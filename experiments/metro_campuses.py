"""C13 2층: 수도권 대학을 실제 이동 자료(생활이동 OD)로 넓게 보는 축소 모의실험.

학교마다 같은 방식으로:
1. 출발지: 캠퍼스 좌표의 행정구역. 서울은 행정동(8자리), 경기·인천은 시군구(생활이동이 시군구 단위).
2. 귀가 흐름: 생활이동 OA-22300 2026-10 평일 20일, 그 출발지의 귀가(목적 3) 목적지·출발 시각.
3. 목적지: 목적지 시군구(서울은 자치구로 묶음) 흐름 × 그 안의 TIMS 2025 격자 승차 비율. 시군구마다 대표 격자를 두고 최대 800곳,
   고르지 않은 격자의 흐름은 같은 시군구의 가까운 격자로 옮겨 시군구별 흐름을 지킨다.
4. 경로: 로컬 OSRM(캠퍼스 좌표 → 격자, 격자 ↔ 격자), 시간은 C7 카카오 보정 배수. 혼자 요금은 지역 요금표.
5. 서비스: 현재 운영 코드(마감 확정 + 확정 분담금)를 C12와 같은 방식으로 10일씩.
6. 성별: 운영은 같은 성별끼리만 묶는다. 요청마다 그 캠퍼스의 재학생 남성 비율(대학알리미 2026)로 성별을 뽑는다.
"""
from __future__ import annotations
import csv, hashlib, io, json, math, statistics, subprocess, sys, time, zipfile
from collections import Counter, defaultdict
from dataclasses import replace
from datetime import date, datetime, timezone
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import campus_survey as c13
import detailed as c7
import livingmove as lm
import osrm_matrix as om
import scale_and_prepay as c10
import fixed_share_and_timing as c11
from fares import korea_tariff
import run

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = HERE / '.cache' / 'c13' / 'metro'
REPORTS = HERE / 'reports'
DAYS = 10
LEVELS = (35, 70, 140)
MAX_GRIDS = 800
MAX_KM = 60
SEED = c10.SEED


def campuses():
    data = json.loads((HERE / '.cache/c13/campuses.json').read_text(encoding='utf-8'))
    return [c for c in data if c['region'] in ('서울', '경기', '인천')]


def origin_codes(cs):
    """Kakao H code (행정동 10자리) -> 생활이동 출발 코드. 서울은 앞 8자리, 경기·인천은 시군구."""
    key = c13.kakao_key(); out = {}
    for c in cs:
        r = subprocess.run(['curl', '-s', '-m', '10', '-G', 'https://dapi.kakao.com/v2/local/geo/coord2regioncode.json',
                            '--data-urlencode', f"x={c['lon']}", '--data-urlencode', f"y={c['lat']}", '-H', 'Authorization: KakaoAK ' + key],
                           capture_output=True, text=True, encoding='utf-8')
        h = next(d for d in json.loads(r.stdout)['documents'] if d['region_type'] == 'H')
        code = h['code'][:8] if h['code'].startswith('11') else h['code'][:5] + '000'
        out[campus_id(c)] = dict(origin=code, dong=h['region_3depth_name'], sigungu=h['region_2depth_name'], level='행정동' if code.startswith('11') else '시군구')
        time.sleep(.05)
    return out


def campus_id(c):
    return hashlib.sha1(f"{c['name']}|{c['branch']}".encode()).hexdigest()[:10]


def aggregate(origins):
    """One pass over the 20 weekday OA-22300 files; keep home trips from any requested origin."""
    want = {v['origin'].encode() + b',' for v in origins.values()}
    dest = defaultdict(Counter); hours = defaultdict(Counter); days = set()
    for path in sorted((HERE / '.cache/kr-public/living-move').glob('seoul_purpose_admdong3_*.zip')):
        ds = path.stem[-8:]; dt = date.fromisoformat(f'{ds[:4]}-{ds[4:6]}-{ds[6:]}')
        if lm.exclusion_reason(dt):
            continue
        days.add(ds)
        with zipfile.ZipFile(path) as z, z.open(z.namelist()[0]) as stream:
            header = stream.readline().decode('utf-8-sig').strip().split(',')
            idx = {k: i for i, k in enumerate(header)}
            for line in stream:
                if line[:9] not in want:
                    continue
                f = line.decode('utf-8').rstrip('\r\n').split(',')
                if f[idx['move_purpose']] != '3':
                    continue
                cnt = lm.parse_count(f[idx['cnt']])
                if cnt is None:
                    continue
                o = f[idx['o_admdong_cd']]
                dest[o][f[idx['d_admdong_cd']]] += cnt
                hours[o][lm.normalize_hour(f[idx['st_time_cd']])] += cnt
        print('aggregated', path.name, flush=True)
    return dest, hours, sorted(days)


def destination_points(flows_by_code, hub, client, crosswalk, regions_cache={}):
    """Reuse the C7 universe rules for an arbitrary origin's destination flows."""
    from pyproj import Transformer
    public = HERE / '.cache/kr-public'
    flows = Counter()
    for code, v in flows_by_code.items():
        flows[code[:5] + '000'] += v  # 서울 행정동 목적지는 자치구로 묶는다(TIMS 격자 배정이 시군구 단위)
    canonical = {}; mapping = defaultdict(set)
    for r in om.read_csv(crosswalk):
        c = r.get('group') or r['mois']; canonical[r['mois']] = c; mapping[r['mois']].add(r['sgis'])
    merged = Counter()
    for c, v in flows.items():
        if c in mapping:
            merged[canonical.get(c, c)] += v
    reverse = {}
    for c, ss in mapping.items():
        for s in ss:
            reverse[s] = canonical.get(c, c)
    if 'points' not in regions_cache:
        tr = Transformer.from_crs(5179, 4326, always_xy=True); pts = []
        for r in om.read_csv(public / 'tims-regions.csv'):
            if r['status'] != 'MATCHED' or int(r['trips']) <= 0:
                continue
            lon, lat = tr.transform(float(r['center_e']), float(r['center_n']))
            pts.append(dict(grid=r['grid'], trips=int(r['trips']), sgis=r['sgis_sigungu'], lat=lat, lon=lon))
        pts.sort(key=lambda p: p['grid'])
        for i, p in enumerate(pts, 1):
            p['id'] = i
        regions_cache['points'] = pts
    from pyproj import Geod
    geod = Geod(ellps='WGS84')
    points = []
    for p in regions_cache['points']:
        rc = reverse.get(p['sgis'])
        if rc is None or merged.get(rc, 0) <= 0:
            continue
        if geod.inv(hub['lon'], hub['lat'], p['lon'], p['lat'])[2] / 1000 > MAX_KM:
            continue
        points.append(dict(p, region_code=rc, solo_km=None, solo_seconds=None))
    # 격자 무게 = 시군구 귀가 흐름 비율 × 그 시군구 안 TIMS 승차 비율
    flows_total = sum(merged.values()); totals = Counter()
    for p in points:
        totals[p['region_code']] += p['trips']
    for p in points:
        p['weight'] = merged[p['region_code']] / flows_total * p['trips'] / totals[p['region_code']]
    return represent(points, hub, client, flows, merged)


def represent(points, hub, client, flows, merged, max_grids=None):
    """격자를 MAX_GRIDS곳으로 줄이되 시군구별 흐름은 그대로 둔다.
    1) 흐름이 있는 시군구마다 승차가 가장 많은 격자 하나, 2) 남은 자리는 무게 순, 3) 고르지 않은 격자의 무게는 같은 시군구의
    가장 가까운 고른 격자로 옮긴다. 상위 격자만 남기고 다시 나누면 먼 시군구의 흐름이 빠져 이동이 짧게 잡힌다(11/20 이전 2층)."""
    max_grids = max_grids or MAX_GRIDS
    by_region = defaultdict(list)
    for p in points:
        by_region[p['region_code']].append(p)
    regions = sorted(by_region, key=lambda r: -sum(p['weight'] for p in by_region[r]))[:max_grids]
    chosen = {max(by_region[r], key=lambda p: (p['trips'], -p['id']))['id'] for r in regions}
    for p in sorted(points, key=lambda p: -p['weight']):
        if len(chosen) >= max_grids:
            break
        if p['region_code'] in regions:
            chosen.add(p['id'])
    sel = [dict(p) for p in points if p['id'] in chosen]
    for i in range(0, len(sel), 50):
        part = sel[i:i + 50]
        rows, _ = om.table_rows(client, [dict(hub, id=0)], part)
        for p, r in zip(part, rows):
            p.update(solo_km=r['km'], solo_seconds=r['seconds'])
    sel = [p for p in sel if p['solo_km'] is not None and 0.3 < p['solo_km'] <= MAX_KM]
    kept = defaultdict(list)
    for p in sel:
        kept[p['region_code']].append(p)
    for p in sel:
        p['weight'] = 0.
    for p in points:
        near = kept.get(p['region_code'])
        if near:
            min(near, key=lambda q: (q['lat'] - p['lat']) ** 2 + ((q['lon'] - p['lon']) * math.cos(math.radians(p['lat']))) ** 2)['weight'] += p['weight']
    represented = sum(p['weight'] for p in sel)
    for p in sel:
        p['probability'] = p['weight'] / represented
    sel.sort(key=lambda p: -p['weight'])
    return sel, dict(represented_flow_share=represented, grids=len(sel), destination_regions=len(merged), represented_regions=len(kept),
                     unresolved_flow_share=1 - sum(merged.values()) / sum(flows.values()) if flows else None)


def fit_tariff(points, kind):
    """Least-squares a + b*km + c*min of the regional tariff on this campus's solo routes (group route price model)."""
    X = np.array([[1, p['solo_km'], p['seconds'] / 60] for p in points]); y = np.array([p['fare'] for p in points])
    a, b, c = np.linalg.lstsq(X, y, rcond=None)[0]
    return float(a), float(b), float(max(c, 0))


def build_campus(c, info, dest, hours, days, client, ratio, crosswalk):
    out = CACHE / campus_id(c); out.mkdir(parents=True, exist_ok=True)
    hub = dict(id=0, lat=c['lat'], lon=c['lon'], region_code=info['origin'])
    cached = out / 'destinations.json'
    if cached.exists() and (out / 'matrix.tsv').exists():
        data = json.loads(cached.read_text(encoding='utf-8'))
        # 같은 캠퍼스 좌표·같은 목적지 방식(시군구 대표 격자, 'represented_regions')으로 만든 경로면 OSRM을 다시 부르지 않는다.
        if (data['hub']['lat'], data['hub']['lon']) == (hub['lat'], hub['lon']) and 'represented_regions' in data['coverage']                 and len(data['points']) <= MAX_GRIDS:
            return out, data
    flows = dest[info['origin']]
    if sum(flows.values()) <= 0:
        raise ValueError('No home flow for ' + c['name'])
    sel, cov = destination_points(flows, hub, client, crosswalk)
    kind = 'pocheon' if info['origin'].startswith('41650') else 'seoul'
    for p in sel:
        p['seconds'] = math.ceil(p['solo_seconds'] * ratio)
        p['fare'] = korea_tariff(p['solo_km'], p['seconds'] / 60, 12, kind)
        p['km'] = p['solo_km']
    nodes = [hub] + sel
    rows = []
    for i in range(0, len(nodes), 50):
        for j in range(0, len(nodes), 50):
            rr, _ = om.table_rows(client, nodes[i:i + 50], nodes[j:j + 50]); rows += rr
    with (out / 'matrix.tsv').open('w', encoding='utf-8', newline='') as f:
        w = csv.writer(f, delimiter='\t'); w.writerow(['i', 'j', 'km', 'seconds'])
        for r in rows:
            w.writerow([r['i'], r['j'], r['km'], math.ceil(r['seconds'] * ratio)])
    a, b, cc = fit_tariff(sel, kind)
    hh = Counter({int(h): v for h, v in hours[info['origin']].items()})
    data = dict(hub=hub, points=sel, coverage=cov, hours=[dict(hour=h, cnt=hh.get(h, 0)) for h in range(24)], days=len(days),
                age_check=dict(share_2030=None), tariff=dict(kind=kind, a=a, b=b, c=cc))
    (out / 'destinations.json').write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    return out, data


def cases_for(out, data, errors, levels, male_share=None):
    hub = data['hub']; points = data['points']; t = data['tariff']
    denom = sum(r['cnt'] for r in data['hours'] if 12 <= r['hour'] <= 21)
    hours = [dict(hour=r['hour'], probability=r['cnt'] / denom if 12 <= r['hour'] <= 21 and denom else 0) for r in data['hours']]
    header = ['CASE', 'C13_METRO', 'grid', 'timeline', 'FROM_HUB', '0', str(hub['lat']), str(hub['lon']),
              '1.3', '24', 'KAKAO', str(t['a']), str(t['b']), str(t['c']), '100', '4800', 'CACHE_MATRIX', 'KRW']
    errs = errors
    ss = [replace(c7.Scenario(), name=f'n{n}', requests=n, male_share=male_share) for n in levels]
    c7.write_cases(out / 'cases.tsv', ss, DAYS, SEED, points, hours, header, errs)


def simulate(command, out, bias, margin):
    with (out / 'sim.log').open('w', encoding='utf-8') as log:
        subprocess.run(command + ['com.campustaximate.server.service.C11Simulation', str(out / 'cases.tsv'), str(out / 'matrix.tsv'),
                                  str(HERE / '.cache/kr-public/tims-errors.csv'), str(out / 'groups.tsv'), str(out / 'days.csv'), '-', str(bias),
                                  'CURRENT', str(margin)], cwd=ROOT, check=True, stdout=log, stderr=log)
    groups = c11.read_tsv(out / 'groups.tsv'); days = c7.read_csv(out / 'days.csv')
    res = {}
    for n in sorted({int(d['case_id'].split('_day')[0][1:]) for d in days}):
        dd = [d for d in days if int(d['case_id'].split('_day')[0][1:]) == n]
        gg = [g for g in groups if int(g['case_id'].split('_day')[0][1:]) == n]
        saving = [1 - a / u for g in gg for a, u in zip(c11.split_numbers(g['expected']), c11.split_numbers(g['units']))]
        waits = [x for g in gg for x in c11.split_numbers(g['waits'])]
        matched = sum(int(d['matched']) for d in dd); req = sum(int(d['requests']) for d in dd)
        res[str(n)] = dict(match_rate=matched / req, mean_saving_matched=statistics.mean(saving) if saving else 0,
                           requester_saving=(matched / req) * (statistics.mean(saving) if saving else 0),
                           mean_wait=statistics.mean(waits) if waits else None,
                           daily=[int(d['matched']) / int(d['requests']) for d in dd])
    return res


def main():
    import argparse
    ap = argparse.ArgumentParser(); ap.add_argument('--limit', type=int, default=0); a = ap.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    allc = campuses()
    cs = allc[:a.limit] if a.limit else allc
    ocache = CACHE / 'origins.json'
    origins = json.loads(ocache.read_text(encoding='utf-8')) if ocache.exists() else {}
    todo = [c for c in allc if campus_id(c) not in origins]
    if todo:
        origins.update(origin_codes(todo)); ocache.write_text(json.dumps(origins, ensure_ascii=False, indent=1), encoding='utf-8')
    agg_path = CACHE / 'home-flows.json'
    need = {origins[campus_id(c)]['origin'] for c in allc}
    agg = json.loads(agg_path.read_text(encoding='utf-8')) if agg_path.exists() else dict(dest={}, hours={}, days=[])
    if not need <= set(agg['dest']):
        d, h, days = aggregate({k: v for k, v in origins.items() if v['origin'] in need | set(agg['dest'])})
        agg = dict(dest={k: dict(v) for k, v in d.items()}, hours={k: {str(x): y for x, y in v.items()} for k, v in h.items()}, days=days)
        agg_path.write_text(json.dumps(agg, ensure_ascii=False), encoding='utf-8')
    dest = {k: Counter(v) for k, v in agg['dest'].items()}; hours = {k: Counter({int(x): y for x, y in v.items()}) for k, v in agg['hours'].items()}
    ratio = json.loads((HERE / '.cache/osrm/calibration-model.json').read_text(encoding='utf-8'))['duration_ratio']
    crosswalk = HERE / '.cache/kr-public/livingmove-sgis-crosswalk.csv'
    client = om.OSRM('http://127.0.0.1:5000')
    errors = [float(x) for x in (HERE / '.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    bias, margin = c12.fare_config()
    command, provenance = c12.compile_current()
    shares = c13.male_shares()
    results = []
    for c in cs:
        cid = campus_id(c); info = origins[cid]
        res_path = CACHE / cid / 'result.json'
        male = shares.get((c['name'], c['branch']))
        if res_path.exists():
            old = json.loads(res_path.read_text(encoding='utf-8'))
            if old.get('male_share') == male:  # 성비가 바뀌면(성별 없는 옛 결과 포함) 다시 돌린다
                results.append(old); continue
        if sum(dest.get(info['origin'], {}).values()) <= 0:
            results.append(dict(campus=c['name'], branch=c['branch'], region=c['region'], skipped='출발지 귀가 흐름 없음')); continue
        t0 = time.time()
        try:
            out, data = build_campus(c, info, dest, hours, agg['days'], client, ratio, crosswalk)
            observed = max(10, min(600, round(c['student_excess_per_day']))) if c.get('student_excess_per_day') is not None else None
            levels = tuple(sorted(set(LEVELS) | ({observed} if observed else set())))
            cases_for(out, data, errors, levels, male)
            res = simulate(command, out, bias, margin)
        except Exception as e:  # 한 학교 실패가 전체를 멈추지 않게 이유를 남긴다
            results.append(dict(campus=c['name'], branch=c['branch'], region=c['region'], skipped=f'{type(e).__name__}: {e}'[:200])); continue
        r = dict(campus=c['name'], branch=c['branch'], region=c['region'], enrolled=c['enrolled'], lon=c['lon'], lat=c['lat'],
                 origin=info, observed_level=observed, male_share=male, student_excess_per_day=c.get('student_excess_per_day'),
                 semester_taxi_per_day=c['semester_per_day'], coverage=data['coverage'],
                 mean_solo_km=sum(p['probability'] * p['solo_km'] for p in data['points']),
                 home_flow_per_day=sum(dest[info['origin']].values()) / len(agg['days']),
                 results=res, seconds=time.time() - t0)
        res_path.write_text(json.dumps(r, ensure_ascii=False), encoding='utf-8')
        results.append(r)
        print('done', c['name'], c['branch'], {k: round(v['match_rate'], 3) for k, v in res.items()}, f"{r['seconds']:.0f}s", flush=True)
    run.json_write(CACHE / 'results.json', results)
    print('C13 layer2 done', len([r for r in results if 'results' in r]), 'campuses')


if __name__ == '__main__':
    main()
