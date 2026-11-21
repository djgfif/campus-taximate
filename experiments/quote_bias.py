"""C16 앱 견적(카카오 예상 요금 x (1+quote-bias))이 실제 요금보다 평균 몇 % 다른지 공개자료로 잇는다.

앱은 카카오 길찾기 예상 요금에 quote-bias(6.57%)를 곱해 견적을 낸다. 그런데 6.57%는 C5에서
'요금표 공식'과 TIMS 실제 평균 요금을 비교해 나온 값이고, 카카오 견적과 실제 요금을 비교한 적은 없다.
같은 운행의 카카오 견적과 실제 요금이 함께 공개된 자료는 없으므로, 요금표 공식을 다리로 쓴다.

  카카오 / 공식  : 이미 받아 둔 카카오 미래경로 견적 400건(kakao-calibration.csv) 중 대진대 출발 200건.
                   카카오가 준 거리·시간을 그대로 포천 낮 요금표 공식에 넣어 같은 운행끼리 비교한다.
                   포천시 시계외 할증(관외 20%)은 로컬 OSRM 경로 중 포천시 경계 밖 길이 비율만큼에 붙인다.
  실제 / 공식    : TIMS 2025 통학권 격자·월 평균(C5와 같은 자료·공식)을 평균 운행거리대별로 나눈다.
  앱 견적 / 실제 = (1 + bias) x (카카오/공식) / (실제/공식), 거리대마다 따로 계산한다.

거리대 비중은 C12(지금 서비스, 대진대) 모의 요청의 혼자 거리 분포를 쓴다. 외부 API 호출은 없다(OSRM은 로컬).
"""
import csv
import json
import random
import statistics
import sys
from pathlib import Path

import duckdb

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from fares import korea_tariff  # noqa: E402

CACHE = HERE / '.cache'
REPORTS = HERE / 'reports'
KAKAO = CACHE / 'osrm' / 'kakao-calibration.csv'
TIMS = CACHE / 'tims-monthly-safe.csv'
COMMUTE = HERE.parent.parent / 'taximate-matching-sim' / 'realdata' / 'data' / 'tims_commute_grids.csv'
C12_CASES = CACHE / 'c12' / 'cases.tsv'
BIAS = 0.0657  # C16 이전 앱 기본값(app.fare.quote-bias). 이 결과로 앱 기본값은 0이 됐다.
BIAS_NOW = 0.0  # 지금 앱 기본값: 카카오 견적 그대로
BANDS = ('0-5', '5-15', '15-30', '30+')
POCHEON = '41650'
SGIS = CACHE / 'kr-public' / 'sgis-admin-2025.zip'
OSRM = 'http://127.0.0.1:5000'
OUTSIDE_CACHE = CACHE / 'c16' / 'outside-fraction.json'
# 포천시 공공요금 현황(2026.1.1.) '시계 외 할증 (관외) 20%'. 적용 방식은 경계를 벗어난 구간부터(서울시 택시요금 안내와 같은 방식).
BORDER_RATE = 0.20
SEED = 20261121
BOOT = 4000


def band(km):
    return '0-5' if km < 5 else '5-15' if km < 15 else '15-30' if km < 30 else '30+'


def chain(kakao_over_formula, actual_over_formula, bias=BIAS):
    """비율(1+r)끼리 이어 카카오/실제, 앱 견적/실제의 상대 차이를 돌려준다."""
    kakao_vs_actual = (1 + kakao_over_formula) / (1 + actual_over_formula) - 1
    return kakao_vs_actual, (1 + bias) * (1 + kakao_vs_actual) - 1


def quantile(values, q):
    s = sorted(values)
    if not s:
        return None
    pos = (len(s) - 1) * q
    lo, hi = int(pos), min(int(pos) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)


def with_border_surcharge(formula, outside_fraction, rate=BORDER_RATE, base=4800):
    """시계외 할증: 포천시 경계를 벗어난 구간의 요금에만 rate를 더한다(경계 지점부터 적용).

    거리·시간 틱이 경로를 따라 고르게 쌓인다고 보고, 기본요금 위의 증가분 중 경계 밖 비율만큼에 할증을 붙인다.
    """
    return formula + rate * max(0., formula - base) * outside_fraction


def pocheon_polygon(archive=SGIS):
    """SGIS 2025Q2 시군구 경계에서 포천시 다각형(EPSG:5179 계열 UTM-K)을 읽는다."""
    import io
    import zipfile
    import shapefile
    import shapely
    from shapely.geometry import shape
    with zipfile.ZipFile(archive) as z:
        stem = next(n[:-4] for n in z.namelist() if '/bnd_sigungu_' in n and n.endswith('.shp'))
        reader = shapefile.Reader(shp=io.BytesIO(z.read(stem + '.shp')), dbf=io.BytesIO(z.read(stem + '.dbf')), encoding='utf8')
        found = [shape(r.shape.__geo_interface__) for r in reader.iterShapeRecords() if r.record.as_dict()['SIGUNGU_NM'] == '포천시']
    if len(found) != 1:
        raise ValueError(f'포천시 경계가 {len(found)}개다')
    geom = found[0]
    return geom if geom.is_valid else shapely.make_valid(geom)


def outside_fractions(rows, cache=OUTSIDE_CACHE, osrm=OSRM):
    """각 견적 OD의 OSRM 경로 중 포천시 밖 길이 비율. 로컬 OSRM만 쓰고 결과는 캐시에 남긴다."""
    import urllib.request
    from pyproj import Transformer
    from shapely.geometry import LineString
    done = json.loads(cache.read_text(encoding='utf-8')) if cache.exists() else {}
    todo = [r for r in rows if r['pair'] not in done]
    if todo:
        poly = pocheon_polygon()
        to_utm = Transformer.from_crs(4326, 5179, always_xy=True)
        for r in todo:
            url = f"{osrm}/route/v1/driving/{r['o'][0]},{r['o'][1]};{r['d'][0]},{r['d'][1]}?overview=full&geometries=geojson"
            with urllib.request.urlopen(url, timeout=30) as resp:
                coords = json.load(resp)['routes'][0]['geometry']['coordinates']
            line = LineString([to_utm.transform(x, y) for x, y in coords])
            done[r['pair']] = dict(km=line.length / 1000, outside=line.difference(poly).length / line.length if line.length else 0.)
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding='utf-8')
    return done


def kakao_rows(path=KAKAO, surcharge=True):
    out = []
    with open(path, encoding='utf-8', newline='') as f:
        for x in csv.DictReader(f):
            if x['kind'] != 'hub' or not x['kakao_taxi'] or not x['kakao_km']:
                continue
            km, sec, fare = float(x['kakao_km']), float(x['kakao_seconds']), float(x['kakao_taxi'])
            formula = korea_tariff(km, sec / 60, int(x['hour']), 'pocheon')
            out.append(dict(pair=x['pair_id'], km=km, band=band(km), hour=int(x['hour']),
                            o=(float(x['origin_lon']), float(x['origin_lat'])), d=(float(x['destination_lon']), float(x['destination_lat'])),
                            inside=x['destination_region'].startswith(POCHEON),
                            kakao=fare, formula=formula, ratio=fare / formula - 1))
    if surcharge:
        frac = outside_fractions(out)
        for r in out:
            r['outside'] = frac[r['pair']]['outside']
            r['formula_border'] = with_border_surcharge(r['formula'], r['outside'])
            r['ratio_border'] = r['kakao'] / r['formula_border'] - 1
    return out


def summarize(values, rng):
    if not values:
        return dict(n=0)
    boots = [statistics.median(rng.choices(values, k=len(values))) for _ in range(BOOT)]
    return dict(n=len(values), median=statistics.median(values), mean=statistics.mean(values),
                p25=quantile(values, .25), p75=quantile(values, .75),
                median_ci95=[quantile(boots, .025), quantile(boots, .975)])


def tims_by_band(tims=TIMS, commute=COMMUTE):
    db = duckdb.connect()
    db.execute("""CREATE MACRO krfare(km,mins) AS
      round(4800+100*(ceil(greatest(0,km-2.)*1000/83.-1e-10)+
        CASE WHEN km>2. THEN ceil(greatest(0,mins*60-km/15*3600)/20.-1e-10) ELSE 0 END))""")
    p = lambda path: "'" + str(Path(path).resolve()).replace('\\', '/').replace("'", "''") + "'"
    db.execute(f"CREATE TABLE c AS SELECT grid FROM read_csv_auto({p(commute)})")
    db.execute(f"""CREATE TABLE t AS SELECT *, distance_m/trips/1000 km, time_s/trips/60 mins,
      fare_won/trips actual FROM read_csv_auto({p(tims)})
      WHERE trips>0 AND fare_won>0 AND distance_m>0 AND time_s>0 AND grid IN (SELECT grid FROM c)""")
    rows = db.execute("""SELECT CASE WHEN km<5 THEN '0-5' WHEN km<15 THEN '5-15' WHEN km<30 THEN '15-30' ELSE '30+' END b,
      count(*), sum(trips), avg(km), median(actual/krfare(km,mins)-1), avg(actual/krfare(km,mins)-1),
      sum(fare_won)/sum(trips*krfare(km,mins))-1
      FROM t GROUP BY 1""").fetchall()
    out = {b: dict(grid_months=n, trips=int(trips), mean_km=km, median=med, mean=mean, trip_weighted=tw)
           for b, n, trips, km, med, mean, tw in rows}
    total = db.execute("SELECT count(*), avg(actual/krfare(km,mins)-1), sum(fare_won)/sum(trips*krfare(km,mins))-1 FROM t").fetchone()
    return out, dict(grid_months=total[0], mean=total[1], trip_weighted=total[2])


def service_weights(path=C12_CASES):
    counts = {b: 0 for b in BANDS}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('R\t'):
                counts[band(float(line.split('\t')[5]))] += 1
    n = sum(counts.values())
    return {b: counts[b] / n for b in BANDS}, n


def analyze():
    rng = random.Random(SEED)
    kakao = kakao_rows()
    tims, tims_total = tims_by_band()
    weights, requests = service_weights()
    # 본 결과는 시계외 할증을 넣은 공식(ratio_border). 할증을 빼면(ratio) 이전 C16 결과로 민감도를 남긴다.
    per_band, weighted = bridge(kakao, 'ratio_border', tims, weights, rng)
    no_border = dict(zip(('per_band', 'service_weighted'), bridge(kakao, 'ratio', tims, weights, rng)))
    split = {name: dict(**summarize([r['ratio'] for r in kakao if r['inside'] == flag], rng),
                        border=summarize([r['ratio_border'] for r in kakao if r['inside'] == flag], rng),
                        outside_fraction_median=statistics.median(r['outside'] for r in kakao if r['inside'] == flag))
             for name, flag in (('destination_in_pocheon', True), ('destination_outside_pocheon', False))}
    return dict(
        experiment='C16 quote bias bridge', seed=SEED, bootstrap=BOOT, bias_before=BIAS, bias_now=BIAS_NOW,
        border_rate=BORDER_RATE, border_rule='포천시 경계를 벗어난 구간(OSRM 경로 길이 비율)의 기본요금 초과분에만 20%',
        inputs=dict(kakao=str(KAKAO), tims=str(TIMS), commute_grids=str(COMMUTE), c12_cases=str(C12_CASES),
                    boundary=str(SGIS), osrm=OSRM, outside_cache=str(OUTSIDE_CACHE)),
        kakao_hub_quotes=len(kakao), kakao_all=summarize([r['ratio'] for r in kakao], rng),
        kakao_all_border=summarize([r['ratio_border'] for r in kakao], rng), kakao_split=split,
        tims_commute_total=tims_total, per_band=per_band, service_requests=requests,
        service_weighted=weighted, no_border=no_border,
        limitations=[
            '카카오 견적과 실제 요금을 같은 운행으로 비교한 것이 아니다. 요금표 공식을 사이에 둔 간접 비교다.',
            '시계외 할증 20%의 적용 방식(경계부터)은 포천시 요금표에 적혀 있지 않아 서울시 택시요금 안내와 같은 방식으로 두었다. 경계 밖 비율은 카카오가 아니라 OSRM 경로로 잰 값이다.',
            '실제/공식(TIMS)은 할증 없는 공식 기준이라, 서비스 운행의 실제 요금은 할증 넣은 공식 × (1+실제/공식)으로 가정한다.',
            'TIMS는 격자·월 평균이라 30km 넘는 운행은 평균 거리가 그 대에 드는 격자·월로만 대신한다(표본이 적다).',
            '공식에는 통행료와 실제 저속 시간 계산이 없다. 카카오 견적은 2026-11-23 낮 시간 미래경로 예측이며 실제 미터 결제가 아니다.',
            'TIMS 통학권 격자에는 포천 밖(다른 요금 지역) 승차도 섞여 있다. C5와 같은 범위를 그대로 썼다.',
        ])


def bridge(kakao, key, tims, weights, rng):
    """카카오/공식(key)과 TIMS 실제/공식을 거리대마다 잇고 서비스 거리 분포로 가중한다."""
    per_band = {}
    for b in BANDS:
        values = [r[key] for r in kakao if r['band'] == b]
        k = summarize(values, rng)
        t = tims.get(b, {})
        entry = dict(kakao_over_formula=k, actual_over_formula=t, service_share=weights[b])
        if k['n'] and t:
            # 실제/공식은 격자·월 중앙값과 운행 가중 평균 두 가지로 범위를 낸다.
            lo_t, hi_t = sorted([t['median'], t['trip_weighted']])
            kv_hi, app_hi = chain(k['median'], lo_t)
            kv_lo, app_lo = chain(k['median'], hi_t)
            entry.update(kakao_vs_actual=[kv_lo, kv_hi], app_vs_actual=[app_lo, app_hi])
        per_band[b] = entry
    usable = [b for b in BANDS if 'app_vs_actual' in per_band[b]]
    share = sum(weights[b] for b in usable)
    weighted = {key: [sum(weights[b] * per_band[b][key][i] for b in usable) / share for i in (0, 1)]
                for key in ('kakao_vs_actual', 'app_vs_actual')}
    # 거리대마다 카카오 표본 수가 달라(5~15km는 8건뿐) 표본 재추출로 가중 평균의 95% 구간을 낸다.
    # 실제/공식 두 값(격자·월 중앙값, 운행 가중)을 모두 넣어 한 구간으로 합친다.
    by_band = {b: [r[key] for r in kakao if r['band'] == b] for b in usable}
    draws, draws_now = [], []
    for _ in range(BOOT):
        medians = {b: statistics.median(rng.choices(by_band[b], k=len(by_band[b]))) for b in usable}
        for variant in ('median', 'trip_weighted'):
            pairs = [(weights[b], chain(medians[b], tims[b][variant])) for b in usable]
            draws.append(sum(w * c[1] for w, c in pairs) / share)
            draws_now.append(sum(w * c[0] for w, c in pairs) / share)  # 보정 0 = 카카오 견적 그대로
    weighted['app_vs_actual_ci95'] = [quantile(draws, .025), quantile(draws, .975)]
    weighted['kakao_vs_actual_ci95'] = [quantile(draws_now, .025), quantile(draws_now, .975)]
    return per_band, dict(bands=usable, share_covered=share, **weighted)


def pct(x, digits=1):
    return '—' if x is None else f'{x * 100:+.{digits}f}%'


def markdown(r):
    w = r['service_weighted']
    lines = ['# C16 앱 견적과 실제 요금의 차이 (카카오·요금표 공식·TIMS 연결)', '',
             f"앱 견적 = 카카오 예상 요금 × (1 + quote-bias). 이 실험 전 기본값은 {r['bias_before']:.2%}였다. 6.57%는 '요금표 공식 대비 실제'에서 나온 값이라, "
             '카카오 견적에 곱해도 되는지는 확인된 적이 없었다. 같은 운행의 카카오 견적과 실제 요금이 함께 공개된 자료가 없어 요금표 공식을 다리로 잇는다.', '',
             '## 결론', '',
             f"- 대진대 출발 카카오 견적 {r['kakao_hub_quotes']}건은 할증 없는 요금표 공식보다 중앙값 {pct(r['kakao_all']['median'])} 높지만, "
             f"포천시 시계외 할증(관외 20%, 경계 밖 구간)을 공식에 넣으면 {pct(r['kakao_all_border']['median'])}로 줄고 거리대마다 고르게 맞는다. 카카오는 시계외 할증을 실제 규정대로 넣고 있다.",
             f"- 지금 서비스 거리 분포로 가중하면 **카카오 견적은 실제 평균과 거의 같다**: {pct(w['kakao_vs_actual'][0])} ~ {pct(w['kakao_vs_actual'][1])}"
             f"(95% 구간 {pct(w['kakao_vs_actual_ci95'][0])} ~ {pct(w['kakao_vs_actual_ci95'][1])}).",
             f"- 예전 앱 견적(×{1 + r['bias_before']:.4f})은 실제 평균보다 {pct(w['app_vs_actual'][0])} ~ {pct(w['app_vs_actual'][1])} 높았다"
             f"(95% 구간 {pct(w['app_vs_actual_ci95'][0])} ~ {pct(w['app_vs_actual_ci95'][1])}). 이용자가 실제보다 더 내고 차액이 적립금에 쌓이는 쪽이었다.",
             f"- **이 결과로 앱 보정을 {r['bias_now']:.0%}로 바꿨다**(카카오 견적 그대로). 95% 구간이 0을 포함해 더 깎을 근거도, 더할 근거도 없다. "
             '확정 분담금 구조라 남는 오차는 적립금이 맡고, 운영 중 실제 요금 입력이 쌓이면 다시 맞춘다.',
             '- 모의실험(C11~C15)의 견적은 카카오가 아니라 OSRM 경로 + 할증 없는 요금표 공식이고, 그 견적이 실제보다 6.57% 낮다는 것은 C5로 확인됐으므로 실험에는 6.57%를 그대로 둔다.',
             f"- 시계외 할증을 빼고 계산하면(이전 C16) 카카오 견적이 실제보다 {pct(r['no_border']['service_weighted']['kakao_vs_actual'][0])} ~ "
             f"{pct(r['no_border']['service_weighted']['kakao_vs_actual'][1])} 높게 나온다. 그 차이는 대부분 할증을 공식에 넣지 않아 생긴 것이었다.", '',
             '## 거리대별', '',
             '| 거리대 | 지금 서비스 비중 | 카카오/할증 넣은 공식 (건수, 중앙값, 95% CI) | 실제/공식 (TIMS 격자·월 수, 중앙값 · 운행가중) | 카카오/실제 = 지금 앱 견적/실제 | 예전 앱 견적(×1.0657)/실제 |',
             '|---|---:|---|---|---|---|']
    for b in BANDS:
        e = r['per_band'][b]; k = e['kakao_over_formula']; t = e['actual_over_formula']
        kk = f"{k['n']}건, {pct(k.get('median'))} [{pct(k['median_ci95'][0])}, {pct(k['median_ci95'][1])}]" if k['n'] else '0건'
        tt = f"{t['grid_months']:,}, {pct(t['median'])} · {pct(t['trip_weighted'])}" if t else '—'
        kv = f"{pct(e['kakao_vs_actual'][0])} ~ {pct(e['kakao_vs_actual'][1])}" if 'kakao_vs_actual' in e else '—'
        av = f"{pct(e['app_vs_actual'][0])} ~ {pct(e['app_vs_actual'][1])}" if 'app_vs_actual' in e else '—'
        lines.append(f"| {b}km | {e['service_share']:.0%} | {kk} | {tt} | {kv} | {av} |")
    s = r['kakao_split']
    lines += ['', '## 포천 안·밖 목적지와 시계외 할증', '',
              '| 목적지 | 건수 | 경로 중 포천 밖 비율(중앙값) | 카카오/할증 없는 공식 | 카카오/할증 넣은 공식 |', '|---|---:|---:|---:|---:|']
    for name, label in (('destination_in_pocheon', '포천 안'), ('destination_outside_pocheon', '포천 밖')):
        x = s[name]
        lines.append(f"| {label} | {x['n']} | {x['outside_fraction_median']:.0%} | {pct(x['median'])} | {pct(x['border']['median'])} |")
    lines += ['', '- 할증을 빼면 포천 밖 운행만 +15% 가까이 높아 보이지만, 경계 밖 구간에 20%를 붙이면 포천 안 운행과 같은 수준(+2~5%)이 된다.',
              '- 할증 근거: 포천시 공공요금 현황(2026.1.1.)의 택시 "시계 외 할증 (관외) 20%". 경계를 벗어난 지점부터 붙는 방식은 서울시 택시요금 안내(시 경계를 벗어나는 시계외 구간에 20%)와 같게 두었다.', '',
              '## 읽는 법', '',
              '- 카카오/할증 넣은 공식: 카카오가 준 거리·시간을 C5와 같은 포천 낮 요금표 공식에 넣고, OSRM 경로로 잰 포천 밖 비율만큼의 기본요금 초과분에 20%를 더한 값 대비 카카오 예상 요금.',
              '- 실제/공식: TIMS 2025 통학권 격자·월의 평균 거리·시간을 할증 없는 공식에 넣은 값 대비 실제 평균 요금(C5와 같은 범위). 서비스 운행의 실제 요금은 할증 넣은 공식 × (1+실제/공식)으로 본다.',
              '- 카카오/실제 = (1+카카오/할증 넣은 공식)/(1+실제/공식) − 1. 실제/공식은 격자·월 중앙값과 운행 가중 평균 두 값으로 범위를 냈다.',
              f"- 예전 앱 견적/실제 = (1+{r['bias_before']})×(1+카카오/실제) − 1. 지금 앱(보정 {r['bias_now']:.0%})은 카카오/실제와 같다. 일행 확정 금액에는 여기에 여유분 1%가 더 붙는다.",
              f"- 거리대 비중: C12 지금 서비스 모의 요청 {r['service_requests']:,}건의 혼자 거리.", '',
              '## 한계', ''] + [f'- {x}' for x in r['limitations']]
    lines += ['', '출처: 카카오모빌리티 길찾기 미래경로 API(C7 보정용으로 이미 받은 400건), 한국교통안전공단 TIMS 2025 택시운행정보 격자별 영업통계(data.go.kr 15165682), '
              '포천시 공공요금 현황(https://www.pocheon.go.kr/www/contents.do?key=3878), 서울시 택시요금 안내(https://news.seoul.go.kr/traffic/archives/1659), '
              '국가데이터처 SGIS 2025년 2분기 시군구 경계, OpenStreetMap 기반 로컬 OSRM.']
    return '\n'.join(lines) + '\n'


def main():
    report = analyze()
    REPORTS.mkdir(exist_ok=True)
    (REPORTS / 'c16-quote-bias.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (REPORTS / 'c16-quote-bias.md').write_text(markdown(report), encoding='utf-8')
    print(markdown(report))


if __name__ == '__main__':
    main()
