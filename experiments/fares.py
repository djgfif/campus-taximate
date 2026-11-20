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
SOURCES = {
    'seoul_tariff': 'https://news.seoul.go.kr/traffic/archives/1659',
    'pocheon_tariff': 'https://www.pocheon.go.kr/www/contents.do?key=3878',
    'tims': 'https://www.data.go.kr/data/15165682/fileData.do',
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


def file_manifest(path, url=None):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4*1024*1024), b""):
            h.update(block)
    return {"path": str(Path(path).resolve()), "url": url, "bytes": Path(path).stat().st_size,
            "sha256": h.hexdigest()}


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


def prepare_public_sql(db):
    db.execute("SET threads=4")
    db.execute("""CREATE OR REPLACE MACRO krfare(km,mins,h,basekm,meters,secs,surge) AS
      round((4800+100*(ceil(greatest(0,km-basekm)*1000/meters-1e-10)+
        CASE WHEN km>basekm THEN ceil(greatest(0,mins*60-km/15*3600)/secs-1e-10) ELSE 0 END))*surge)""")
    db.execute("""CREATE OR REPLACE MACRO seoulfare(km,mins,h) AS krfare(km,mins,h,1.6,131.,30.,
      CASE WHEN h>=23 OR h<2 THEN 1.4 WHEN h>=22 OR h<4 THEN 1.2 ELSE 1. END)""")



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
        regions=json.loads((PUBLIC_CACHE/'kr-public'/'point-regions.json').read_text(encoding='utf-8'))['points']
        pocheon_ids={p['id'] for p in regions if p['h_code'].startswith('41650')}
        points=json.loads((legacy/'data'/'korea_points.json').read_text(encoding='utf-8'))
        grids=[p['grid'] for p in points if p['id'] in pocheon_ids and p['id']!=0]
        db.execute('CREATE OR REPLACE TABLE pocheon_grids(grid VARCHAR)')
        db.executemany('INSERT INTO pocheon_grids VALUES (?)',[(g,) for g in sorted(set(grids))])
        for label,condition in (('national','true'),('commute',"grid IN (SELECT grid FROM tims_commute)"),
                                ('pocheon_cached_grids',"grid IN (SELECT grid FROM pocheon_grids)")):
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
        error_query="SELECT *,krfare(km,mins,12,2.,83.,20.,1.) expected FROM tims_monthly WHERE grid IN (SELECT grid FROM tims_commute)"
        errors=PUBLIC_CACHE/'kr-public'/'tims-errors.csv'
        db.execute(f"COPY (SELECT (actual-expected)/expected FROM ({error_query}) ORDER BY grid,month) TO {sql_path(errors)} (HEADER false)")
        stats=db.execute(f"SELECT count(*),avg((actual-expected)/expected),stddev_pop((actual-expected)/expected),min((actual-expected)/expected),max((actual-expected)/expected) FROM ({error_query})").fetchone()
        results['errors']=dict(**file_manifest(errors,SOURCES['tims']),n=stats[0],mean=stats[1],stdev=stats[2],min=stats[3],max=stats[4],
            grain='grid_month_mean',scope='5~25km commute grids, Pocheon daytime formula, one equally weighted residual per grid-month',
            percentiles=sql_metrics(db,error_query)['signed_relative_percentiles'],
            spread_rule='scale=0: no error; otherwise mean + scale*(residual-mean); 1.0/1.5/2.0',
            limitation='TIMS 잔차는 격자-월 집계 평균이므로 개별 운행 오차 분산을 과소추정한다. 통학권에는 다른 요금 지역도 섞인다.')
        results.update(monthly_status='COMPLETED',monthly=monthly)
    results['point_grid_selection_sources']=[file_manifest(PUBLIC_CACHE/'kr-public'/'point-regions.json'),
        file_manifest(legacy/'data'/'korea_points.json')]
    results["limitations"]=["Monthly raw archive is analyzed separately from annual caches; annual values do not reconstruct grid-month errors.",
      "Nonlinear tariff at an aggregate mean differs from mean trip fares; national regions use different tariffs.",
      "No low-speed clock, night surcharge, border or toll detail; this is a tariff proxy comparison, not validation of its exact formula.",
      "Commute grids describe pickups 5-25 km from Daejin, not confirmed Daejin student trips."]
    return results


def public_markdown(report):
    lines=['# C5 TIMS 2025 격자·월 요금 비교','',report['conclusion'],'',
           '실제 평균 운행거리·시간에 공식 근사 요금을 적용하고 관측 평균 요금과 비교한다. 일반택시 개별 영수증 검증이 아니다.',
           '', '| 범위 | 요금표 | 격자·월 수 | MAE(원) | MAPE | ±15% |', '|---|---|---:|---:|---:|---:|']
    for label in ('national','commute','pocheon_cached_grids'):
        for tariff,m in report['tims']['monthly'][label]['comparisons'].items():
            lines.append(f"| {label} | {tariff} | {m['n']:,} | {m['mae']:.2f} | {m['mape']:.2%} | {m['within_15pct_rate']:.2%} |")
    e=report['tims']['errors']
    lines+=['','## C6/C7 오차 입력','',f"{e['n']:,}개 통학권 격자·월 평균 잔차. 평균 {e['mean']:.4f}, 표준편차 {e['stdev']:.4f}, 최솟값 {e['min']:.4f}, 최댓값 {e['max']:.4f}.",
            '잔차=(관측 평균 요금−평균 거리·시간의 포천 낮 요금)/포천 낮 요금. 격자·월을 동일 가중한다. 배수는 평균을 유지한 잔차 편차에 1.0/1.5/2.0을 적용한다.',
            '포천 하위 비교는 행정구역이 확인된 기존 캐시 목적지의 격자이며 포천 전체 격자가 아니다.',
            '', '## 한계','',*['- '+x for x in report['limitations']],
            '', '출처: 한국교통안전공단 TIMS, 교통안전공단 택시운행정보 격자별 영업통계(2025).',
            *['- '+k+': '+v for k,v in SOURCES.items()], '']
    return '\n'.join(lines)


def public_main():
    parser=argparse.ArgumentParser(description='C5 TIMS 2025 only')
    parser.add_argument('--public',action='store_true')
    parser.add_argument('--download',action='store_true',help='Acquire missing Korean public files')
    parser.add_argument('--legacy-realdata',type=Path,default=ROOT.parent.parent/'taximate-matching-sim'/'realdata')
    args=parser.parse_args()
    if args.download:
        import subprocess
        subprocess.run(['node',str(ROOT/'acquire-public.mjs'),'all'],check=True)
    import duckdb
    db=duckdb.connect(); prepare_public_sql(db)
    tims=tims_public(db,args.legacy_realdata)
    if tims['monthly_status']!='COMPLETED': raise ValueError('TIMS monthly archive required')
    report=dict(schema_version=3,generated_at=datetime.now(timezone.utc).isoformat(),tims=tims,sources=SOURCES,
        definitions=dict(signed_relative='(actual-expected)/expected',mape='mean(abs(actual-expected)/actual)',
            expected='Pocheon: 4800 + distance ticks (2km,83m) + slow-time ticks (20sec); Seoul: 1.6km,131m,30sec; daytime',
            grain='grid-month, formula evaluated at mean km/minutes; not individual trips'),
        limitations=['실제 합승 성과 미검증이며 개별 일반택시 결제 검증이 아니다.',
            'TIMS 잔차는 집계 평균이라 개별 오차 분산을 과소추정한다. 평균을 유지한 1.0/1.5/2.0배 분산 민감도를 병기한다.',
            '비선형 요금표를 평균 거리·시간에 적용한 값은 개별 요금 평균과 다르다. 지역 요금제 혼합·심야·경계 할증·통행료와 저속 clock을 재구성하지 못한다.',
            '통학권은 대진대 5~25km 격자 대리 범위이며 실제 학생 통학 자료가 아니다. 연간 캐시는 하위 격자 선택 및 별도 보조 비교에만 쓴다.'],
        new_kakao_api_calls=0,tool_sha256=file_manifest(Path(__file__))['sha256'])
    m=tims['monthly']['commute']['comparisons']['pocheon']
    report['conclusion']=f"TIMS 통학권 {m['n']:,}개 격자·월에서 포천 낮 요금 근사와 관측 평균 요금의 MAPE {m['mape']:.2%}, ±15% 이내 {m['within_15pct_rate']:.2%}. 집계 비교이며 개별 운행 검증이 아니다."
    report['presentation_sentence']=report['conclusion']
    reports=ROOT/'reports'; reports.mkdir(exist_ok=True)
    files=[tims['monthly']['source'],file_manifest(PUBLIC_CACHE/'tims-monthly-safe.csv'),tims['errors'],
           *[tims[k]['source'] for k in ('national','commute')],*tims['point_grid_selection_sources']]
    living=PUBLIC_CACHE/'kr-public'/'living-move-summary.json'
    if living.is_file(): files+=json.loads(living.read_text(encoding='utf-8'))['files']
    (reports/'public-data-MANIFEST.json').write_text(json.dumps(dict(generated_at=report['generated_at'],files=files,tool_sha256={p.name:file_manifest(p)['sha256'] for p in (Path(__file__),ROOT/'livingmove.py',ROOT/'acquire_public.py',ROOT/'acquire-public.mjs')}),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (reports/'public-fares.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (reports/'public-fares.md').write_text(public_markdown(report),encoding='utf-8')
    print(report['conclusion'])


if __name__ == '__main__':
    import sys
    if '--public' in sys.argv: public_main()
    else: main()
