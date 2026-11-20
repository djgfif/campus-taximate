"""C14 성비 민감도: 택시를 부르는 학생의 남성 비율이 재학생 성비(대진대 56.2%)와 다르면 매칭률이 얼마나 바뀌나.

운영은 같은 성별끼리만 묶는다. 대진대의 2층 요청·경로(목적지 대표 격자, 10일)를 그대로 두고 요청자 성별만
남성 비율 0.3~0.7과 전원 한 성별(1.0, 옛 실험의 가정)로 바꿔 돌린다. 출발 가능 범위는 20분(지금 서비스 가정)과 60분(화면 기본값).
"""
from __future__ import annotations
import json, shutil, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import campus_survey as c13
import current_service as c12
import departure_slots as ds
import metro_campuses as m
import run

HERE = Path(__file__).resolve().parent
OUT = HERE / '.cache' / 'c14' / 'sex'
REPORTS = HERE / 'reports'
SHARES = (.3, .4, .5, .6, .7, 1.)
POLICIES = ('CURRENT', 'FLEX60')
LEVELS = (20, 35, 70)


def main():
    cid = m.campus_id(dict(name='대진대학교', branch='본교')); src = m.CACHE / cid
    data = json.loads((src / 'destinations.json').read_text(encoding='utf-8'))
    errors = [float(x) for x in (HERE / '.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    bias, margin = c12.fare_config(); command, provenance = c12.compile_current()
    enrolled = c13.male_shares()[('대진대학교', '본교')]
    res = {}
    for share in sorted(set(SHARES) | {round(enrolled, 3)}):
        out = (OUT / f'{share:.3f}').resolve(); out.mkdir(parents=True, exist_ok=True)
        shutil.copy(src / 'matrix.tsv', out / 'matrix.tsv')
        m.cases_for(out, data, errors, LEVELS, share)
        res[f'{share:.3f}'] = ds.simulate(command, out, out, bias, margin, POLICIES)
        print('share', share, {p: round(v['35']['match_rate'], 3) for p, v in res[f'{share:.3f}'].items()}, flush=True)
    run.json_write(REPORTS / 'c14-sex-sensitivity.json', dict(enrolled_male_share=enrolled, shares=sorted(res), policies=POLICIES, results=res))
    pct = ds.pct
    L = ['# C14 성비 민감도 (대진대)', '',
         f'운영은 같은 성별끼리만 묶는다. 대진대 재학생 남성 비율은 {100 * enrolled:.1f}%(대학알리미 2026)지만, 택시를 부르는 학생의 성비는 다를 수 있다. '
         '2층의 대진대 요청·경로(10일)를 그대로 두고 요청자 남성 비율만 바꿨다. 1.0은 전원 한 성별, 11/21 이전 실험의 가정이다.', '']
    for pol, name in (('CURRENT', '출발 가능 범위 20분'), ('FLEX60', '출발 가능 범위 60분(화면 기본값)')):
        L += [f'## {name}', '', '| 남성 비율 | ' + ' | '.join(f'하루 {n}건 매칭률' for n in LEVELS) + ' | 하루 35건 요청자 전체 1인 절약 |',
              '|---:|' + '---:|' * (len(LEVELS) + 1)]
        for k in sorted(res):
            r = res[k][pol]
            tag = ' (재학생 성비)' if abs(float(k) - enrolled) < 5e-4 else ' (전원 한 성별)' if k == '1.000' else ''
            L.append(f'| {100 * float(k):.0f}%{tag} | ' + ' | '.join(pct(r[str(n)]['match_rate']) for n in LEVELS) + f" | {pct(r['35']['requester_saving'])} |")
        L.append('')
    e = res[f'{round(enrolled, 3):.3f}']['CURRENT']['35']['match_rate']; one = res['1.000']['CURRENT']['35']['match_rate']
    rng = [res[k]['CURRENT']['35']['match_rate'] for k in res if .3 <= float(k) <= .7]
    L += ['## 요약', '',
          f'- 하루 35건·범위 20분: 전원 한 성별이면 {pct(one)}, 재학생 성비면 {pct(e)}. 성별 분리만으로 {100 * (one - e):.0f}%p 낮아진다.',
          f'- 남성 비율을 30~70% 사이에서 바꾸면 {pct(min(rng))}~{pct(max(rng))}. 택시 이용 성비가 재학생 성비와 달라도 결과는 거의 같고, 크게 바꾸는 것은 성별로 나뉜다는 사실 자체다.',
          '- 학교 하나의 요청이 성별로 나뉘면 사실상 요청 수가 절반인 학교 두 곳이 되는 셈이라, 같은 성별 매칭은 수요가 적은 학교일수록 크게 손해다.']
    (REPORTS / 'c14-sex-sensitivity.md').write_text('\n'.join(L) + '\n', encoding='utf-8')
    print('\n'.join(L[-4:]))


if __name__ == '__main__':
    main()
