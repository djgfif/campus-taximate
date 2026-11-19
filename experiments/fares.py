"""C5 public trip experiments and optional private paired quote/paid analysis.

Only the public-data mode needs DuckDB. No Kakao or payment API is called.
"""
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
    return {"schema_version": 1, "target_prospective_records": None, "input_sha256": hashlib.sha256(raw).hexdigest(),
            "rows_received": len(records), "rows_accepted": sum(map(len, groups.values())),
            "rows_excluded": sum(excluded.values()), "exclusion_reasons": dict(sorted(excluded.items())),
            "cohorts": {key: metrics(value) for key, value in groups.items()},
            "measurement_status": "NO_OBSERVED_FARES" if not any(groups.values()) else "OBSERVED_SAMPLE_ONLY",
            "new_api_calls": 0,
            "definitions": {"signed_relative": "(actual-expected)/expected", "mape": "mean(abs(actual-expected)/actual)",
                            "within_15pct": "abs(actual-expected)/expected <= 0.15", "above_15pct": "(actual-expected)/expected > 0.15"}}, private_details


def markdown(report):
    lines = ["# 실제 요금 검증 결과", "", f"수집 {report['rows_received']}건, 포함 {report['rows_accepted']}건, 제외 {report['rows_excluded']}건.",
             "선택적 개인 CSV 분석이다. 팀원 탑승 수집 계획은 폐기했으며 주 실험은 공개자료를 사용한다.", "",
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


PUBLIC_CACHE = ROOT / ".cache"
MIN_OD_SAMPLES = 20  # Fixed before evaluating either country's test period.
SOURCES = {
    "seoul_trips": "https://www.data.go.kr/data/15115859/fileData.do",
    "seoul_tariff": "https://news.seoul.go.kr/traffic/archives/1659",
    "special_taxi_tariff": "https://www.sisul.or.kr/open_content/calltaxi/introduce/receipt.jsp",
    "gyeonggi_tariff": "https://gits.gg.go.kr/web/newboard/5567.do?idx=189653336&mode=view",
    "pocheon_tariff": "https://www.pocheon.go.kr/www/contents.do?key=3878",
    "tims": "https://www.data.go.kr/data/15165682/fileData.do",
    "nyc_trips": "https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page",
    "nyc_tariff": "https://www.nyc.gov/site/tlc/passengers/taxi-fare.page",
    "nyc_lookup": "https://d37ci6vzurychx.cloudfront.net/misc/taxi_zone_lookup.csv",
    "nyc_dictionary": "https://www.nyc.gov/assets/tlc/downloads/pdf/data_dictionary_trip_records_yellow.pdf",
}


def public_metrics(expected, actual):
    """Independent small-sample reference, also used in unit tests.

    MAPE retains the conventional ACTUAL denominator used by the old C5.
    All thresholds and signed/absolute relative errors use EXPECTED.
    """
    if len(expected) != len(actual) or any(not math.isfinite(x) or x <= 0 for x in [*expected, *actual]):
        raise ValueError("positive paired finite fares required")
    e = [(a-p)/p for p, a in zip(expected, actual)]
    n = len(e)
    avg = lambda xs: statistics.mean(xs) if n else None
    return {"n": n, "mae": avg([abs(a-p) for p, a in zip(expected, actual)]),
            "mape": avg([abs(a-p)/a for p, a in zip(expected, actual)]),
            "median_absolute_relative": quantile([abs(x) for x in e], .5),
            "within_10pct_rate": avg([abs(a-p)*100 <= p*10+1e-8 for p,a in zip(expected,actual)]),
            "within_15pct_rate": avg([abs(a-p)*100 <= p*15+1e-8 for p,a in zip(expected,actual)]),
            "additional_payment_rate": avg([(a-p)*100 > p*15+1e-8 for p,a in zip(expected,actual)]),
            "refund_rate": avg([a < p for p,a in zip(expected,actual)]),
            "signed_relative_percentiles": {str(p): quantile(e, p/100) for p in (5,10,25,50,75,90,95)}}


def trip_exclusion(fare, km, minutes, start, end, od_valid=True, standard=True):
    """Shared ordered filter policy; the SQL CASE below implements this order."""
    if not standard:
        return "non_standard_rate"
    if not all(x is not None and math.isfinite(x) for x in (fare, km, minutes)) or fare <= 0 or fare > 1000000:
        return "invalid_fare_or_missing_numeric"
    if start is None or end is None or end <= start:
        return "invalid_time_order"
    if not .1 <= km <= 160:
        return "distance_outside_0.1_160km"
    if not 1 <= minutes <= 180:
        return "duration_outside_1_180min"
    if not 1 <= km/minutes*60 <= 130:
        return "speed_outside_1_130kmh"
    if not od_valid:
        return "missing_or_unknown_od"
    return None


def korea_tariff(km, minutes, hour=12, kind="seoul"):
    """Tariff PROXY, not a reconstruction of the meter's instantaneous clock.

    Slow seconds = max(0, total seconds - distance / 15 km/h).
    Distance/time ticks are added, with time ticks only after basic distance.
    The pickup-time surcharge is held constant for the whole journey.
    """
    base_km, meters, seconds = (2.,83.,20.) if kind == "pocheon" else (1.6,131.,30.)
    slow = max(0., minutes*60 - km/15*3600)
    ticks = math.ceil(max(0., km-base_km)*1000/meters - 1e-10)
    if km > base_km:
        ticks += math.ceil(slow/seconds - 1e-10)
    factor = (1.3 if hour >= 23 or hour < 4 else 1.) if kind == "pocheon" else (
        1.4 if hour >= 23 or hour < 2 else 1.2 if hour >= 22 or hour < 4 else 1.)
    return round((4800 + 100*ticks)*factor)


def special_taxi_tariff(km):
    return math.floor((1500+280*min(max(0.,km-5),5)+70*max(0.,km-10)+1e-8)/100)*100


def file_manifest(path, url=None):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4*1024*1024), b""):
            h.update(block)
    return {"path": str(Path(path).resolve()), "url": url, "bytes": Path(path).stat().st_size,
            "sha256": h.hexdigest()}


def public_download(url, path):
    """Cache validated downloads; TLS verification stays enabled."""
    import urllib.request
    path = Path(path)
    if path.is_file():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=45) as response, temporary.open("wb") as target:
            import shutil
            shutil.copyfileobj(response, target)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def sql_path(path):
    return "'" + str(Path(path).resolve()).replace("\\", "/").replace("'", "''") + "'"


def sql_metrics(db, query):
    result = db.execute(f"""WITH v AS ({query}), e AS (
      SELECT *, (actual-expected)/expected AS err FROM v WHERE expected>0 AND actual>0)
      SELECT count(*) AS n, avg(abs(actual-expected)) AS mae,
        avg(abs(actual-expected)/actual) AS mape,
        median(abs(err)) AS median_absolute_relative,
        avg(CASE WHEN abs(actual-expected)*100<=expected*10+1e-8 THEN 1.0 ELSE 0 END) AS within_10pct_rate,
        avg(CASE WHEN abs(actual-expected)*100<=expected*15+1e-8 THEN 1.0 ELSE 0 END) AS within_15pct_rate,
        avg(CASE WHEN (actual-expected)*100>expected*15+1e-8 THEN 1.0 ELSE 0 END) AS additional_payment_rate,
        avg(CASE WHEN actual<expected THEN 1.0 ELSE 0 END) AS refund_rate,
        quantile_cont(err,[.05,.10,.25,.50,.75,.90,.95]) AS percentiles FROM e""").fetchone()
    keys = [col[0] for col in db.description]
    out = dict(zip(keys,result))
    out["signed_relative_percentiles"] = dict(zip(map(str,(5,10,25,50,75,90,95)),out.pop("percentiles") or [None]*7))
    return out


def segment_metrics(db, query):
    result = {"overall": sql_metrics(db,query), "by_distance_km": {}, "by_timeband": {}}
    for label, condition in (("0.1-3","km<3"),("3-10","km>=3 AND km<10"),("10-30","km>=10 AND km<30"),("30-160","km>=30")):
        result["by_distance_km"][label] = sql_metrics(db,f"SELECT * FROM ({query}) WHERE {condition}")
    for band in range(8):
        result["by_timeband"][str(band)] = sql_metrics(db,f"SELECT * FROM ({query}) WHERE band={band}")
    return result


def prepare_public_sql(db):
    db.execute("SET threads=4")
    # All timestamp arithmetic is local wall-clock: Asia/Seoul or America/New_York.
    db.execute("""CREATE OR REPLACE MACRO bandof(t) AS
      (CASE WHEN isodow(t)>5 THEN 4 ELSE 0 END + CAST(floor(hour(t)/6) AS INTEGER))""")
    db.execute("""CREATE OR REPLACE MACRO krfare(km,mins,h,basekm,meters,secs,surge) AS
      round((4800+100*(ceil(greatest(0,km-basekm)*1000/meters-1e-10)+
        CASE WHEN km>basekm THEN ceil(greatest(0,mins*60-km/15*3600)/secs-1e-10) ELSE 0 END))*surge)""")
    db.execute("""CREATE OR REPLACE MACRO seoulfare(km,mins,h) AS krfare(km,mins,h,1.6,131.,30.,
      CASE WHEN h>=23 OR h<2 THEN 1.4 WHEN h>=22 OR h<4 THEN 1.2 ELSE 1. END)""")
    # NYC charges distance ABOVE 12 mph OR time at/below 12 mph, never all time plus all distance.
    # With only totals, max(5*miles,minutes) is a lower-bound proxy for mixed-speed ticks.
    db.execute("CREATE OR REPLACE MACRO nycfare(km,mins) AS 3.+.7*ceil(greatest(km/1.609344*5,mins)-1e-10)")
    db.execute("""CREATE OR REPLACE MACRO specialfare(km) AS
      floor((1500+280*least(greatest(0,km-5),5)+70*greatest(0,km-10)+1e-8)/100)*100""")


def error_decomposition(db,table,predicted,actual_route,distance_only,observed):
    """Exact multiplicative identity, not causal identification of traffic effects."""
    q=f"""SELECT *,{predicted} AS p,{actual_route} AS r,{distance_only} AS d,{observed} AS a
      FROM {table} WHERE pred_km IS NOT NULL"""
    data=db.execute(f"""SELECT count(*),avg(ln(a/p)),avg(ln(a/r)),avg(ln(d/p)),avg(ln(r/d)),
      max(abs(a/p-(a/r)*(d/p)*(r/d))) FROM ({q})""").fetchone()
    keys=("n","mean_log_total_factor","mean_log_meter_reconstruction_factor","mean_log_distance_factor",
          "mean_log_duration_interaction_factor","max_factorization_residual")
    return {**dict(zip(keys,data)),"identity":"actual/predicted = (actual/tariff_actual_route) * (tariff_actual_distance_predicted_time/predicted) * (tariff_actual_route/tariff_actual_distance_predicted_time)",
      "interpretation":"Reconstruction, distance prediction, and duration/interaction factors; duration is not a causally isolated traffic effect."}


def clean_public(db, source, label, period_start, period_end):
    db.execute(f"""CREATE OR REPLACE TABLE {label}_filtered AS SELECT *,
      CASE WHEN NOT standard THEN 'non_standard_rate'
        WHEN fare IS NULL OR NOT isfinite(fare) OR fare<=0 OR fare>1000000 OR
          km IS NULL OR NOT isfinite(km) OR mins IS NULL OR NOT isfinite(mins) THEN 'invalid_fare_or_missing_numeric'
        WHEN pickup IS NULL OR dropoff IS NULL OR dropoff<=pickup THEN 'invalid_time_order'
        WHEN pickup < TIMESTAMP '{period_start}' OR pickup >= TIMESTAMP '{period_end}' THEN 'pickup_outside_period'
        WHEN km<.1 OR km>160 THEN 'distance_outside_0.1_160km'
        WHEN mins<1 OR mins>180 THEN 'duration_outside_1_180min'
        WHEN km/mins*60<1 OR km/mins*60>130 THEN 'speed_outside_1_130kmh'
        WHEN pu IS NULL OR trim(pu)='' OR dest IS NULL OR trim(dest)='' THEN 'missing_or_unknown_od'
        WHEN canceled THEN 'canceled' ELSE NULL END AS excluded
      FROM ({source})""")
    counts = dict(db.execute(f"SELECT coalesce(excluded,'accepted'),count(*) FROM {label}_filtered GROUP BY excluded").fetchall())
    received = sum(counts.values())
    accepted = counts.pop("accepted",0)
    db.execute(f"CREATE OR REPLACE TABLE {label} AS SELECT *,bandof(pickup) AS band FROM {label}_filtered WHERE excluded IS NULL")
    assert received == accepted + sum(counts.values())
    return {"rows":received,"accepted":accepted,"excluded":sum(counts.values()),"exclusion_reasons":counts}


def od_evaluation(db, train, test, tariff, actual_expr, prefix):
    """One-to-one fallback joins. Test outcomes never enter the predictor."""
    for suffix, group in (("band","pu,dest,band"),("od","pu,dest")):
        db.execute(f"""CREATE OR REPLACE TABLE {prefix}_{suffix} AS SELECT {group},count(*) AS n,
          median(km) AS pred_km,median(mins) AS pred_mins,median(fare) AS pred_fare
          FROM {train} GROUP BY {group} HAVING count(*)>={MIN_OD_SAMPLES}""")
    db.execute(f"""CREATE OR REPLACE TABLE {prefix}_eval AS SELECT t.*,
      coalesce(b.pred_km,o.pred_km) AS pred_km,coalesce(b.pred_mins,o.pred_mins) AS pred_mins,
      coalesce(b.pred_fare,o.pred_fare) AS pred_fare,
      CASE WHEN b.n IS NOT NULL THEN 'od_timeband' WHEN o.n IS NOT NULL THEN 'od_only' ELSE 'insufficient_training' END AS prediction
      FROM {test} t LEFT JOIN {prefix}_band b USING(pu,dest,band) LEFT JOIN {prefix}_od o USING(pu,dest)""")
    ntest = db.execute(f"SELECT count(*) FROM {test}").fetchone()[0]
    assert db.execute(f"SELECT count(*) FROM {prefix}_eval").fetchone()[0] == ntest
    counts = dict(db.execute(f"SELECT prediction,count(*) FROM {prefix}_eval GROUP BY prediction").fetchall())
    base = f"SELECT *,{tariff} AS expected,{actual_expr} AS actual FROM {prefix}_eval WHERE pred_km IS NOT NULL"
    metrics_result = segment_metrics(db,base)
    errors = PUBLIC_CACHE / (prefix+"-errors.csv")
    db.execute(f"COPY (SELECT (actual-expected)/expected AS epsilon FROM ({base})) TO {sql_path(errors)} (HEADER false)")
    return {"prediction_coverage":counts,"evaluation_valid_rows":ntest,**metrics_result,
            "errors":file_manifest(errors),"error_order":"source table order; file hash pins bootstrap input"}


def seoul_public(db, path):
    # Whitelist columns: sensitive purpose/disability fields never enter the analysis table.
    safe = PUBLIC_CACHE / "seoul-safe.csv"
    encoding = "utf-8-sig"
    try:
        with path.open(encoding=encoding) as stream:
            stream.read(8192)
    except UnicodeDecodeError:
        encoding = "cp949"
    columns = ["승차일시","하차일시","취소일시","출발구","출발동","목적구","목적동","요금","승차거리"]
    with path.open(encoding=encoding,newline="") as stream, safe.open("w",encoding="utf-8",newline="") as target:
        reader = csv.DictReader(stream)
        if set(columns)-set(reader.fieldnames or []):
            raise ValueError("Seoul CSV lacks required columns")
        writer = csv.DictWriter(target,fieldnames=columns); writer.writeheader()
        for row in reader:
            writer.writerow({key:row.get(key,"") for key in columns})
    source = f"""SELECT try_cast(승차일시 AS TIMESTAMP) AS pickup,try_cast(하차일시 AS TIMESTAMP) AS dropoff,
      try_cast(요금 AS DOUBLE) AS fare,try_cast(승차거리 AS DOUBLE)/1000 AS km,
      epoch(try_cast(하차일시 AS TIMESTAMP)-try_cast(승차일시 AS TIMESTAMP))/60 AS mins,
      CASE WHEN nullif(trim(출발구),'') IS NOT NULL AND nullif(trim(출발동),'') IS NOT NULL THEN 출발구||':'||출발동 END AS pu,
      CASE WHEN nullif(trim(목적구),'') IS NOT NULL AND nullif(trim(목적동),'') IS NOT NULL THEN 목적구||':'||목적동 END AS dest,
      true AS standard,coalesce(length(trim(취소일시))>0,false) AS canceled
      FROM read_csv({sql_path(safe)},header=true,all_varchar=true)"""
    filt = clean_public(db,source,"kr","2025-01-01","2026-01-01")
    db.execute("CREATE OR REPLACE TABLE kr_train AS SELECT * FROM kr WHERE pickup<TIMESTAMP '2025-10-01'")
    db.execute("CREATE OR REPLACE TABLE kr_test AS SELECT * FROM kr WHERE pickup>=TIMESTAMP '2025-10-01'")
    result = od_evaluation(db,"kr_train","kr_test","seoulfare(pred_km,pred_mins,hour(pickup))",
                           "seoulfare(km,mins,hour(pickup))","kr")
    result["actual_recorded_special_taxi_fare"] = segment_metrics(db,
        "SELECT *,pred_fare AS expected,fare AS actual FROM kr_eval WHERE pred_fare IS NOT NULL")
    result["special_tariff_on_actual_route"] = segment_metrics(db,
        "SELECT *,specialfare(km) AS expected,fare AS actual FROM kr_test")
    result["special_tariff_on_predicted_route"] = segment_metrics(db,
        "SELECT *,specialfare(pred_km) AS expected,fare AS actual FROM kr_eval WHERE pred_km IS NOT NULL")
    result["decomposition_special_actual_fare"] = error_decomposition(db,"kr_eval","specialfare(pred_km)",
        "specialfare(km)","specialfare(km)","fare")
    result["decomposition_general_counterfactual"] = error_decomposition(db,"kr_eval", "seoulfare(pred_km,pred_mins,hour(pickup))",
        "seoulfare(km,mins,hour(pickup))","seoulfare(km,pred_mins,hour(pickup))","seoulfare(km,mins,hour(pickup))")
    result["route_prediction_metrics"] = {
        "distance_km":sql_metrics(db,"SELECT pred_km AS expected,km AS actual FROM kr_eval WHERE pred_km IS NOT NULL"),
        "duration_minutes":sql_metrics(db,"SELECT pred_mins AS expected,mins AS actual FROM kr_eval WHERE pred_mins IS NOT NULL")}
    result.update(status="COMPLETED",source={**file_manifest(path,SOURCES["seoul_trips"]),"rows":filt['rows']},filters=filt,
                  train_n=db.execute("SELECT count(*) FROM kr_train").fetchone()[0],
                  train_period="2025-01..09",test_period="2025-10..12",currency="KRW",
                  interpretation="General taxi tariff counterfactual on observed special-taxi routes; NOT actual general-taxi receipts")
    return result


def nyc_public(db, download=False):
    import re
    import urllib.request
    page_path = PUBLIC_CACHE / "tlc-page.html"
    if download or not page_path.is_file():
        with urllib.request.urlopen(SOURCES["nyc_trips"],timeout=45) as response:
            page_path.write_bytes(response.read())
    months = sorted(set(re.findall(r"yellow_tripdata_(\d{4}-\d{2})\.parquet",page_path.read_text(encoding="utf-8"))))
    if not months:
        raise ValueError("No officially linked Yellow parquet months")
    latest = datetime.strptime(months[-1],"%Y-%m")
    from datetime import timedelta
    previous = (latest.replace(day=1)-timedelta(days=1)).strftime("%Y-%m")
    if previous not in months:
        raise ValueError("Previous month not linked on official page")
    manifests, filters = [], {}
    for label,month in (("nyc_train",previous),("nyc_test",months[-1])):
        url = f"https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_{month}.parquet"
        path = PUBLIC_CACHE / f"yellow_tripdata_{month}.parquet"
        if download:
            public_download(url,path)
        source = f"""SELECT tpep_pickup_datetime AS pickup,tpep_dropoff_datetime AS dropoff,
          fare_amount AS fare,trip_distance*1.609344 AS km,epoch(tpep_dropoff_datetime-tpep_pickup_datetime)/60 AS mins,
          CASE WHEN PULocationID BETWEEN 1 AND 263 THEN cast(PULocationID AS VARCHAR) END AS pu,
          CASE WHEN DOLocationID BETWEEN 1 AND 263 THEN cast(DOLocationID AS VARCHAR) END AS dest,
          coalesce(RatecodeID=1,false) AS standard,false AS canceled FROM read_parquet({sql_path(path)})"""
        start=datetime.strptime(month,"%Y-%m")
        end=(start.replace(day=28)+timedelta(days=4)).replace(day=1)
        filters[label] = clean_public(db,source,label,start.date().isoformat(),end.date().isoformat())
        manifests.append({"month":month,**file_manifest(path,url),"rows":filters[label]["rows"]})
    result = od_evaluation(db,"nyc_train","nyc_test","nycfare(pred_km,pred_mins)","fare","nyc")
    result["method_b_observed_fare_median"] = segment_metrics(db,
        "SELECT *,pred_fare AS expected,fare AS actual FROM nyc_eval WHERE pred_fare IS NOT NULL")
    result["method_c_tariff_proxy_on_actual_route"] = segment_metrics(db,
        "SELECT *,nycfare(km,mins) AS expected,fare AS actual FROM nyc_test")
    result["method_c_same_coverage_as_a"] = segment_metrics(db,
        "SELECT *,nycfare(km,mins) AS expected,fare AS actual FROM nyc_eval WHERE pred_km IS NOT NULL")
    result["error_decomposition"] = error_decomposition(db,"nyc_eval","nycfare(pred_km,pred_mins)",
        "nycfare(km,mins)","nycfare(km,pred_mins)","fare")
    # Compare two unidentifiable extreme clock reconstructions; do not choose
    # a formula because its retrospective MAPE looks better.
    result["method_c_all_time_plus_all_distance_upper_proxy"] = segment_metrics(db,
        "SELECT *,3.+.7*ceil(km/1.609344*5+mins-1e-10) AS expected,fare AS actual FROM nyc_test")
    result["fare_scope"]={"actual":"fare_amount = meter time-and-distance fare",
        "separate_excluded_columns":["extra","mta_tax","improvement_surcharge","tip_amount","tolls_amount",
          "congestion_surcharge","Airport_fee","cbd_congestion_fee"],
        "meter_identifiability":"No subtrip speed clock. max(distance ticks,total minute ticks) is a lower bound; distance ticks+total minute ticks is an upper proxy. Neither is the exact mixed-speed meter.",
        "rate_period":"2022-12-19 onwards: $3.00 start, $0.70 / 1/5 mile above 12mph OR /60 seconds in slow/stopped traffic; standard RatecodeID=1 only"}
    result.update(status="COMPLETED",files=manifests,filters=filters,train_period=previous,test_period=months[-1],
                  currency="USD",latest_page=file_manifest(page_path,SOURCES["nyc_trips"]))
    return result


def tims_public(db, legacy):
    """Annual caches are explicitly supplementary; never label them grid-months."""
    results={}
    for label,name in (("national","tims_annual_grids.csv"),("commute","tims_commute_grids.csv")):
        path=legacy/"data"/name
        db.execute(f"""CREATE OR REPLACE TABLE tims_{label} AS SELECT *,distance_m/trips/1000 AS km,
          time_s/trips/60 AS mins,fare_won/trips AS actual FROM read_csv_auto({sql_path(path)})
          WHERE trips>0 AND fare_won>0 AND distance_m>0 AND time_s>0""")
        results[label]={"source":file_manifest(path),"grain":"annual_grid","comparisons":{}}
        for tariff in ("seoul_gyeonggi_standard","pocheon"):
            formula = "krfare(km,mins,12,2.,83.,20.,1.)" if tariff=="pocheon" else "seoulfare(km,mins,12)"
            q=f"SELECT *,{formula} AS expected FROM tims_{label}"
            value=sql_metrics(db,q)
            means=db.execute(f"SELECT sum(trips),sum(actual*trips)/sum(trips),sum(expected*trips)/sum(trips),sum(abs(actual-expected)*trips)/sum(trips) FROM ({q})").fetchone()
            value.update(trips=means[0],trip_weighted_actual_mean=means[1],trip_weighted_tariff_mean=means[2],trip_weighted_mae=means[3])
            results[label]["comparisons"][tariff]=value
    archive=PUBLIC_CACHE/'kr-public'/'tims_2025.zip'
    results["monthly_status"]="BLOCKED_MISSING_MONTHLY_ARCHIVE"
    if archive.is_file():
        import zipfile
        normalized=PUBLIC_CACHE/'tims-monthly-safe.csv'
        members=[]
        with zipfile.ZipFile(archive) as z, normalized.open('w',encoding='utf-8',newline='') as out:
            writer=csv.writer(out);writer.writerow(['grid','month','trips','fare_won','distance_m','time_s'])
            for name in sorted(z.namelist()):
                if not name.endswith('.csv'): continue
                raw=z.read(name); rows=0
                for row in csv.DictReader(io.StringIO(raw.decode('cp949'),newline='')):
                    writer.writerow([row[k] for k in ('500미터격자','년월','영업건수(건)','영업금액(원)','영업거리(미터)','영업시간(초)')]);rows+=1
                members.append({'name':name,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'rows':rows})
        db.execute(f"CREATE OR REPLACE TABLE tims_raw AS SELECT * FROM read_csv_auto({sql_path(normalized)})")
        duplicates=db.execute('SELECT count(*) FROM (SELECT grid,month FROM tims_raw GROUP BY grid,month HAVING count(*)>1)').fetchone()[0]
        if duplicates: raise ValueError('TIMS duplicate grid-month rows; stop rather than double count')
        alln=db.execute('SELECT count(*) FROM tims_raw').fetchone()[0]
        db.execute("""CREATE OR REPLACE TABLE tims_monthly AS SELECT *,distance_m/trips/1000 AS km,
          time_s/trips/60 AS mins,fare_won/trips AS actual FROM tims_raw
          WHERE trips>0 AND fare_won>0 AND distance_m>0 AND time_s>0
            AND isfinite(fare_won) AND isfinite(distance_m) AND isfinite(time_s)""")
        valid=db.execute('SELECT count(*) FROM tims_monthly').fetchone()[0]
        monthly={'grain':'grid_month','source':file_manifest(archive,SOURCES['tims']),'members':members,
                 'rows':alln,'valid':valid,'excluded_nonpositive_or_nonfinite':alln-valid,'duplicate_grid_month':duplicates}
        for label,condition in (('national','true'),('commute',"grid IN (SELECT grid FROM tims_commute)")):
            monthly[label]={'comparisons':{},'by_month':{}}
            for tariff in ('seoul_gyeonggi_standard','pocheon'):
                formula='krfare(km,mins,12,2.,83.,20.,1.)' if tariff=='pocheon' else 'seoulfare(km,mins,12)'
                q=f'SELECT *,{formula} AS expected FROM tims_monthly WHERE {condition}'
                m=sql_metrics(db,q)
                weighted=db.execute(f'SELECT sum(trips),sum(actual*trips)/sum(trips),sum(expected*trips)/sum(trips),sum(abs(actual-expected)*trips)/sum(trips) FROM ({q})').fetchone()
                m.update(trips=weighted[0],trip_weighted_actual_mean=weighted[1],trip_weighted_tariff_mean=weighted[2],trip_weighted_mae=weighted[3])
                monthly[label]['comparisons'][tariff]=m
                for (month,) in db.execute('SELECT DISTINCT month FROM tims_monthly ORDER BY month').fetchall():
                    monthly[label]['by_month'].setdefault(str(month),{})[tariff]=sql_metrics(db,f"SELECT * FROM ({q}) WHERE month='{month}'")
        results.update(monthly_status='COMPLETED',monthly=monthly)
    results["limitations"]=["Monthly raw archive is analyzed separately from annual caches; annual values do not reconstruct grid-month errors.",
      "Nonlinear tariff at an aggregate mean differs from mean trip fares; national regions use different tariffs.",
      "No low-speed clock, night surcharge, border or toll detail; this is a tariff proxy comparison, not validation of its exact formula.",
      "Commute grids describe pickups 5-25 km from Daejin, not confirmed Daejin student trips."]
    return results


def public_markdown(report):
    lines=["# C5 공개 운행 기록 요금 실험","",report["conclusion"],"", "발표용 한 문장: "+report["presentation_sentence"],"",
      "## 한국 주 실험: 서울 실제 운행 경로의 일반택시 요금 환산","",
      "장애인콜택시의 실제 도로 운행에 일반택시 요금표를 적용한 반사실 실험이다. 일반택시 영수증·카카오 견적 검증으로 표현하지 않는다.",""]
    kr=report["korea"]
    if kr["status"]!="COMPLETED":
        lines += ["**미측정**: "+kr["blocker"],""]
    table=[]
    if kr["status"]=="COMPLETED":
        table += [("장콜 공식 검증: 실제 거리→장콜 요금표",kr["special_tariff_on_actual_route"]["overall"]),
                  ("장콜 경로 예측: 학습 거리→장콜 요금표",kr["special_tariff_on_predicted_route"]["overall"]),
                  ("장콜 실제 요금: 학습 OD 요금 중앙값",kr["actual_recorded_special_taxi_fare"]["overall"]),
                  ("한국 발표 주 지표 (i): 일반택시 환산",kr["overall"])]
    def add_table(rows):
        lines.extend(["| 방법 | n | MAE | MAPE | 중앙 절대오차율 | ±10% | ±15% | +15% 초과 | 실제<예상 |","|---|---:|---:|---:|---:|---:|---:|---:|---:|"])
        for label,m in rows:
            fmt=lambda x: "미측정" if x is None else f"{x:.2%}"
            lines.append(f"| {label} | {m['n']:,} | {m['mae']:.2f} | "+" | ".join(fmt(m[k]) for k in ("mape","median_absolute_relative","within_10pct_rate","within_15pct_rate","additional_payment_rate","refund_rate"))+" |")
        lines.append("")
    if table: add_table(table)
    if kr['status']=='COMPLETED':
        lines += [f"학습 {kr['train_period']} {kr['train_n']:,}건, 평가 유효 {kr['evaluation_valid_rows']:,}건. 예측 범위: {json.dumps(kr['prediction_coverage'],ensure_ascii=False)}.",
          "장콜 공식은 기본 5km 1,500원, 5~10km 280원/km, 이후 70원/km, 100원 미만 절사이며 시간·지역 할증이 없다. 실제 결제 잔차가 0이라고 가정하지 않는다.","",
          "| 일반택시 환산 주 지표 분해 | n | MAPE | ±15% | +15% 초과 |","|---|---:|---:|---:|---:|"]
        for dim,values in (('거리 km',kr['by_distance_km']),('시간대',kr['by_timeband'])):
            for label,m in values.items():
                if m['n']: lines.append(f"| {dim} {label} | {m['n']:,} | {m['mape']:.2%} | {m['within_15pct_rate']:.2%} | {m['additional_payment_rate']:.2%} |")
        lines.append('')
    lines += ["## TIMS 한국 일반택시 보조","", "월별 원본 상태: "+report["tims"]["monthly_status"]+". 아래 주 비교는 **격자-월 평균**이다. 연간 격자 캐시는 JSON에 별도로 보존한다.",""]
    tims=report['tims'].get('monthly',report['tims'])
    add_table([(label+" / "+tariff,m) for label in ("commute","national") for tariff,m in tims[label]["comparisons"].items()])
    lines += [*['- '+x for x in report['tims']['limitations']],"","## 해외 실제 미터 요금 참고: NYC Yellow","",
      f"학습 {report['nyc']['train_period']}, 평가 {report['nyc']['test_period']}. 실제액은 fare_amount만 사용하며 팁·세금·통행료·surcharge를 제외한다.",""]
    add_table([("c 먼저: 실제 경로→요금표 하한 근사",report['nyc']['method_c_tariff_proxy_on_actual_route']['overall']),
      ("c 참고: 거리+전체시간 상한 근사",report['nyc']['method_c_all_time_plus_all_distance_upper_proxy']['overall']),
      ("a: 이전 월 OD 경로→요금표 하한 근사",report['nyc']['overall']),
      ("b: 이전 월 OD 실제 요금 중앙값",report['nyc']['method_b_observed_fare_median']['overall'])])
    c=report['nyc']['method_c_tariff_proxy_on_actual_route']['overall']; a=report['nyc']['overall']
    lines += [f"c의 MAPE {c['mape']:.2%}는 저속 clock 부재와 데이터/미터 재구성 잔차를 포함한다. a의 MAPE {a['mape']:.2%}를 순수 경로 예측 오차로 해석할 수 없다.",
      "fare_amount 외 extra, mta_tax, improvement_surcharge, congestion_surcharge, cbd_congestion_fee, Airport_fee, tip_amount, tolls_amount는 별도 열이며 비교 금액에 더하지 않는다.","",
      "## 오차 원인 분해 (같은 평가 표본)","",
      "아래는 log(금액 배수)의 평균이다. 세 요인 합이 총 log 오차이며 MAPE를 더한 값이 아니다. 시간 항은 교통 변동을 포함하지만 인과적으로 교통 효과만 분리한 값은 아니다.","",
      "| 비교 | n | 미터 재구성 | 거리 예측 | 시간·상호작용 | 총 log 오차 |","|---|---:|---:|---:|---:|---:|"]
    decomps=[('NYC a',report['nyc']['error_decomposition'])]
    if kr['status']=='COMPLETED':
        decomps=[('한국 장콜 실제 요금',kr['decomposition_special_actual_fare']),('한국 일반택시 환산',kr['decomposition_general_counterfactual'])]+decomps
    for name,d in decomps:
        lines.append(f"| {name} | {d['n']:,} | {d['mean_log_meter_reconstruction_factor']:.6f} | {d['mean_log_distance_factor']:.6f} | {d['mean_log_duration_interaction_factor']:.6f} | {d['mean_log_total_factor']:.6f} |")
    lines += ["", "일반택시 환산의 미터 재구성 항이 0인 것은 양쪽에 같은 근사식을 적용한 정의의 결과다. 실제 일반택시 미터 정확도를 입증하지 않는다.",""]
    lines += ["## 제외·예측 범위","","| 자료 | 입력 | 유효 | 제외 | 사유 |","|---|---:|---:|---:|---|"]
    filters=dict(report['nyc']['filters'])
    if kr['status']=='COMPLETED': filters['korea']=kr['filters']
    for name,f in filters.items():
        lines.append(f"| {name} | {f['rows']:,} | {f['accepted']:,} | {f['excluded']:,} | {json.dumps(f['exclusion_reasons'],ensure_ascii=False)} |")
    lines += ["", "OD 최소 표본은 사전 고정 20건. 평일/주말×6시간대(8칸), 부족하면 같은 OD 전체 시간으로 후퇴, 그래도 부족하면 평가 제외. 모든 평가는 시간상 뒤의 자료만 사용한다.",
      "MAPE 분모는 실제액, 상대오차·임계값 분모는 예상액이다. 상세 백분위·거리대·시간대 지표, 파일 URL/크기/SHA256/행 수는 동명 JSON에 있다.","",
      "## 해석 한계","", *['- '+x for x in report['limitations']],"","## 추가 한국 자료 확인","",*['- '+x for x in report['other_korean_sources']],"","## 출처","",*['- '+k+': '+v for k,v in SOURCES.items()],""]
    return "\n".join(lines)


def public_main():
    parser=argparse.ArgumentParser(description="C5 Korean primary / NYC supplementary public-trip analysis")
    parser.add_argument("--public",action="store_true")
    parser.add_argument("--download",action="store_true",help="Refresh TLC catalogue and download missing linked files")
    parser.add_argument("--seoul-csv",type=Path,default=PUBLIC_CACHE/"kr-public"/"seoul_dcall_2025.csv")
    parser.add_argument("--legacy-realdata",type=Path,default=ROOT.parent.parent/"taximate-matching-sim"/"realdata")
    args=parser.parse_args()
    if args.download:
        import subprocess
        # A failed portal source does not stop the independently available source.
        subprocess.run(["node",str(ROOT/"acquire-public.mjs"),"all"],check=False)
    try: import duckdb
    except ImportError: parser.error("Public mode needs experiments/requirements-public.txt in a repository-local environment")
    PUBLIC_CACHE.mkdir(exist_ok=True)
    db=duckdb.connect(); prepare_public_sql(db)
    if args.seoul_csv.is_file():
        kr=seoul_public(db,args.seoul_csv)
    else:
        kr={"status":"BLOCKED_DOWNLOAD_HTTP_500","n":0,
            "blocker":"data.go.kr 메타데이터·다운로드 정보는 HTTP 200, 로그인 불필요, check-limit needCaptcha=false. 실제 fileDownload.do는 HTTP 500(주소 변경/삭제 오류 화면), 세션 유지 요청은 시간 초과. 원본 CSV 미확보. 숫자를 생성하지 않음.",
            "source_url":SOURCES['seoul_trips']}
    nyc=nyc_public(db,False)
    report={"schema_version":2,"generated_at":datetime.now(timezone.utc).isoformat(),"korea":kr,"nyc":nyc,
      "tims":tims_public(db,args.legacy_realdata),"sources":SOURCES,"min_training_samples":MIN_OD_SAMPLES,
      "definitions":{"signed_relative":"(actual-expected)/expected","mape":"mean(abs(actual-expected)/actual)",
        "timeband":"local pickup hour [0,6),[6,12),[12,18),[18,24); weekend +4",
        "korea_tariff_proxy":"4800+100*(ceil(max(0,km-1.6)*1000/131)+ceil(max(0,seconds-km/15*3600)/30) after base distance); pickup-hour night multiplier",
        "nyc_tariff_proxy":"3+0.7*ceil(max(5*miles,total_minutes)); 2022-12-19 rate; no surcharges"},
      "limitations":["한국 주 지표는 실제 경로의 일반택시 환산액이며 일반택시 실제 결제액이 아니다. 장콜 실제 요금은 별도 보조 지표다.",
        "저속·정차 시간 미공개. 서울은 총시간−15km/h 주행시간으로 근사하며 기본거리 이내 시간요금은 제외한다. 승차 시각 할증률을 전 구간에 고정하고 경계 통과 할증·통행료를 제외한다.",
        "NYC max(거리 tick,시간 tick)은 혼합 속도 주행에서 미터 tick의 하한 근사다. c는 요금표 공식 자체 정확도가 아닌 자료 부족을 포함한 재구성 오차다. 거리+전체시간 상한 근사도 함께 공개하되 결과에 맞춰 주 공식을 바꾸지 않는다.",
        "서울 장콜 공식은 5km 1500원, 5~10km 280원/km, 이후 70원/km, 시간/지역 할증 없음, 100원 미만 절사다. 주차·통행료는 승객 부담이나 데이터에 별도 열이 없어 잔차 원인을 확정할 수 없다.",
        "NYC 표준 요금표는 2022-12-19 이후 기본 $3, tick $0.70. 12mph 초과 거리 또는 저속 분당 중 하나를 부과한다.",
        "OD 구역/동의 범위가 넓고 휴일은 주말로 대체한다. 이전 기간 경로 중앙값은 카카오 알고리즘이 아니다. 이질적 장콜/NYC 오차를 포천 동승에 옮기는 것은 스트레스 가정이다.",
        "반복 이용자 식별자가 없어 사용자 단위 독립성·중복 운행을 확정할 수 없다. 모집단 파일 전체를 사용하되 필터/OD 커버리지 편향이 남는다.",
        "추가 결제·환불 C5 비율은 운행 요금 비교다. 결제자 제외·원 단위 반올림을 적용한 개인 비율은 C6에서 따로 계산한다."],
      "other_korean_sources":["서울시 OA-13550 일별 이용현황 https://data.seoul.go.kr/dataList/OA-13550/L/1/datasetView.do : 일별 평균/집계이므로 OD 개별 평가에 미사용.",
        "서울시 OA-13551 목적지 BEST100 https://data.seoul.go.kr/dataList/OA-13551/L/1/datasetView.do : 순위 집계이며 개별 거리·실제 요금 쌍이 없어 미사용.",
        "경기 광역이동지원센터 https://ggsts.gg.go.kr/ : 개인 이용내역 조회 서비스만 확인; 공개 개별 OD/거리/요금 파일을 확보하지 못해 미사용.",
        "인천교통공사 https://www.ictr.or.kr/main/openinfo/public_list.jsp 및 https://www.ictr.or.kr/main/bbs/bbsMsgDetail.do?bcd=opendata&msg_seq=4011 : 월별 접수·승차·수입액 및 분기 이용실적 집계로 공개하며 개별 OD·요금 쌍이 아니어서 미사용.",
        "대구 나드리콜 https://www.data.go.kr/data/15125690/openapi.do 및 https://nadrihome.dpfc.or.kr/bbs/board.php?bo_table=notic&wr_id=254 : 운영통계/API 제공 범위를 확인했으나 공개 운행 단위 OD·실제 요금 파일을 확보하지 못함. 서울 원본과 동등한 검증 입력으로 채택하지 않음."],
      "new_kakao_api_calls":0,"tool_sha256":file_manifest(Path(__file__))['sha256']}
    m=kr.get('overall')
    if m:
        report['conclusion']=f"한국 실제 운행 경로의 일반택시 환산 평가 {m['n']:,}건에서 ±15% 이내 {m['within_15pct_rate']:.2%}, +15% 초과 {m['additional_payment_rate']:.2%}. 실제 일반택시 결제 검증은 아니다."
        report['presentation_sentence']=report['conclusion']
    else:
        report['conclusion']="한국 발표용 주 실험은 원본 다운로드 서버 오류로 미측정이다. NYC 참고 결과를 한국 결과로 대체하지 않는다."
        report['presentation_sentence']="공개 운행 경로 기반 요금 비교 도구를 구현했으나 한국 원본 확보가 막혀 예상·실제 오차가 작다고 결론 낼 수 없다."
    reports=ROOT/'reports'; reports.mkdir(exist_ok=True)
    acquisition=PUBLIC_CACHE/'public-download-manifest.json'
    if acquisition.is_file():
        downloaded=json.loads(acquisition.read_text(encoding='utf-8'))
        downloaded['analysis_sources']={'seoul':kr.get('source'),'nyc':nyc['files'],
            'tims':report['tims'].get('monthly',{}).get('source')}
        (reports/'public-data-MANIFEST.json').write_text(json.dumps(downloaded,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (reports/'public-fares.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding='utf-8')
    (reports/'public-fares.md').write_text(public_markdown(report),encoding='utf-8')
    print(json.dumps({"korea":kr.get('overall',kr['status']),"nyc":nyc['overall']},ensure_ascii=False,indent=2))


if __name__ == "__main__":
    import sys
    if "--public" in sys.argv:
        public_main()
    else:
        main()
