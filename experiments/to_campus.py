"""C15 등교 모의 운영: 승차 지점에 모여 학교로 가는 도착 기준 요청을 지금 서비스 코드로 돌린다.

C7~C14는 모두 하교(학교 → 집)만 봤다. 지금 서비스의 등교는 순차 승차(TO_SCHOOL, 옛 형태)가 아니라,
사는 곳 근처 승차 지점에 모여 학교로 가는 도착 기준 요청(MatchRequest.arriving, 방향 FROM_HUB)이다.

학교마다 같은 방식으로:
1. 출발 격자: 2층(metro_campuses)에서 그 캠퍼스 학생이 귀가하는 목적지 격자와 무게를 그대로 쓴다(아침에 같은 곳에서 온다고 본다).
   같은 500m 격자에 사는 학생은 같은 승차 지점에 모인다.
2. 경로: 격자 → 캠퍼스를 로컬 OSRM으로 조회하고 C7 카카오 보정 배수를 곱한다. 요금은 지역 요금표.
3. 도착 시각: 생활이동(2026-10 평일)에서 캠퍼스 지역에 도착하는 이동의 도착 시각 분포(to_campus_hours.py, 목적 코드 PURPOSE).
   시각 안에서는 고르게 뽑고 5분 단위로 맞춘다(서버 입력 규칙).
4. 성별: 그 캠퍼스 재학생 남성 비율(C14).
5. 서비스: ToCampusSimulation(운영 MatchingRound·RouteGroupVerifier, 모집 마감은 서버 arrivalDeadline과 같은 식, 일찍 도착 10분).
"""
from __future__ import annotations
import json, math, random, statistics, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import campus_survey as c13
import current_service as c12
import detailed as c7
import metro_campuses as m
import osrm_matrix as om
from fares import korea_tariff
import run

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = HERE / '.cache' / 'c15'
REPORTS = HERE / 'reports'
DAYS = 10
LEVELS = (35, 70, 140)
SEED = 20261121
# 승차 지점을 어떻게 모으나: 자기 500m 격자(가장 엄격), 1km 칸(걸어서 10분 규칙), 시군구 거점 하나(역처럼 큰 거점, 상한)
SCENARIOS = {'grid': 500, 'walk': 1000, 'region': 'region'}
SCENARIO_NAMES = {'grid': '자기 격자(500m)', 'walk': '걸어서 10분(1km 칸)', 'region': '시군구 거점(상한)'}
# 목적 코드 2를 등교로 본다: 평일 대학가(예: 한국외대 이문1동)에서 2가 1(출근)보다 많고 8~11시 수업 시각에 퍼져 있다.
PURPOSE = '2'


def campus_routes(cid, data, client, ratio):
    """격자 → 캠퍼스 경로. 학교마다 한 번 조회해 저장한다."""
    path = CACHE / cid / 'to-campus.json'
    if path.exists():
        return json.loads(path.read_text(encoding='utf-8'))
    hub = dict(data['hub'], id=0); kind = data['tariff']['kind']; out = {}
    pts = data['points']
    for i in range(0, len(pts), 50):
        part = [dict(p) for p in pts[i:i + 50]]
        rows, _ = om.table_rows(client, part, [hub])
        for r in rows:
            seconds = math.ceil(r['seconds'] * ratio)
            out[str(r['i'])] = dict(km=r['km'], seconds=seconds, fare=korea_tariff(r['km'], seconds / 60, 9, kind))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(out), encoding='utf-8')
    return out


def arrival_hours(code, hours):
    """캠퍼스 지역 도착 시각 분포(7~18시). 그 지역 자료가 없으면 None."""
    h = hours.get(code, {}).get(PURPOSE, {})
    w = {int(k): v for k, v in h.items() if 7 <= int(k) <= 18}
    total = sum(w.values())
    return {k: v / total for k, v in w.items()} if total > 0 else None


def boarding_points(pts, cell):
    """사는 격자 → 승차 지점. cell=500이면 자기 격자(가장 엄격). cell=1000이면 1km 칸 안의 학생이 한 지점에 모인다
    (칸 안에서 가장 많이 사는 격자를 승차 지점으로, 칸 끝에서 걸어 약 700m·10분 이내: 서비스의 '걸어서 10분' 규칙)."""
    if cell == 'region':  # 상한: 같은 시군구(서울은 자치구) 학생이 역처럼 큰 거점 한 곳에 모인다
        groups = {}
        for p in pts:
            groups.setdefault(p['region_code'], []).append(p)
        return {p['id']: max(g, key=lambda q: q['probability']) for g in groups.values() for p in g}
    if cell <= 500:
        return {p['id']: p for p in pts}
    from pyproj import Transformer
    tr = Transformer.from_crs(4326, 5179, always_xy=True)
    groups = {}
    for p in pts:
        x, y = tr.transform(p['lon'], p['lat'])
        groups.setdefault((int(x // cell), int(y // cell)), []).append(p)
    return {p['id']: max(g, key=lambda q: q['probability']) for g in groups.values() for p in g}


def write_days(path, data, routes, hour_weights, male, levels, cell=500):
    pts = [p for p in data['points'] if str(p['id']) in routes and 0.3 < routes[str(p['id'])]['km'] <= m.MAX_KM]
    weights = [p['probability'] for p in pts]
    board = boarding_points(pts, cell)
    hours, hw = zip(*sorted(hour_weights.items()))
    with path.open('w', encoding='utf-8', newline='\n') as f:
        for n in levels:
            for day in range(DAYS):
                rng = random.Random(SEED * 1000003 + n * 7919 + day * 104729)
                f.write('\t'.join(['DAY', f'n{n}_day{day:02}', str(data['hub']['lat']), str(data['hub']['lon'])]) + '\n')
                for i in range(1, n + 1):
                    p = board[rng.choices(pts, weights=weights)[0]['id']]; r = routes[str(p['id'])]
                    hour = rng.choices(hours, weights=hw)[0]
                    arrive = hour * 3600 + rng.randrange(12) * 300   # 5분 단위
                    sex = 'M' if rng.random() < male else 'F'
                    f.write('\t'.join(map(str, ['R', i, p['id'], p['lat'], p['lon'], r['km'], r['seconds'], r['fare'], arrive, sex])) + '\n')
                f.write('END\n')


def simulate(command, out, bias, margin):
    with (out / 'sim.log').open('w', encoding='utf-8') as log:
        subprocess.run(command + ['com.campustaximate.server.service.ToCampusSimulation', str(out / 'days.tsv'), str(out / 'groups.tsv'),
                                  str(out / 'days.csv'), str(bias), str(margin)], cwd=ROOT, check=True, stdout=log, stderr=log)
    groups = _tsv(out / 'groups.tsv')
    days = c7.read_csv(out / 'days.csv')
    res = {}
    for n in sorted({int(d['day'].split('_day')[0][1:]) for d in days}):
        dd = [d for d in days if d['day'].startswith(f'n{n}_')]
        gg = [g for g in groups if g['day'].startswith(f'n{n}_')]
        saving = [1 - int(s) / int(u) for g in gg for s, u in zip(g['shares'].split(';'), g['units'].split(';'))]
        waits = [float(x) for g in gg for x in g['waits'].split(';')]
        sizes = [len(g['ids'].split(';')) for g in gg]
        matched = sum(int(d['matched']) for d in dd); req = sum(int(d['requests']) for d in dd)
        ms = statistics.mean(saving) if saving else 0
        res[str(n)] = dict(match_rate=matched / req, mean_saving_matched=ms, requester_saving=matched / req * ms,
                           mean_wait=statistics.mean(waits) if waits else None, mean_group_size=statistics.mean(sizes) if sizes else None,
                           daily=[int(d['matched']) / int(d['requests']) for d in dd])
    return res


def _tsv(path):
    import csv
    with path.open(encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f, delimiter='\t'))


def main():
    bias, margin = c12.fare_config(); command, provenance = c12.compile_current()
    hours = json.loads((CACHE / 'arrival-hours.json').read_text(encoding='utf-8'))['hours']
    layer2 = [r for r in json.loads((m.CACHE / 'results.json').read_text(encoding='utf-8')) if 'results' in r]
    shares = c13.male_shares()
    ratio = json.loads((HERE / '.cache/osrm/calibration-model.json').read_text(encoding='utf-8'))['duration_ratio']
    client = om.OSRM('http://127.0.0.1:5000')
    results = []
    for r in layer2:
        cid = m.campus_id(dict(name=r['campus'], branch=r['branch'])); out = CACHE / cid
        res_path = out / 'result.json'
        if res_path.exists():
            results.append(json.loads(res_path.read_text(encoding='utf-8'))); continue
        data = json.loads((m.CACHE / cid / 'destinations.json').read_text(encoding='utf-8'))
        hw = arrival_hours(r['origin']['origin'], hours)
        if hw is None:
            results.append(dict(campus=r['campus'], branch=r['branch'], skipped='도착 시각 자료 없음')); continue
        routes = campus_routes(cid, data, client, ratio)
        levels = tuple(sorted(set(LEVELS) | ({r['observed_level']} if r.get('observed_level') else set())))
        res = {}
        for name, cell in SCENARIOS.items():
            write_days(out / 'days.tsv', data, routes, hw, r['male_share'], levels, cell)
            res[name] = simulate(command, out, bias, margin)
        x = dict(campus=r['campus'], branch=r['branch'], region=r['region'], male_share=r['male_share'], observed_level=r.get('observed_level'),
                 mean_solo_km=r['mean_solo_km'], arrival_hours=hw, home=r['results'], results=res)
        res_path.write_text(json.dumps(x, ensure_ascii=False), encoding='utf-8'); results.append(x)
        print('done', r['campus'], r['branch'], {s: round(v['35']['match_rate'], 3) for s, v in res.items()}, flush=True)
    run.json_write(CACHE / 'results.json', dict(purpose=PURPOSE, bias=bias, margin=margin, provenance=provenance, campuses=results))
    report(results)


def report(rs):
    ok = [r for r in rs if 'results' in r]
    pct = lambda x: '-' if x is None else f'{100 * x:.0f}%'
    med = lambda xs: statistics.median([x for x in xs if x is not None]) if xs else None
    def v(r, s, n): return (r['home'] if s == 'home' else r['results'][s]).get(str(n))
    def obs(r, s): return r.get('observed_level') and v(r, s, r['observed_level'])
    cols = list(SCENARIOS) + ['home']
    head = '| 하루 요청 | ' + ' | '.join(SCENARIO_NAMES.get(c, '하교(2층, 범위 20분)') for c in cols) + ' |'
    dj = next((r for r in ok if r['campus'] == '대진대학교'), None)
    L = ['# C15 등교 모의 운영', '',
         f'C7~C14는 하교만 봤다. 지금 서비스의 등교는 사는 곳 근처 승차 지점에 모여 학교로 가는 도착 기준 요청이다(MatchRequest.arriving). 수도권 캠퍼스 {len(ok)}곳에서 지금 서비스 코드로 10일씩 돌렸다.', '',
         '- 사는 곳: 2층의 귀가 목적지 격자와 무게(아침에 같은 곳에서 온다고 본다).',
         '- 승차 지점 세 가지: 자기 500m 격자(가장 엄격), 1km 칸 안의 한 지점(서비스의 "걸어서 10분" 규칙), 시군구(서울은 자치구) 거점 한 곳(역처럼 큰 거점에 모이는 상한).',
         f'- 도착 시각: 생활이동 2026-10 평일, 캠퍼스 지역에 도착하는 등교 이동(목적 코드 {PURPOSE})의 7~18시 분포, 5분 단위.',
         '- 성별: 캠퍼스 재학생 남성 비율, 같은 성별끼리만. 모집 마감은 서버와 같은 식(도착 − 혼자 소요 − 준비 25분 − 35분), 일찍 도착 10분, 요청은 마감 1시간 전.', '',
         '## 매칭률(캠퍼스 중앙값)', '', head, '|---:|' + '---:|' * len(cols)]
    for n in LEVELS:
        L.append(f'| {n}건 | ' + ' | '.join(pct(med([v(r, c, n)['match_rate'] for r in ok if v(r, c, n)])) for c in cols) + ' |')
    L.append('| 학생 수준 | ' + ' | '.join(pct(med([obs(r, c)['match_rate'] for r in ok if obs(r, c)])) for c in cols) + ' |')
    L += ['', '## 하루 35건, 요청자 전체 1인 절약(캠퍼스 중앙값)', '', '| ' + ' | '.join(SCENARIO_NAMES.get(c, '하교') for c in cols) + ' |', '|' + '---:|' * len(cols),
          '| ' + ' | '.join(pct(med([v(r, c, 35)['requester_saving'] for r in ok])) for c in cols) + ' |', '']
    w = [v(r, 'walk', 35) for r in ok]
    L += ['## 요약', '',
          f"- 등교는 하교보다 훨씬 덜 묶인다. 하루 35건, 걸어서 10분 규칙이면 매칭률 중앙값 {pct(med([x['match_rate'] for x in w]))}, 하교는 {pct(med([v(r, 'home', 35)['match_rate'] for r in ok]))}.",
          '- 하교는 모두 같은 정문에서 출발해 방향만 맞으면 된다. 등교는 같은 승차 지점에 사는 같은 성별 학생이 비슷한 시각(10분 안)에 도착해야 해 출발 쪽이 흩어진다.',
          f"- 묶이면 절약은 크다(모두 같은 학교로 가 경로가 같다): 매칭된 사람 1인 절약 중앙값 {pct(med([x['mean_saving_matched'] for x in w if x['match_rate'] > 0]))}.",
          f"- 시군구 거점 하나에 모인다면(상한) 35건 {pct(med([v(r, 'region', 35)['match_rate'] for r in ok]))}. 등교를 살리는 열쇠는 매칭 규칙이 아니라 모이는 곳(역·정류장 같은 큰 거점)을 정하는 것이다."]
    if dj:
        L.append('- 대진대 35건: ' + ', '.join(f"{SCENARIO_NAMES.get(c, '하교')} {pct(v(dj, c, 35)['match_rate'])}" for c in cols) + '.')
    L += ['', '## 학교별(하루 35건 매칭률)', '', '| 학교 | ' + ' | '.join(SCENARIO_NAMES.get(c, '하교') for c in cols) + ' |', '|---|' + '---:|' * len(cols)]
    for r in sorted(ok, key=lambda r: -v(r, 'walk', 35)['match_rate']):
        L.append(f"| {r['campus']}{'' if r['branch'] == '본교' else ' ' + r['branch']} | " + ' | '.join(pct(v(r, c, 35)['match_rate']) for c in cols) + ' |')
    skipped = [r for r in rs if 'skipped' in r]
    if skipped:
        L += ['', '## 계산하지 못한 곳', ''] + [f"- {r['campus']} {r['branch']}: {r['skipped']}" for r in skipped]
    L += ['', '## 한계', '',
          '- 아침에 오는 곳을 저녁에 귀가하는 곳과 같다고 놓았다(같은 학생의 통학 왕복).',
          '- 승차 지점까지 걷는 시간은 넣지 않았다. 1km 칸은 칸 끝에서 걸어 약 10분, 시군구 거점은 걷기로 닿지 않는 거리라 상한으로만 본다.',
          f'- 도착 시각 분포는 그 지역에 도착하는 등교 이동 전체(목적 코드 {PURPOSE})다. 목적 코드의 정의는 공개 문서가 아니라 시간 분포로 추정했다(to_campus_hours.py).',
          '- 하루 요청 수는 하교와 같은 수준을 가정했다. 등교 택시 수요를 따로 재는 공개 자료는 없다.']
    (REPORTS / 'c15-to-campus.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    run.json_write(REPORTS / 'c15-to-campus.json', dict(purpose=PURPOSE, scenarios=SCENARIOS, campuses=rs))


if __name__ == '__main__':
    main()
