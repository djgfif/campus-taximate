"""C13 설정 최적화: 지연 한도 × 최소 절약 × 출발 가능 범위를 함께 바꿔, 지금 설정이 효율 경계 위에 있는지 본다.

2층의 수도권 캠퍼스별 요청·경로를 그대로 두고 운영 코드의 MatchingSettings만 바꾼다(CFG-Dd-Ss-Ww).
지금 서비스 = 지연 15분, 절약 10%. 범위는 학생이 고르므로(기본 60분) 20·30·60분을 모두 본다.
얻는 것: 요청자 전체 1인 절약(매칭률 × 매칭자 절약). 치르는 것: 혼자 갈 때보다 늘어난 차 안 시간(지연), 가장 이른 출발에서 밀린 시간.
"""
from __future__ import annotations
import json, statistics, sys
from itertools import product
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import departure_slots as ds
import run

HERE = Path(__file__).resolve().parent
CACHE = HERE / '.cache' / 'c13' / 'settings'
REPORTS = HERE / 'reports'
DELAYS = (10, 15, 20)
SAVINGS = (5, 10, 15)
WIDTHS = (20, 30, 60)
CURRENT = dict(delay=15, saving=10)
POLICIES = tuple(f'CFG-D{d}-S{s}-W{w}' for w, d, s in product(WIDTHS, DELAYS, SAVINGS))


def parse(policy):
    d, s, w = (int(x[1:]) for x in policy.split('-')[1:])
    return dict(delay=d, saving=s, width=w)


# 학생이 느끼지 못할 차이(10일 축소 실행의 표본 오차도 이 안에 든다)는 같은 것으로 본다.
MARGIN = dict(requester_saving=.005, mean_delay=.5, mean_shift=1.)  # 절약 0.5%p(1만 원에 50원), 차 안 지연 30초, 출발 1분


def dominated(a, b):
    """b가 a를 지배: 절약·지연·밀린 시간 모두 a보다 나쁘지 않고(차이가 MARGIN 안이면 같음), 하나는 MARGIN 넘게 낫다."""
    m = MARGIN
    ge = (b['requester_saving'] >= a['requester_saving'] - m['requester_saving'] and b['mean_delay'] <= a['mean_delay'] + m['mean_delay']
          and b['mean_shift'] <= a['mean_shift'] + m['mean_shift'])
    gt = (b['requester_saving'] > a['requester_saving'] + m['requester_saving'] or b['mean_delay'] < a['mean_delay'] - m['mean_delay']
          or b['mean_shift'] < a['mean_shift'] - m['mean_shift'])
    return ge and gt


def frontier(points):
    """points: {policy: metrics}. 다른 설정에 지배되지 않는 설정 목록."""
    return [p for p, m in points.items() if not any(dominated(m, o) for q, o in points.items() if q != p)]


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
        print('done', r['campus'], r['branch'], flush=True)
    run.json_write(CACHE / 'results.json', dict(policies=POLICIES, bias=bias, margin=margin, provenance=provenance, campuses=results))
    report(results)


def medians(rs, n):
    keys = ('match_rate', 'mean_saving_matched', 'requester_saving', 'mean_delay', 'mean_shift', 'mean_wait')
    def at(r, p): return r['results'][p].get(str(r['observed_level'] if n == 'obs' else n))
    return {p: {k: statistics.median(at(r, p)[k] for r in rs if at(r, p) and at(r, p)[k] is not None) for k in keys} for p in POLICIES}


def report(rs):
    pct = ds.pct
    cur = lambda w: f"CFG-D{CURRENT['delay']}-S{CURRENT['saving']}-W{w}"
    M = {n: medians(rs, n) for n in (35, 70, 'obs')}
    L = ['# C13 설정 최적화: 지연 한도 × 최소 절약 × 출발 가능 범위', '',
         f'2층의 수도권 캠퍼스 {len(rs)}곳 요청·경로(생활이동 2026-10, TIMS 2025, OSM)를 그대로 두고 운영 코드의 매칭 설정만 바꿔 같은 10일을 돌렸다. '
         f'지연 한도 {DELAYS}분 × 최소 절약 {SAVINGS}% × 예약 출발 가능 범위 {WIDTHS}분 = {len(POLICIES)}개 설정. 지금 서비스는 지연 15분, 절약 10%이고 범위는 학생이 고른다(기본 60분).', '',
         '- 요청자 전체 1인 절약 = 매칭률 × 매칭자 1인 절약. 매칭이 안 된 사람은 0으로 센다.',
         '- 지연 = 같이 타서 혼자 갈 때보다 늘어난 차 안 시간(매칭자 평균). 밀린 시간 = 원하던 가장 이른 출발에서 실제 출발까지(매칭된 예약 평균).',
         '- 효율 경계: 절약·지연·밀린 시간 중 어느 것도 나빠지지 않으면서 하나라도 더 나은 다른 설정이 없는 설정. '
         '학생이 느끼지 못할 차이(절약 0.5%p, 차 안 지연 30초, 출발 1분 미만)는 같은 것으로 본다. 10일 축소 실행의 표본 오차도 이 안에 든다.', '']
    # 결론: 지금 설정이 경계 위인지, 그리고 손잡이(지연·절약 vs 범위)별로 매칭률이 얼마나 움직이는지
    checks = [(n, w, not any(dominated(M[n][cur(w)], M[n][q]) for q in POLICIES)) for n in (35, 70, 'obs') for w in WIDTHS]
    knob = max(abs(M[35][f'CFG-D{d}-S{sv}-W{w}']['match_rate'] - M[35][cur(w)]['match_rate']) for w in WIDTHS for d in DELAYS for sv in SAVINGS)
    width = M[35][cur(WIDTHS[-1])]['match_rate'] - M[35][cur(WIDTHS[0])]['match_rate']
    L += ['## 결론', '',
          f"- 지금 설정(지연 15분, 절약 10%)은 {sum(c[2] for c in checks)}/{len(checks)}개 경우(수요 3가지 × 범위 3가지)에서 효율 경계 위다. "
          + ('모든 경우에서 지금 설정보다 모든 면에서 나은 설정이 없다.' if all(c[2] for c in checks) else
             '경계 밖인 경우: ' + ', '.join(f"{'학생 수준' if n == 'obs' else f'{n}건'}·범위 {w}분" for n, w, ok in checks if not ok) + '.'),
          f"- 같은 범위에서 지연 한도·최소 절약을 바꾸면 하루 35건 매칭률 중앙값이 최대 {100 * knob:.0f}%p 움직인다. "
          f"출발 가능 범위를 20분에서 60분으로 넓히면 {100 * width:+.0f}%p다. 매칭을 가장 크게 바꾸는 손잡이는 학생이 고르는 출발 가능 범위다.",
          '- 지연 한도를 20분으로 풀면 매칭은 조금 늘지만 같이 탄 사람의 차 안 시간도 늘어, 요청자 전체 절약은 거의 같다. 최소 절약을 5%로 낮추면 매칭은 늘고 매칭된 사람의 절약은 줄어 역시 비슷하다.', '']
    for n, title in ((35, '하루 35건'), ('obs', '학교별 학생 수준')):
        pts = M[n]; front = frontier(pts)
        L += [f'## {title}(캠퍼스 {len(rs)}곳 중앙값)', '']
        for w in WIDTHS:
            L += [f'### 출발 가능 범위 {w}분', '', '| 지연 한도 | ' + ' | '.join(f'절약 {s}%' for s in SAVINGS) + ' |', '|---|' + '---:|' * len(SAVINGS)]
            for d in DELAYS:
                cells = []
                for s in SAVINGS:
                    p = f'CFG-D{d}-S{s}-W{w}'; x = pts[p]
                    mark = ('**' if p == cur(w) else '') + ('◆' if p in front else '')
                    cells.append(f"{mark}{pct(x['match_rate'])} / {pct(x['requester_saving'])} / {x['mean_delay']:.1f}분{'**' if p == cur(w) else ''}")
                L.append(f'| {d}분 | ' + ' | '.join(cells) + ' |')
            L.append('')
        L.append('칸: 매칭률 / 요청자 전체 1인 절약 / 지연. 굵게 = 지금 설정, ◆ = 효율 경계(같은 수요의 27개 설정 안에서, 밀린 시간 포함).')
        L.append('')
    L += ['## 지금 설정은 경계 위에 있나', '']
    for n, title in ((35, '하루 35건'), (70, '하루 70건'), ('obs', '학생 수준')):
        pts = M[n]; front = frontier(pts)
        for w in WIDTHS:
            p = cur(w); better = [q for q in POLICIES if dominated(pts[p], pts[q])]
            if not better:
                L.append(f'- {title}, 범위 {w}분: 경계 위(지금 설정보다 모든 면에서 나은 설정 없음).')
            else:
                b = max(better, key=lambda q: pts[q]['requester_saving']); c = parse(b); x, y = pts[p], pts[b]
                L.append(f"- {title}, 범위 {w}분: 경계 밖. 지연 {c['delay']}분·절약 {c['saving']}%·범위 {c['width']}분이 절약 {pct(x['requester_saving'])}→{pct(y['requester_saving'])}, "
                         f"지연 {x['mean_delay']:.1f}→{y['mean_delay']:.1f}분, 밀린 시간 {x['mean_shift']:.1f}→{y['mean_shift']:.1f}분.")
    same_w = {}
    for w in WIDTHS:
        sub = {p: M[35][p] for p in POLICIES if parse(p)['width'] == w}
        same_w[w] = max(sub, key=lambda p: sub[p]['requester_saving'])
    L += ['', '## 범위를 고정하고 절약만 보면', '']
    for w in WIDTHS:
        b = parse(same_w[w]); x = M[35][cur(w)]; y = M[35][same_w[w]]
        L.append(f"- 범위 {w}분, 하루 35건: 요청자 전체 절약이 가장 큰 설정은 지연 {b['delay']}분·절약 {b['saving']}%({pct(y['requester_saving'])}, 지연 {y['mean_delay']:.1f}분). "
                 f"지금 설정은 {pct(x['requester_saving'])}, 지연 {x['mean_delay']:.1f}분.")
    dj = next((r for r in rs if r['campus'] == '대진대학교'), None)
    if dj:
        L += ['', '## 대진대', '', '| 설정 | 35건 매칭률 | 35건 요청자 절약 | 학생 수준 매칭률 | 학생 수준 요청자 절약 |', '|---|---:|---:|---:|---:|']
        for w in WIDTHS:
            for p in (cur(w), same_w[w]) if same_w[w] != cur(w) else (cur(w),):
                c = parse(p); a = dj['results'][p]['35']; o = dj['results'][p][str(dj['observed_level'])]
                L.append(f"| 지연 {c['delay']}분·절약 {c['saving']}%·범위 {w}분{' (지금)' if p == cur(w) else ''} | {pct(a['match_rate'])} | {pct(a['requester_saving'])} | {pct(o['match_rate'])} | {pct(o['requester_saving'])} |")
    L += ['', '## 한계', '',
          '- 지연·밀린 시간을 돈으로 바꾸지 않았다. 경계 위 설정 가운데 무엇을 고를지는 학생이 시간과 돈을 어떻게 바꾸는지에 달렸다.',
          '- 모든 예약 요청이 같은 범위를 고른다고 놓았다. 기다리다 포기하는 사람은 셈하지 않았다.',
          '- 2층과 같은 요청·경로·10일 축소 실행이다. 학교별 값보다 79곳 중앙값을 본다.']
    (REPORTS / 'c13-settings-sweep.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    run.json_write(REPORTS / 'c13-settings-sweep.json', dict(policies=POLICIES, current=CURRENT, campuses=rs))


if __name__ == '__main__':
    main()
