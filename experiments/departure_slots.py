"""C13 3층: 출발 편 실험. 예약 요청의 출발을 정해진 간격의 '편'으로 모으면 매칭이 얼마나 느는가.

2층(metro_campuses.py)이 학교별로 만든 요청·경로를 그대로 쓰고, 정책만 바꿔 같은 날을 다시 돌린다.
- CURRENT: 지금 서비스(원하는 가장 이른 출발부터 20분 안에서 자유 출발, 마감 확정 + 확정 분담금).
- SLOT20 / SLOT30: 예약 요청은 정오 기준 20분·30분 간격 편 중, 원래 가장 이른 출발 이후 첫 편에 출발(편 시각부터 5분 안).
- FLEX30: 비교군. 시각을 고정하지 않고 예약 요청의 출발 가능 폭만 20분 → 30분(편과 비슷하게 '늦게 떠나도 됨'을 쓴다).
  요청 시각·즉시 요청·요금·매칭 조건(지연 15분, 절약 10%)은 그대로다.
요청 시각이 그대로면 편은 지금의 20분 출발 폭을 5분짜리 점으로 좁히는 것이라 가능한 묶음이 줄어든다. 이 실험은 그 크기를 잰다.
지표: 매칭률, 요청자 전체 1인 절약(매칭률 × 매칭자 절약), 요청부터 확정까지 기다림, 원하던 가장 이른 출발에서 실제 출발까지 밀린 시간.
"""
from __future__ import annotations
import json, statistics, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import detailed as c7
import fixed_share_and_timing as c11
import run

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
METRO = HERE / '.cache' / 'c13' / 'metro'
CACHE = HERE / '.cache' / 'c13' / 'slots'
REPORTS = HERE / 'reports'
POLICIES = ('CURRENT', 'SLOT20', 'SLOT30', 'FLEX30')
LEAD = 25 * 60       # 운영 leadMinutes: 요청 뒤 가장 이른 출발
WINDOW = 20 * 60     # 지금 서비스의 출발 가능 폭(deadline - arrival)


def slot_start(arrival, minutes):
    """원래 가장 이른 출발(arrival + 25분) 이후 첫 편 시각(정오 기준 초). C11Simulation.slotted와 같은 식."""
    earliest = int(arrival) + LEAD; step = minutes * 60
    return -(-earliest // step) * step


def requests(path):
    """cases.tsv에서 날(case)별 요청 {id: (arrival, instant)}."""
    out, case = {}, None
    for line in path.read_text(encoding='utf-8').splitlines():
        s = line.split('\t')
        if s[0] == 'CASE': case = s[2]; out[case] = {}
        elif s[0] == 'R': out[case][int(s[1])] = (float(s[8]), s[10] == '1')
    return out


def summarize(groups, days, reqs, policies=POLICIES):
    """정책·수요별 지표. 밀린 시간은 매칭된 예약 요청의 실제 출발 − 원하던 가장 이른 출발."""
    res = {}
    for pol in policies:
        for n in sorted({int(d['case_id'].split('_day')[0][1:]) for d in days}):
            key = lambda cid: cid.split('_day')[0] == f'n{n}'
            dd = [d for d in days if d['policy'] == pol and key(d['case_id'])]
            gg = [g for g in groups if g['policy'] == pol and key(g['case_id'])]
            if not dd: continue
            saving = [1 - a / u for g in gg for a, u in zip(c11.split_numbers(g['expected']), c11.split_numbers(g['units']))]
            waits = [x for g in gg for x in c11.split_numbers(g['waits'])]
            shifts, beyond = [], 0
            for g in gg:
                for i in (int(x) for x in g['ids'].split(';')):
                    arrival, instant = reqs[g['case_id']][i]
                    if instant: continue
                    shifts.append((int(g['departure']) - arrival - LEAD) / 60)
            reserve = [a for d in dd for a, inst in reqs[d['case_id']].values() if not inst]
            if pol.startswith('SLOT'):
                m = int(pol[4:]); beyond = sum(slot_start(a, m) + 300 > a + LEAD + WINDOW for a in reserve) / len(reserve)
            matched = sum(int(d['matched']) for d in dd); req = sum(int(d['requests']) for d in dd)
            ms = statistics.mean(saving) if saving else 0
            res.setdefault(pol, {})[str(n)] = dict(
                match_rate=matched / req, mean_saving_matched=ms, requester_saving=matched / req * ms,
                mean_wait=statistics.mean(waits) if waits else None, mean_shift=statistics.mean(shifts) if shifts else None,
                beyond_window=beyond, daily=[int(d['matched']) / int(d['requests']) for d in dd])
    return res


def simulate(command, src, out, bias, margin, policies=POLICIES):
    out.mkdir(parents=True, exist_ok=True)
    with (out / 'sim.log').open('w', encoding='utf-8') as log:
        subprocess.run(command + ['com.campustaximate.server.service.C11Simulation', str(src / 'cases.tsv'), str(src / 'matrix.tsv'),
                                  str(HERE / '.cache/kr-public/tims-errors.csv'), str(out / 'groups.tsv'), str(out / 'days.csv'), '-', str(bias),
                                  ','.join(policies), str(margin)], cwd=ROOT, check=True, stdout=log, stderr=log)
    return summarize(c11.read_tsv(out / 'groups.tsv'), c7.read_csv(out / 'days.csv'), requests(src / 'cases.tsv'), policies)


def main():
    bias, margin = c12.fare_config()
    command, provenance = c12.compile_current()
    layer2 = [r for r in json.loads((METRO / 'results.json').read_text(encoding='utf-8')) if 'results' in r]
    import metro_campuses as m
    results = []
    for r in layer2:
        cid = m.campus_id(dict(name=r['campus'], branch=r['branch']))
        path = CACHE / cid / 'result.json'
        if path.exists():
            results.append(json.loads(path.read_text(encoding='utf-8'))); continue
        res = simulate(command, METRO / cid, CACHE / cid, bias, margin)
        x = dict(campus=r['campus'], branch=r['branch'], region=r['region'], mean_solo_km=r['mean_solo_km'],
                 observed_level=r['observed_level'], layer2=r['results'], results=res)
        path.write_text(json.dumps(x, ensure_ascii=False), encoding='utf-8'); results.append(x)
        print('done', r['campus'], r['branch'], {p: round(v['35']['match_rate'], 3) for p, v in res.items()}, flush=True)
    run.json_write(CACHE / 'results.json', dict(policies=POLICIES, bias=bias, margin=margin, provenance=provenance, campuses=results))
    report(results)


def pct(x):
    return '-' if x is None else f'{100 * x:.0f}%'


def report(rs):
    med = lambda xs: statistics.median([x for x in xs if x is not None])
    def v(r, pol, n): return r['results'][pol].get(str(n))
    def obs(r, pol): return r['observed_level'] and v(r, pol, r['observed_level'])
    names = dict(CURRENT='지금', SLOT20='20분 편', SLOT30='30분 편', FLEX30='출발 폭 30분')
    dj = next((r for r in rs if r['campus'] == '대진대학교'), None)
    L = ['# C13 3층: 출발 편 실험', '',
         '2층의 수도권 캠퍼스별 요청·경로(생활이동 2026-10, TIMS 2025, OSM)를 그대로 두고 정책만 바꿔 같은 10일을 다시 돌렸다.', '',
         '- 지금: 원하는 가장 이른 출발부터 20분 안 아무 때나 출발.',
         '- 20분 편 / 30분 편: 예약 요청은 정오 기준 편 중 원래 가장 이른 출발 이후 첫 편에 출발(편 시각부터 5분 안).',
         '- 출발 폭 30분: 시각 고정 없이 출발 가능 폭만 30분으로 넓힌다(늦게 떠나도 되는 정도가 30분 편과 비슷한 비교군).',
         '- 즉시 요청·요금·매칭 조건(지연 15분, 절약 10%)은 모두 같다.', '',
         '## 매칭률(캠퍼스 %d곳 중앙값)' % len(rs), '',
         '| 하루 요청 | ' + ' | '.join(names[p] for p in POLICIES) + ' |', '|---:|' + '---:|' * len(POLICIES)]
    for n in (35, 70, 140):
        L.append(f'| {n}건 | ' + ' | '.join(pct(med([v(r, p, n)['match_rate'] for r in rs])) for p in POLICIES) + ' |')
    L.append('| 학생 수준 | ' + ' | '.join(pct(med([obs(r, p)['match_rate'] for r in rs if obs(r, p)])) for p in POLICIES) + ' |')
    L += ['', '## 하루 35건에서 얻는 것과 치르는 것(중앙값)', '',
          '| | ' + ' | '.join(names[p] for p in POLICIES) + ' |', '|---|' + '---:|' * len(POLICIES),
          '| 요청자 전체 1인 절약 | ' + ' | '.join(pct(med([v(r, p, 35)['requester_saving'] for r in rs])) for p in POLICIES) + ' |',
          '| 원하던 가장 이른 출발에서 밀린 시간(매칭된 예약) | ' + ' | '.join(f"{med([v(r, p, 35)['mean_shift'] for r in rs]):.1f}분" for p in POLICIES) + ' |',
          '| 요청부터 확정까지 기다림 | ' + ' | '.join(f"{med([v(r, p, 35)['mean_wait'] for r in rs]):.1f}분" for p in POLICIES) + ' |',
          '| 예약 요청 중 지금의 20분 폭을 넘는 출발 | ' + ' | '.join(pct(med([v(r, p, 35)['beyond_window'] for r in rs])) for p in POLICIES) + ' |', '']
    gain = {p: [v(r, p, 35)['match_rate'] - v(r, 'CURRENT', 35)['match_rate'] for r in rs] for p in POLICIES[1:]}
    L += ['## 요약', '']
    for p in POLICIES[1:]:
        L.append(f"- {names[p]}: 하루 35건 매칭률 변화 중앙값 {100 * med(gain[p]):+.0f}%p, 오른 학교 {sum(g > 0 for g in gain[p])}곳 / {len(rs)}곳.")
    L.append('- 요청 시각이 그대로면 편은 출발을 점으로 고정해 같이 탈 수 있던 사람을 다른 편으로 가른다. 같은 만큼 늦게 떠나도 된다면 시각을 고정하지 않고 폭을 넓히는 쪽이 더 많이 묶는다.')
    if dj:
        n0 = dj['observed_level']
        L.append(f"- 대진대 35건: " + ', '.join(f"{names[p]} {pct(v(dj, p, 35)['match_rate'])}" for p in POLICIES)
                 + f"; 학생 수준 {n0}건: " + ', '.join(f"{names[p]} {pct(obs(dj, p)['match_rate'])}" for p in POLICIES) + '.')
    L += ['', '## 학교별(하루 35건 매칭률)', '',
          '| 학교 | 평균 혼자 이동 | ' + ' | '.join(names[p] for p in POLICIES) + ' |', '|---|---:|' + '---:|' * len(POLICIES)]
    for r in sorted(rs, key=lambda r: -v(r, 'CURRENT', 35)['match_rate']):
        L.append(f"| {r['campus']}{'' if r['branch'] == '본교' else ' ' + r['branch']} | {r['mean_solo_km']:.1f}km | "
                 + ' | '.join(pct(v(r, p, 35)['match_rate']) for p in POLICIES) + ' |')
    L += ['', '## 한계', '',
          '- 편이 생기면 학생이 요청 시각 자체를 편에 맞출 수 있다. 여기서는 요청 시각을 그대로 두었으므로, 편의 효과는 이런 행동 변화가 있어야만 생긴다. 그 크기는 공개 자료로 잴 수 없다.',
          '- 출발 폭을 넓히거나 편에 맞추느라 이용을 포기하는 사람은 셈하지 않았다.',
          '- 2층과 같은 요청·경로·10일 축소 실행이라 학교별 값의 오차가 C12(30일)보다 크다.']
    (REPORTS / 'c13-departure-slots.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    run.json_write(REPORTS / 'c13-departure-slots.json', dict(policies=POLICIES, campuses=rs))


if __name__ == '__main__':
    main()
