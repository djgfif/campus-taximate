"""C16 앱 견적(카카오 예상 요금 x (1+quote-bias))이 실제 요금보다 평균 몇 % 다른지 공개자료로 잇는다.

앱은 카카오 길찾기 예상 요금에 quote-bias(6.57%)를 곱해 견적을 낸다. 그런데 6.57%는 C5에서
'요금표 공식'과 TIMS 실제 평균 요금을 비교해 나온 값이고, 카카오 견적과 실제 요금을 비교한 적은 없다.
같은 운행의 카카오 견적과 실제 요금이 함께 공개된 자료는 없으므로, 요금표 공식을 다리로 쓴다.

  카카오 / 공식  : C7 보정 때 받은 카카오 미래경로 견적 400건(kakao-calibration.csv) 중 포천 시내 허브(C7 hub) 출발 200건과,
                   서비스 비중이 큰 5~15km를 메우려고 C16에서 받은 대진대 정문 출발 72건(sample → query, 응답 원문 저장).
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
# 5~15km 보강: 서비스(C12) 비중 47%인데 기존 표본은 8건뿐이었다. 대진대 정문 출발, C12 모의 요청의 목적지에서
# 5~8 / 8~11 / 11~15km를 같은 수로 미리 뽑아 견적을 보기 전에 파일로 고정한 뒤 카카오 미래경로를 부른다.
CAMPUS = (127.1561, 37.8707)
CAMPUS_DIR = CACHE / 'c16'
CAMPUS_SAMPLE = CAMPUS_DIR / 'campus-sample.csv'
CAMPUS_KAKAO = CAMPUS_DIR / 'campus-kakao.csv'
CAMPUS_RAW = CAMPUS_DIR / 'kakao-raw'
SUB_BANDS = ((5, 8), (8, 11), (11, 15))
PER_SUB_BAND = 24
SAMPLE_SEED = 20261122


def band(km):
    return '0-5' if km < 5 else '5-15' if km < 15 else '15-30' if km < 30 else '30+'


def sample_campus(cases=C12_CASES, kakao=KAKAO, out=CAMPUS_SAMPLE, per=PER_SUB_BAND, seed=SAMPLE_SEED):
    """C12 목적지 중 대진대 정문에서 5~15km인 곳을 세 소구간에서 같은 수로 뽑는다. 이미 있으면 덮지 않는다."""
    import kakao_calibration as kc
    if out.exists():
        raise ValueError(f'{out} 이미 있음: 표본은 한 번만 뽑는다(결과를 본 뒤 다시 뽑지 않기 위해)')
    points = {}
    with open(cases, encoding='utf-8') as f:
        for line in f:
            if line.startswith('R\t'):
                p = line.rstrip('\n').split('\t')
                points.setdefault(p[2], dict(point=p[2], lat=float(p[3]), lon=float(p[4]), osrm_km=float(p[5]), osrm_seconds=float(p[6])))
    with open(kakao, encoding='utf-8', newline='') as f:
        hours = [int(x['hour']) for x in csv.DictReader(f) if x['kind'] == 'hub']
    rng = random.Random(seed)
    day = kc.weekday()
    rows = []
    for lo, hi in SUB_BANDS:
        pool = sorted((p for p in points.values() if lo <= p['osrm_km'] < hi), key=lambda p: p['point'])
        for p in rng.sample(pool, min(per, len(pool))):
            hour = rng.choice(hours)
            rows.append(dict(pair_id=f"campus:{p['point']}", sub_band=f'{lo}-{hi}', origin_lon=CAMPUS[0], origin_lat=CAMPUS[1],
                             destination_lon=p['lon'], destination_lat=p['lat'], hour=hour,
                             departure_time=f'{day:%Y%m%d}{hour:02}00', osrm_km=p['osrm_km'], osrm_seconds=p['osrm_seconds']))
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    return rows


def query_campus(sample=CAMPUS_SAMPLE, out=CAMPUS_KAKAO, raw=CAMPUS_RAW, env=HERE.parent / '.env', pause=.2):
    """카카오 미래경로(상세 경로 포함)를 부르고 응답 원문을 저장한다. 이미 받은 것은 건너뛴다(재개 가능)."""
    import time
    import urllib.error
    import urllib.parse
    import urllib.request
    import kakao_calibration as kc
    key = kc.load_key(env)
    with open(sample, encoding='utf-8', newline='') as f:
        rows = list(csv.DictReader(f))
    raw.mkdir(parents=True, exist_ok=True)
    for r in rows:
        path = raw / (r['pair_id'].replace(':', '_') + '.json')
        if path.exists():
            continue
        params = dict(origin=f"{r['origin_lon']},{r['origin_lat']}", destination=f"{r['destination_lon']},{r['destination_lat']}",
                      departure_time=r['departure_time'], priority='RECOMMEND', summary='false')
        req = urllib.request.Request(kc.API + '?' + urllib.parse.urlencode(params), headers={'Authorization': 'KakaoAK ' + key})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = json.load(resp)
        except urllib.error.HTTPError as e:
            raise RuntimeError(f'Kakao HTTP {e.code}; 받은 응답은 남아 있음, 다시 실행하면 이어서 받음') from None
        except Exception:
            raise RuntimeError('Kakao 요청 실패; 받은 응답은 남아 있음, 다시 실행하면 이어서 받음') from None
        path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        time.sleep(pause)
    out_rows = []
    for r in rows:
        data = json.loads((raw / (r['pair_id'].replace(':', '_') + '.json')).read_text(encoding='utf-8'))
        s = kc.parse_response(data)
        out_rows.append(dict(r, **s))
    with open(out, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(out_rows[0])); w.writeheader(); w.writerows(out_rows)
    return out_rows


def kakao_route_outside(pair_id, poly, raw=CAMPUS_RAW):
    """저장한 카카오 응답의 상세 경로(roads.vertexes)로 포천시 밖 길이 비율을 잰다."""
    from pyproj import Transformer
    from shapely.geometry import LineString
    data = json.loads((raw / (pair_id.replace(':', '_') + '.json')).read_text(encoding='utf-8'))
    pts = []
    for section in data['routes'][0].get('sections', []):
        for road in section.get('roads', []):
            v = road['vertexes']
            pts += list(zip(v[0::2], v[1::2]))
    if len(pts) < 2:
        return None
    to_utm = Transformer.from_crs(4326, 5179, always_xy=True)
    line = LineString([to_utm.transform(x, y) for x, y in pts])
    return line.difference(poly).length / line.length if line.length else 0.


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


def kakao_rows(path=KAKAO, campus=CAMPUS_KAKAO, surcharge=True):
    """견적 행. source='hub'는 C7 보정 때 받은 포천 시내 허브(C7 hub) 출발 200건, 'campus'는 C16 보강 대진대 정문 출발 5~15km."""
    out = []
    with open(path, encoding='utf-8', newline='') as f:
        for x in csv.DictReader(f):
            if x['kind'] != 'hub' or not x['kakao_taxi'] or not x['kakao_km']:
                continue
            out.append(dict(pair=x['pair_id'], source='hub', km=float(x['kakao_km']), sec=float(x['kakao_seconds']), kakao=float(x['kakao_taxi']),
                            hour=int(x['hour']), o=(float(x['origin_lon']), float(x['origin_lat'])),
                            d=(float(x['destination_lon']), float(x['destination_lat'])), inside=x['destination_region'].startswith(POCHEON)))
    if campus and Path(campus).exists():
        with open(campus, encoding='utf-8', newline='') as f:
            for x in csv.DictReader(f):
                out.append(dict(pair=x['pair_id'], source='campus', sub_band=x['sub_band'], km=float(x['kakao_km']), sec=float(x['kakao_seconds']),
                                kakao=float(x['kakao_taxi']), hour=int(x['hour']), o=(float(x['origin_lon']), float(x['origin_lat'])),
                                d=(float(x['destination_lon']), float(x['destination_lat'])), inside=None))
    poly = pocheon_polygon() if surcharge else None
    if poly is not None:
        from pyproj import Transformer
        from shapely.geometry import Point
        to_utm = Transformer.from_crs(4326, 5179, always_xy=True)
    for r in out:
        r['band'] = band(r['km'])
        r['formula'] = korea_tariff(r['km'], r['sec'] / 60, r['hour'], 'pocheon')
        r['ratio'] = r['kakao'] / r['formula'] - 1
        if r['inside'] is None and poly is not None:
            r['inside'] = poly.contains(Point(*to_utm.transform(*r['d'])))
    if surcharge:
        frac = outside_fractions(out)
        for r in out:
            r['outside_osrm'] = frac[r['pair']]['outside']
            # 보강 표본은 카카오가 준 상세 경로로 경계 밖 비율을 잰다(카카오 견적과 같은 경로). 기존 표본은 경로가 없어 OSRM.
            r['outside_kakao'] = kakao_route_outside(r['pair'], poly) if r['source'] == 'campus' else None
            r['outside'] = r['outside_kakao'] if r['outside_kakao'] is not None else r['outside_osrm']
            r['formula_border'] = with_border_surcharge(r['formula'], r['outside'])
            r['ratio_border'] = r['kakao'] / r['formula_border'] - 1
            # 다른 가정: 목적지가 포천 밖이면 요금 전체에 20%(경계와 상관없이 전 구간)
            r['ratio_whole'] = r['kakao'] / (r['formula'] * (1 + BORDER_RATE) if not r['inside'] else r['formula']) - 1
    return out


def _sha(path):
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest() if Path(path).exists() else None


def statistics_counter(values):
    from collections import Counter
    return Counter(values)


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
    # 본 결과는 기존 200건 + 보강 72건, 시계외 할증을 경계 밖 구간에만 넣은 공식(ratio_border).
    per_band, weighted = bridge(kakao, 'ratio_border', tims, weights, rng)
    scenarios = {}
    for name, key in (('none', 'ratio'), ('border', 'ratio_border'), ('whole', 'ratio_whole')):
        pb, sw = bridge(kakao, key, tims, weights, rng)
        scenarios[name] = dict(service_weighted=sw, all=summarize([r[key] for r in kakao], rng),
                               inside=summarize([r[key] for r in kakao if r['inside']], rng),
                               outside=summarize([r[key] for r in kakao if not r['inside']], rng))
    no_border = dict(zip(('per_band', 'service_weighted'), bridge(kakao, 'ratio', tims, weights, rng)))
    hub_only = [r for r in kakao if r['source'] == 'hub']
    original = dict(zip(('per_band', 'service_weighted'), bridge(hub_only, 'ratio_border', tims, weights, rng)))
    campus = [r for r in kakao if r['source'] == 'campus']
    campus_summary = None
    if campus:
        # 경계를 실제로 넘는 운행(어느 한쪽 경로라도 5% 넘게 밖)만 비교해야 의미가 있다.
        crossing = [r for r in campus if r['outside_kakao'] is not None and max(r['outside_kakao'], r['outside_osrm']) > .05]
        diffs = [abs(r['outside_kakao'] - r['outside_osrm']) for r in crossing]
        with open(CAMPUS_SAMPLE, encoding='utf-8', newline='') as f:
            departures = sorted({x['departure_time'][:8] for x in csv.DictReader(f)})
        campus_summary = dict(
            n=len(campus), origin=CAMPUS, sample_file=str(CAMPUS_SAMPLE), sample_sha256=_sha(CAMPUS_SAMPLE), seed=SAMPLE_SEED,
            departure_dates=departures, crossing_routes=len(crossing),
            by_sub_band={sb: summarize([r['ratio_border'] for r in campus if r['sub_band'] == sb], rng) for sb in ('5-8', '8-11', '11-15')},
            bands=dict(statistics_counter(r['band'] for r in campus)),
            border=summarize([r['ratio_border'] for r in campus], rng),
            outside_fraction_kakao_vs_osrm_median_abs_diff=statistics.median(diffs) if diffs else None,
            inside_destinations=sum(1 for r in campus if r['inside']))
    split = {name: dict(**summarize([r['ratio'] for r in kakao if r['inside'] == flag], rng),
                        border=summarize([r['ratio_border'] for r in kakao if r['inside'] == flag], rng),
                        whole=summarize([r['ratio_whole'] for r in kakao if r['inside'] == flag], rng),
                        outside_fraction_median=statistics.median(r['outside'] for r in kakao if r['inside'] == flag))
             for name, flag in (('destination_in_pocheon', True), ('destination_outside_pocheon', False))}
    return dict(
        experiment='C16 quote bias bridge', seed=SEED, bootstrap=BOOT, bias_before=BIAS, bias_now=BIAS_NOW,
        border_rate=BORDER_RATE, border_rule='포천시 경계를 벗어난 구간(경로 길이 비율)의 기본요금 초과분에만 20%',
        inputs=dict(kakao=str(KAKAO), campus_kakao=str(CAMPUS_KAKAO), tims=str(TIMS), commute_grids=str(COMMUTE), c12_cases=str(C12_CASES),
                    boundary=str(SGIS), osrm=OSRM, outside_cache=str(OUTSIDE_CACHE)),
        kakao_quotes=len(kakao), kakao_hub_quotes=len(hub_only), campus=campus_summary,
        kakao_all=summarize([r['ratio'] for r in kakao], rng),
        kakao_all_border=summarize([r['ratio_border'] for r in kakao], rng), kakao_split=split,
        tims_commute_total=tims_total, per_band=per_band, service_requests=requests,
        service_weighted=weighted, scenarios=scenarios, no_border=no_border, original_200=original,
        limitations=[
            '카카오 견적과 실제 요금을 같은 운행으로 비교한 것이 아니다. 요금표 공식을 사이에 둔 간접 비교다. 평균 요금 수준의 비교이지 운행 한 번의 오차가 아니다.',
            '시계외 할증 20%의 적용 방식(경계부터)은 포천시 요금표에 적혀 있지 않아 서울시 택시요금 안내와 같은 방식으로 두었다. 경계 밖 비율은 보강 72건은 카카오 상세 경로, 기존 200건은 OSRM 경로로 쟀다.',
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
             '이 비교는 **평균 요금 수준의 간접 비교**다. 운행 한 번 한 번의 오차를 잰 것이 아니며, 95% 구간은 카카오 표본을 다시 뽑아 본 표본 불확실성만 담는다.', '',
             '## 결론', '',
             f"- 카카오 견적 {r['kakao_quotes']}건(C7 포천 시내 허브 출발 {r['kakao_hub_quotes']}건 + 이번에 받은 대진대 정문 출발 5~15km {r['campus']['n'] if r['campus'] else 0}건)은 "
             f"할증 없는 요금표 공식보다 중앙값 {pct(r['kakao_all']['median'])} 높지만, 포천시 시계외 할증(관외 20%, 경계 밖 구간)을 공식에 넣으면 {pct(r['kakao_all_border']['median'])}로 줄어든다.",
             f"- 지금 서비스 거리 분포로 가중하면 카카오 견적은 실제 평균보다 {pct(w['kakao_vs_actual'][0])} ~ {pct(w['kakao_vs_actual'][1])}"
             f"(95% 구간 {pct(w['kakao_vs_actual_ci95'][0])} ~ {pct(w['kakao_vs_actual_ci95'][1])}) 높다. 실제보다 낮다는 예전 앱의 전제와 반대다.",
             f"- 예전 앱 견적(×{1 + r['bias_before']:.4f})은 실제 평균보다 {pct(w['app_vs_actual'][0])} ~ {pct(w['app_vs_actual'][1])} 높았다"
             f"(95% 구간 {pct(w['app_vs_actual_ci95'][0])} ~ {pct(w['app_vs_actual_ci95'][1])}). 이용자가 실제보다 더 내고 차액이 적립금에 쌓이는 쪽이었다.",
             f"- **이 결과로 앱 보정을 {r['bias_now']:.0%}로 바꿨다**(카카오 견적 그대로). 카카오가 실제보다 조금 높게 나올 가능성이 크지만, 할증 방식·간접 비교의 모형 불확실성이 "
             '그 차이보다 커서 음수로 깎지 않았다. 남는 차이는 이용자 쪽 소폭 초과 부담이고 적립금에 쌓이며, 운영 중 실제 요금 입력이 쌓이면 다시 맞춘다.',
             '- 모의실험(C11~C15)의 견적은 카카오가 아니라 OSRM 경로 + 할증 없는 요금표 공식이고, 그 견적이 실제보다 6.57% 낮다는 것은 C5로 확인됐으므로 실험에는 6.57%를 그대로 둔다.', '']
    o = r['original_200']['service_weighted']
    lines += ['## 표본 보강 전후', '',
              f"- 보강 전(기존 {r['kakao_hub_quotes']}건, 5~15km는 8건): 카카오/실제 {pct(o['kakao_vs_actual'][0])} ~ {pct(o['kakao_vs_actual'][1])}"
              f"(95% 구간 {pct(o['kakao_vs_actual_ci95'][0])} ~ {pct(o['kakao_vs_actual_ci95'][1])}).",
              f"- 보강 후(5~15km {r['per_band']['5-15']['kakao_over_formula']['n']}건): {pct(w['kakao_vs_actual'][0])} ~ {pct(w['kakao_vs_actual'][1])}"
              f"(95% 구간 {pct(w['kakao_vs_actual_ci95'][0])} ~ {pct(w['kakao_vs_actual_ci95'][1])}). 결론은 그대로이고 구간이 좁아졌다."]
    c = r['campus']
    if c:
        sb = c['by_sub_band']
        lines += [f"- 보강 표본: 대진대 정문 출발, C12 모의 요청 목적지 중 OSRM 거리 5~8/8~11/11~15km에서 {PER_SUB_BAND}건씩, 견적을 받기 전에 뽑아 파일로 고정"
                  f"(sha256 {c['sample_sha256'][:12]}…, 출발일 {', '.join(c['departure_dates'])} 낮 12~21시). 카카오/할증 넣은 공식 중앙값 "
                  + ', '.join(f"{k}km {pct(v['median'])}" for k, v in sb.items()) + '로 소구간마다 비슷하다.',
                  f"- 경계를 넘는 보강 경로 {c['crossing_routes']}건에서 카카오 상세 경로와 OSRM 경로로 잰 포천 밖 비율의 차이는 중앙값 "
                  f"{(c['outside_fraction_kakao_vs_osrm_median_abs_diff'] or 0) * 100:.0f}%p다(기존 200건의 OSRM 비율을 그대로 써도 된다는 근거)."]
    sc = r['scenarios']
    lines += ['', '## 시계외 할증 가정별', '',
              '| 할증 가정 | 카카오/공식 포천 안 | 카카오/공식 포천 밖 | 안·밖 차이 | 카카오/실제(서비스 가중) |', '|---|---:|---:|---:|---|']
    for name, label in (('none', '할증 없음'), ('border', '**경계 밖 구간만 20% (기본)**'), ('whole', '포천 밖 목적지면 전 구간 20%')):
        x = sc[name]; sw = x['service_weighted']
        lines.append(f"| {label} | {pct(x['inside']['median'])} | {pct(x['outside']['median'])} | {abs(x['inside']['median'] - x['outside']['median']) * 100:.1f}%p | "
                     f"{pct(sw['kakao_vs_actual'][0])} ~ {pct(sw['kakao_vs_actual'][1])} |")
    lines += ['', '- 경계 밖 구간에만 붙이는 가정이 포천 안·밖 카카오 견적의 차이를 가장 작게 만든다. 카카오 견적을 가장 잘 설명하는 가정이라는 뜻이지, 포천의 법정 적용 방식을 증명한 것은 아니다.',
              '- 어느 가정에서도 카카오 견적은 실제 평균에서 ±10% 안이고, 예전 +6.57% 보정을 정당화하는 가정은 없다.', '']
    lines += ['## 거리대별', '',
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
    lines += ['', '- 할증을 빼면 포천 밖 운행만 크게 높아 보이지만, 경계 밖 구간에 20%를 붙이면 포천 안 운행과 비슷한 수준이 된다.',
              '- 할증 근거: 포천시 공공요금 현황(2026.1.1.)의 택시 "시계 외 할증 (관외) 20%". 경계를 벗어난 지점부터 붙는 방식은 서울시 택시요금 안내(시 경계를 벗어나는 시계외 구간에 20%)와 같게 두었다.', '',
              '## 읽는 법', '',
              '- 카카오/할증 넣은 공식: 카카오가 준 거리·시간을 C5와 같은 포천 낮 요금표 공식에 넣고, 경로(보강분은 카카오 상세 경로, 기존분은 OSRM)로 잰 포천 밖 비율만큼의 기본요금 초과분에 20%를 더한 값 대비 카카오 예상 요금.',
              '- 실제/공식: TIMS 2025 통학권 격자·월의 평균 거리·시간을 할증 없는 공식에 넣은 값 대비 실제 평균 요금(C5와 같은 범위). 서비스 운행의 실제 요금은 할증 넣은 공식 × (1+실제/공식)으로 본다.',
              '- 카카오/실제 = (1+카카오/할증 넣은 공식)/(1+실제/공식) − 1. 실제/공식은 격자·월 중앙값과 운행 가중 평균 두 값으로 범위를 냈다.',
              f"- 예전 앱 견적/실제 = (1+{r['bias_before']})×(1+카카오/실제) − 1. 지금 앱(보정 {r['bias_now']:.0%})은 카카오/실제와 같다. 일행 확정 금액에는 여기에 여유분 1%가 더 붙는다.",
              f"- 거리대 비중: C12 지금 서비스 모의 요청 {r['service_requests']:,}건의 혼자 거리.", '',
              '## 한계', ''] + [f'- {x}' for x in r['limitations']]
    lines += ['', '출처: 카카오모빌리티 길찾기 미래경로 API(C7 보정용으로 받은 400건 중 허브 출발 200건 + C16 보강 72건, 응답 원문은 .cache/c16/kakao-raw),한국교통안전공단 TIMS 2025 택시운행정보 격자별 영업통계(data.go.kr 15165682), '
              '포천시 공공요금 현황(https://www.pocheon.go.kr/www/contents.do?key=3878), 서울시 택시요금 안내(https://news.seoul.go.kr/traffic/archives/1659), '
              '국가데이터처 SGIS 2025년 2분기 시군구 경계, OpenStreetMap 기반 로컬 OSRM.']
    return '\n'.join(lines) + '\n'


def main():
    import argparse
    ap = argparse.ArgumentParser(description='C16 앱 견적 오차')
    ap.add_argument('step', nargs='?', default='report', choices=('sample', 'query', 'report'),
                    help='sample: 5~15km 보강 표본 고정(한 번만), query: 카카오 호출(재개 가능), report: 보고서')
    step = ap.parse_args().step
    if step == 'sample':
        print(len(sample_campus()), '건 표본 고정:', CAMPUS_SAMPLE); return
    if step == 'query':
        print(len(query_campus()), '건 응답:', CAMPUS_KAKAO); return
    report = analyze()
    REPORTS.mkdir(exist_ok=True)
    (REPORTS / 'c16-quote-bias.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (REPORTS / 'c16-quote-bias.md').write_text(markdown(report), encoding='utf-8')
    print(markdown(report))


if __name__ == '__main__':
    main()
