"""C13 3층 후속: 출발 가능 범위 곡선. 예약 요청이 고르는 출발 가능 범위를 10~60분으로 바꾸면 매칭과 지연이 어떻게 변하는가.

서비스는 범위를 0~60분(5분 단위, 기본 60분)에서 학생이 고르게 한다. C7~C13 모의실험은 모두 20분을 가정했다.
2층의 캠퍼스별 요청·경로를 그대로 두고 예약 요청의 범위만 바꾼다(CURRENT = 20분). 즉시 요청·요금·매칭 조건은 같다.
"""
from __future__ import annotations
import json, statistics, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import departure_slots as ds
import run

HERE = Path(__file__).resolve().parent
CACHE = HERE / '.cache' / 'c13' / 'window'
REPORTS = HERE / 'reports'
WIDTHS = (10, 20, 30, 45, 60)
POLICIES = tuple('CURRENT' if w == 20 else f'FLEX{w}' for w in WIDTHS)


def main():
    bias, margin = c12.fare_config()
    command, provenance = c12.compile_current()
    import metro_campuses as m
    layer2 = [r for r in json.loads((ds.METRO / 'results.json').read_text(encoding='utf-8')) if 'results' in r]
    results = []
    for r in layer2:
        cid = m.campus_id(dict(name=r['campus'], branch=r['branch']))
        path = CACHE / cid / 'result.json'
        if path.exists():
            results.append(json.loads(path.read_text(encoding='utf-8'))); continue
        res = ds.simulate(command, ds.METRO / cid, CACHE / cid, bias, margin, POLICIES)
        x = dict(campus=r['campus'], branch=r['branch'], region=r['region'], mean_solo_km=r['mean_solo_km'],
                 observed_level=r['observed_level'], layer2=r['results'], results=res)
        path.write_text(json.dumps(x, ensure_ascii=False), encoding='utf-8'); results.append(x)
        print('done', r['campus'], r['branch'], {p: round(v['35']['match_rate'], 3) for p, v in res.items()}, flush=True)
    run.json_write(CACHE / 'results.json', dict(widths=WIDTHS, bias=bias, margin=margin, provenance=provenance, campuses=results))
    report(results)


def report(rs):
    pct = ds.pct
    med = lambda xs: statistics.median([x for x in xs if x is not None])
    def v(r, pol, n): return r['results'][pol][str(n)]
    def obs(r, pol): return r['observed_level'] and r['results'][pol].get(str(r['observed_level']))
    dj = next((r for r in rs if r['campus'] == '대진대학교'), None)
    head = '| | ' + ' | '.join(f'{w}분' for w in WIDTHS) + ' |'; rule = '|---|' + '---:|' * len(WIDTHS)
    L = ['# C13 3층 후속: 출발 가능 범위 곡선', '',
         '서비스는 예약 때 출발 가능 범위를 0~60분(5분 단위, 기본 60분)에서 학생이 고른다. C7~C13 모의실험은 모두 20분을 가정했다.',
         f'2층의 수도권 캠퍼스 {len(rs)}곳 요청·경로(생활이동 2026-10, TIMS 2025, OSM)를 그대로 두고 예약 요청의 범위만 바꿔 같은 10일을 다시 돌렸다. 즉시 요청·요금·매칭 조건(지연 15분, 절약 10%)은 같다.', '',
         f'## 매칭률(캠퍼스 {len(rs)}곳 중앙값)', '', head.replace('| |', '| 하루 요청 |'), rule]
    for n in (35, 70, 140):
        L.append(f'| {n}건 | ' + ' | '.join(pct(med([v(r, p, n)['match_rate'] for r in rs])) for p in POLICIES) + ' |')
    L.append('| 학생 수준 | ' + ' | '.join(pct(med([obs(r, p)['match_rate'] for r in rs if obs(r, p)])) for p in POLICIES) + ' |')
    L += ['', '## 하루 35건에서 얻는 것과 치르는 것(중앙값)', '', head, rule,
          '| 요청자 전체 1인 절약 | ' + ' | '.join(pct(med([v(r, p, 35)['requester_saving'] for r in rs])) for p in POLICIES) + ' |',
          '| 매칭자 1인 절약 | ' + ' | '.join(pct(med([v(r, p, 35)['mean_saving_matched'] for r in rs])) for p in POLICIES) + ' |',
          '| 가장 이른 출발에서 밀린 시간(매칭된 예약) | ' + ' | '.join(f"{med([v(r, p, 35)['mean_shift'] for r in rs]):.1f}분" for p in POLICIES) + ' |',
          '| 요청부터 확정까지 기다림 | ' + ' | '.join(f"{med([v(r, p, 35)['mean_wait'] for r in rs]):.1f}분" for p in POLICIES) + ' |', '']
    m = {p: med([v(r, p, 35)['match_rate'] for r in rs]) for p in POLICIES}
    steps = [f"{a}→{b}분 {100 * (m[q] - m[p]):+.0f}%p" for (a, p), (b, q) in zip(zip(WIDTHS, POLICIES), list(zip(WIDTHS, POLICIES))[1:])]
    mono = sum(all(v(r, q, 35)['match_rate'] >= v(r, p, 35)['match_rate'] - .02 for p, q in zip(POLICIES, POLICIES[1:])) for r in rs)
    L += ['## 요약', '',
          '- 35건에서 범위를 넓힐 때 매칭률 중앙값 변화: ' + ', '.join(steps) + '.',
          f'- 범위가 넓을수록 매칭률이 오르는(10일 표본 오차 2%p 안) 학교 {mono}곳 / {len(rs)}곳.',
          '- 지금까지 보고한 C12·C13 매칭률은 모든 학생이 20분만 기다린다는 가정이라, 기본값(60분)을 그대로 두는 학생이 많다면 실제는 이 표의 오른쪽에 가깝다.']
    if dj:
        L.append('- 대진대 35건: ' + ', '.join(f"{w}분 {pct(v(dj, p, 35)['match_rate'])}" for w, p in zip(WIDTHS, POLICIES))
                 + f"; 학생 수준 {dj['observed_level']}건: " + ', '.join(f"{w}분 {pct(obs(dj, p)['match_rate'])}" for w, p in zip(WIDTHS, POLICIES)) + '.')
    L += ['', '## 학교별(하루 35건 매칭률)', '', '| 학교 | 평균 혼자 이동 | ' + ' | '.join(f'{w}분' for w in WIDTHS) + ' |', '|---|---:|' + '---:|' * len(WIDTHS)]
    for r in sorted(rs, key=lambda r: -v(r, 'CURRENT', 35)['match_rate']):
        L.append(f"| {r['campus']}{'' if r['branch'] == '본교' else ' ' + r['branch']} | {r['mean_solo_km']:.1f}km | "
                 + ' | '.join(pct(v(r, p, 35)['match_rate']) for p in POLICIES) + ' |')
    L += ['', '## 한계', '',
          '- 모든 예약 요청이 같은 범위를 고른다고 놓았다. 실제 학생은 서로 다른 범위를 고르고, 그 분포는 운영 기록이 있어야 안다.',
          '- 범위가 넓으면 그만큼 늦게 떠날 수 있다는 뜻이라, 밀린 시간이 받아들일 만한지는 학생이 정한다.',
          '- 2층과 같은 요청·경로·10일 축소 실행이라 학교별 값의 오차가 C12(30일)보다 크다.']
    (REPORTS / 'c13-window-curve.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    run.json_write(REPORTS / 'c13-window-curve.json', dict(widths=WIDTHS, policies=POLICIES, campuses=rs))


if __name__ == '__main__':
    main()
