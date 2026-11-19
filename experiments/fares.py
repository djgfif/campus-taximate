"""C5 paired quote/paid analysis. Standard library only; no network calls."""
from __future__ import annotations

import argparse
import csv
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent
FIELDS = (ROOT / "templates" / "actual_fares.csv").read_text(encoding="utf-8").strip().split(",")
COHORTS = ("prospective", "historical_requery")


def quantile(values, fraction):
    """Linear interpolation (R7 / numpy default), including n=1."""
    values = sorted(values)
    if not values:
        return None
    position = (len(values) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    return values[low] + (values[high] - values[low]) * (position - low)


def amount(value):
    try:
        parsed = Decimal(value)
        if not parsed.is_finite() or parsed != parsed.to_integral() or not 0 < parsed <= 10_000_000:
            raise ValueError("invalid_amount")
        return int(parsed)
    except (InvalidOperation, TypeError):
        raise ValueError("invalid_amount") from None


def timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.utcoffset() is None:
            raise ValueError()
        return parsed
    except (ValueError, AttributeError):
        raise ValueError("missing_or_invalid_timezone") from None


def validate(row):
    if any(not row.get(key, "").strip() for key in FIELDS):
        raise ValueError("missing_field")
    if row["cohort"] not in COHORTS:
        raise ValueError("invalid_cohort")
    if row["currency"] != "KRW":
        raise ValueError("currency_not_KRW")
    expected, actual = amount(row["expected_krw"]), amount(row["actual_krw"])
    ride, quote = timestamp(row["ride_at"]), timestamp(row["quote_at"])
    if row["cohort"] == "prospective" and quote > ride:
        raise ValueError("prospective_quote_after_ride")
    for kind in ("toll", "surcharge"):
        before, after = row[f"expected_{kind}_scope"], row[f"actual_{kind}_scope"]
        if before not in ("included", "excluded") or after not in ("included", "excluded"):
            raise ValueError(f"unknown_{kind}_scope")
        if before != after:
            raise ValueError(f"mismatched_{kind}_scope")
    if row["quote_source"] not in ("kakao_quote", "recorded_app_quote"):
        raise ValueError("invalid_quote_source")
    if row["actual_source"] not in ("meter_receipt", "payment_receipt"):
        raise ValueError("actual_payment_evidence_required")
    return {"cohort": row["cohort"], "expected": expected, "actual": actual,
            "signed_relative": (actual - expected) / expected,
            # Conventional MAPE uses observed actual amount as denominator.
            "ape": abs(actual - expected) / actual,
            "absolute_krw": abs(actual - expected)}


def metrics(rows):
    if not rows:
        return {"n": 0, "mae_krw": None, "mape": None,
                "within_15pct_count": 0, "within_15pct_rate": None,
                "above_15pct_count": 0, "above_15pct_rate": None,
                "signed_relative": {key: None for key in ("mean", "median", "p90", "p95", "min", "max")}}
    relative = [row["signed_relative"] for row in rows]
    inside = sum(abs(row["actual"] - row["expected"]) * 100 <= row["expected"] * 15 for row in rows)
    above = sum((row["actual"] - row["expected"]) * 100 > row["expected"] * 15 for row in rows)
    return {"n": len(rows), "mae_krw": statistics.mean(row["absolute_krw"] for row in rows),
            "mape": statistics.mean(row["ape"] for row in rows),
            "within_15pct_count": inside, "within_15pct_rate": inside / len(rows),
            "above_15pct_count": above, "above_15pct_rate": above / len(rows),
            "signed_relative": {"mean": statistics.mean(relative), "median": statistics.median(relative),
                                "p90": quantile(relative, .90), "p95": quantile(relative, .95),
                                "min": min(relative), "max": max(relative)}}


def analyze(path):
    path = Path(path)
    groups = {cohort: [] for cohort in COHORTS}
    excluded = Counter()
    private_details = []
    raw = path.read_bytes()
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig"), newline=""))
    if not reader.fieldnames or set(FIELDS) - set(reader.fieldnames) or len(set(reader.fieldnames)) != len(reader.fieldnames):
        raise ValueError("CSV header does not contain all required fields")
    records = [{key: (value or "").strip() if key is not None else value for key, value in row.items()} for row in reader]
    # Exclude every member of duplicate IDs/identical trip fingerprints, not just the later row.
    id_counts = Counter(row.get("record_id", "").strip() for row in records)
    def fingerprint(row):
        values = [row.get(key, "").strip() for key in ("origin_label", "destination_label", "ride_at", "actual_krw")]
        try:
            values[2] = timestamp(values[2]).astimezone(timezone.utc).isoformat()
            values[3] = str(amount(values[3]))
        except ValueError:
            pass
        return tuple(values)
    trip_counts = Counter(fingerprint(row) for row in records)
    for line, row in enumerate(records, 2):
        row = {key: (value or "").strip() for key, value in row.items() if key is not None}
        try:
            if None in records[line - 2]:
                raise ValueError("extra_csv_values")
            if id_counts[row.get("record_id", "")] > 1 or trip_counts[fingerprint(row)] > 1:
                raise ValueError("duplicate_record")
            accepted = validate(row)
            groups[accepted["cohort"]].append(accepted)
            private_details.append({"line": line, "record_id": row["record_id"], **accepted})
        except ValueError as error:
            excluded[str(error)] += 1
            private_details.append({"line": line, "record_id": row.get("record_id", ""), "excluded": str(error)})
    return {"schema_version": 1, "target_prospective_records": 30, "input_sha256": hashlib.sha256(raw).hexdigest(),
            "rows_received": len(records), "rows_accepted": sum(map(len, groups.values())),
            "rows_excluded": sum(excluded.values()), "exclusion_reasons": dict(sorted(excluded.items())),
            "cohorts": {key: metrics(value) for key, value in groups.items()},
            "measurement_status": "NO_OBSERVED_FARES" if not any(groups.values()) else "OBSERVED_SAMPLE_ONLY",
            "new_api_calls": 0,
            "definitions": {"signed_relative": "(actual-expected)/expected", "mape": "mean(abs(actual-expected)/actual)",
                            "within_15pct": "abs(actual-expected)/expected <= 0.15", "above_15pct": "(actual-expected)/expected > 0.15"}}, private_details


def markdown(report):
    lines = ["# 실제 요금 검증 결과", "", f"수집 {report['rows_received']}건, 포함 {report['rows_accepted']}건, 제외 {report['rows_excluded']}건.",
             "전향적 수집 목표는 30건이다. 표본이 없으면 정확도나 15% 안전성을 주장할 수 없다.", "",
             "| 자료 구분 | 건수 | MAE(원) | MAPE | ±15% 이내 | +15% 초과 |", "|---|---:|---:|---:|---:|---:|"]
    for key, label in (("prospective", "탑승 전 예상액과 실제액"), ("historical_requery", "과거 실제액과 현재 재조회")):
        item = report["cohorts"][key]
        fmt = lambda value, percent=False: "미측정" if value is None else f"{value * (100 if percent else 1):.2f}" + ("%" if percent else "")
        lines.append(f"| {label} | {item['n']} | {fmt(item['mae_krw'])} | {fmt(item['mape'], True)} | {fmt(item['within_15pct_rate'], True)} | {item['above_15pct_count']} |")
    lines += ["", "과거 영수증과 현재 재조회는 당시 예측 정확도와 다르므로 합산하지 않는다.",
              "MAPE의 분모는 실제액이고, ±15% 및 상방 초과 판정의 분모는 예상액이다.",
              "통행료·할증 범위가 다르거나 증빙이 없는 기록은 제외한다. 원본·개별 결과는 비공개 보관한다.",
              "기존 실험의 모델 대 카카오 오차는 이 실측 결과를 대체하지 않는다.", "",
              f"제외 사유: `{json.dumps(report['exclusion_reasons'], ensure_ascii=False)}`", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, nargs="?", default=ROOT / "templates" / "actual_fares.csv")
    parser.add_argument("--output", type=Path, default=ROOT / "reports" / "actual-fares.json")
    parser.add_argument("--private-output", type=Path, default=ROOT / "private" / "actual-fares-details.json")
    args = parser.parse_args()
    # This CLI deliberately never emits record IDs/routes/amounts to the public output.
    report, details = analyze(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.output.with_suffix(".md").write_text(markdown(report), encoding="utf-8")
    args.private_output.parent.mkdir(parents=True, exist_ok=True)
    args.private_output.write_text(json.dumps(details, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"C5: received={report['rows_received']} accepted={report['rows_accepted']} excluded={report['rows_excluded']}")


if __name__ == "__main__":
    main()
