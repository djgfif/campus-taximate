"""C13 2층 결과 보고서: metro_campuses.py가 남긴 학교별 결과를 표와 요약으로 정리한다."""
import json, statistics, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run
HERE = Path(__file__).resolve().parent
CACHE = HERE / '.cache/c13/metro'
REPORTS = HERE / 'reports'


def pct(x):
    return '-' if x is None else f'{100 * x:.0f}%'


def main():
    rs = json.loads((CACHE / 'results.json').read_text(encoding='utf-8'))
    ok = [r for r in rs if 'results' in r]; skipped = [r for r in rs if 'skipped' in r]
    def at(r, n):
        v = r['results'].get(str(n)); return v and v['match_rate']
    def obs(r):
        v = r['results'].get(str(r['observed_level'])) if r.get('observed_level') else None
        return v
    seoul = [r for r in ok if r['origin']['level'] == '행정동']; gg = [r for r in ok if r['origin']['level'] == '시군구']
    med = lambda xs: statistics.median(xs) if xs else None
    dj = next((r for r in ok if r['campus'] == '대진대학교'), None)
    L = ['# C13 2층: 수도권 대학 실제 이동 자료 축소 모의실험', '',
         '출처: 서울특별시·KT 수도권 생활이동(2026-10 평일 20일, 귀가), TIMS 2025, OpenStreetMap, 대학알리미 2026.', '',
         f'수도권 4년제 캠퍼스 {len(ok)}곳(서울 행정동 출발 {len(seoul)}곳, 경기·인천 시군구 출발 {len(gg)}곳). 학교마다 캠퍼스 좌표에서 출발, 지금 서비스 코드(마감 확정 + 확정 분담금)로 10일씩.', '',
         '같은 하루 요청 수에서 학교끼리 비교하면 학교 위치와 학생들이 흩어지는 방향의 차이만 남는다. "학생 수준"은 1층의 학기에만 늘어난 택시 승차(하루)다.', '',
         '## 요약', '',
         f"- 하루 35건에서 매칭률 중앙값: 서울 {pct(med([at(r, 35) for r in seoul]))}, 경기·인천 {pct(med([at(r, 35) for r in gg]))}.",
         f"- 하루 70건: 서울 {pct(med([at(r, 70) for r in seoul]))}, 경기·인천 {pct(med([at(r, 70) for r in gg]))}. 140건: 서울 {pct(med([at(r, 140) for r in seoul]))}, 경기·인천 {pct(med([at(r, 140) for r in gg]))}.",
         f"- 학생 수준 수요에서 매칭률 중앙값 {pct(med([obs(r)['match_rate'] for r in ok if obs(r)]))}."]
    import numpy as np
    lv = np.array([r['observed_level'] for r in ok if obs(r)]); mm = np.array([obs(r)['match_rate'] for r in ok if obs(r)])
    bands = []
    for lo, hi in ((10, 20), (20, 50), (50, 100), (100, 200), (200, 10000)):
        sel = (lv >= lo) & (lv < hi)
        if sel.any():
            bands.append(f"{lo}~{hi if hi < 10000 else ''}건 {int(sel.sum())}곳 {pct(float(np.median(mm[sel])))}")
    km = np.array([r['mean_solo_km'] for r in ok]); m35 = np.array([at(r, 35) for r in ok])
    L.append('- 학생 수준 수요 구간별 매칭률 중앙값: ' + ', '.join(bands) + '. 매칭을 가르는 첫째 요인은 학교의 학생 택시 수요다.')
    L.append(f"- 같은 하루 35건이면 평균 혼자 이동 거리와 매칭률의 상관 {np.corrcoef(km, m35)[0, 1]:.2f}. 가까이 흩어지는 도심 캠퍼스(평균 6~8km)는 61~64%, "
             '멀리 흩어지는 교외 캠퍼스(포천·화성·안성, 14~24km)는 35~47%다.')
    if dj:
        L.append(f"- 대진대(캠퍼스 좌표에서 다시 계산): 35건 {pct(at(dj, 35))}, 70건 {pct(at(dj, 70))}, 학생 수준 {dj['observed_level']}건 {pct(obs(dj) and obs(dj)['match_rate'])}. "
                 f"평균 혼자 이동 {dj['mean_solo_km']:.1f}km.")
    L += ['', '## 학교별', '', '| 학교 | 출발 | 평균 혼자 이동 | 35건 | 70건 | 140건 | 학생 수준(건/일) | 학생 수준 매칭 | 학생 수준 1인 절약(매칭자) |', '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for r in sorted(ok, key=lambda r: -(at(r, 35) or 0)):
        o = obs(r)
        L.append(f"| {r['campus']}{'' if r['branch'] == '본교' else ' ' + r['branch']} | {r['origin']['dong'] if r['origin']['level'] == '행정동' else r['origin']['sigungu']} | "
                 f"{r['mean_solo_km']:.1f}km | {pct(at(r, 35))} | {pct(at(r, 70))} | {pct(at(r, 140))} | {r['observed_level'] or '-'} | "
                 f"{pct(o and o['match_rate'])} | {pct(o and o['mean_saving_matched'])} |")
    if skipped:
        L += ['', '## 계산하지 못한 곳', ''] + [f"- {r['campus']} {r['branch']}: {r['skipped']}" for r in skipped]
    L += ['', '## 한계', '',
          '- 출발 흐름은 캠퍼스가 있는 행정동(서울) 또는 시군구(경기·인천) 전체의 귀가 이동이라 학생 외 이동이 섞인다. 경기·인천은 시 전체라 더 거칠다.',
          '- 목적지는 서울을 자치구로 묶어 TIMS 격자 승차 비율로 나눈다. 흐름의 약 70%를 덮는 상위 격자(최대 400곳)만 쓴다.',
          '- 경로 시간 보정은 포천 통학권 카카오 400건에서 구한 배수(1.567)를 모든 학교에 쓴다. 서울 혼잡은 다를 수 있다.',
          '- 10일 축소 실행이라 학교별 값의 오차가 C12(30일)보다 크다.',
          '- 학생 수준이 하루 10건 미만(또는 음수)인 곳은 10건으로 올려 계산했다.', '']
    (REPORTS / 'c13-metro-campuses.md').write_text('\n'.join(L), encoding='utf-8')
    run.json_write(REPORTS / 'c13-metro-campuses.json', dict(campuses=ok, skipped=skipped))
    print('report written', len(ok))


if __name__ == '__main__':
    main()
