"""C13 2층 검증: 학교마다 줄인 목적지(최대 800격자)가 전체 격자 계산(C12, 대진대 4,453격자)과 얼마나 다른가.

대진대 하나를 2층 방식으로 만든 목적지·경로로 C12와 같은 수요(15~400건)·같은 30일을 돌려 C12 결과와 비교한다.
요청 생성 규칙과 서비스 코드는 같고 다른 것은 목적지 격자 수뿐이라, 차이가 곧 2층이 학교마다 갖는 표현 오차다.
"""
from __future__ import annotations
import json, shutil, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import metro_campuses as m
import run

HERE = Path(__file__).resolve().parent
OUT = HERE / '.cache' / 'c13' / 'validate'
REPORTS = HERE / 'reports'


def main():
    cid = m.campus_id(dict(name='대진대학교', branch='본교')); src = m.CACHE / cid
    OUT.mkdir(parents=True, exist_ok=True); shutil.copy(src / 'matrix.tsv', OUT / 'matrix.tsv')
    data = json.loads((src / 'destinations.json').read_text(encoding='utf-8'))
    errors = [float(x) for x in (HERE / '.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    import campus_survey as c13
    m.DAYS = c12.DAYS; m.cases_for(OUT.resolve(), data, errors, c12.LEVELS, c13.male_shares()[('대진대학교', '본교')])
    bias, margin = c12.fare_config(); command, _ = c12.compile_current()
    reduced = m.simulate(command, OUT.resolve(), bias, margin)
    full = {str(l['requests']): l['matching']['match_rate'] for l in
            json.loads((REPORTS / 'c12-current-service.json').read_text(encoding='utf-8'))['levels']}
    rows = [dict(requests=int(n), reduced=reduced[n]['match_rate'], full=full[n]['mean'], full_ci=[full[n]['ci95_low'], full[n]['ci95_high']],
                 diff=reduced[n]['match_rate'] - full[n]['mean']) for n in reduced]
    full_km = c12_mean_km()
    reduced_km = sum(p['probability'] * p['solo_km'] for p in data['points'])
    L = ['# C13 2층 검증: 줄인 목적지와 전체 격자의 차이(대진대)', '',
         f"2층은 학교마다 목적지를 최대 {m.MAX_GRIDS}격자로 줄인다(시군구마다 대표 격자, 고르지 않은 격자의 흐름은 같은 시군구의 가까운 격자로). "
         f"대진대는 C12가 전체 격자로 계산해 두었으므로, 같은 요청 규칙·같은 서비스 코드·같은 30일로 두 결과를 비교한다.", '',
         f"- 목적지: 줄인 쪽 {len(data['points'])}격자(귀가 흐름의 {100 * data['coverage']['represented_flow_share']:.0f}%), 전체 C12 격자. "
         f"평균 혼자 이동(흐름 가중) {reduced_km:.1f}km / {full_km:.1f}km.", '',
         '| 하루 요청 | 줄인 목적지 | 전체 격자(C12) | C12 95% CI | 차이 |', '|---:|---:|---:|---:|---:|']
    for r in rows:
        L.append(f"| {r['requests']} | {100 * r['reduced']:.1f}% | {100 * r['full']:.1f}% | {100 * r['full_ci'][0]:.1f}~{100 * r['full_ci'][1]:.1f}% | {100 * r['diff']:+.1f}%p |")
    mean_diff = sum(r['diff'] for r in rows) / len(rows)
    L += ['', f"- 차이 평균 {100 * mean_diff:+.1f}%p. 격자를 줄이면 목적지가 겹치기 쉬워 매칭이 조금 높게 나온다. 2층·3층 학교별 값은 이만큼 낙관적일 수 있다.",
          '- 11/20 이전 2층은 무게 상위 격자만 남기고 다시 나눠(대진대 흐름의 67%, 평균 혼자 이동 13.6km) 같은 비교에서 +4~10%p였다. 이 방식으로 바꿔 다시 계산했다.']
    (REPORTS / 'c13-representation-check.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    run.json_write(REPORTS / 'c13-representation-check.json', dict(max_grids=m.MAX_GRIDS, reduced_km=reduced_km, full_km=full_km, rows=rows, mean_diff=mean_diff))
    print('\n'.join(L))


def c12_mean_km():
    import detailed as c7
    _, pts, _, _, _ = c7.demand_inputs(None, HERE / '.cache', c12.ROUTING)
    w = sum(p['weight'] for p in pts)
    return sum(p['weight'] * p['km'] for p in pts) / w


if __name__ == '__main__':
    main()
