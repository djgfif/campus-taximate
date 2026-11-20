"""C12: 지금 서비스 그대로(마감 확정 S42 + 확정 분담금 S43)를 수요 곡선 위에서 다시 잰다.

- 매칭·검증·정산은 현재 체크아웃의 운영 코드(matching-engine, server)를 그대로 컴파일해 쓴다.
- 견적 보정과 여유분은 server/src/main/resources/application.yml의 app.fare 기본값을 읽는다.
- 수요는 C10과 같은 생성기(같은 씨앗)로 하루 15~400건, 30일. 100건은 C10과 같은 요청 흐름이다.
- 학교 앞 실제 택시 이용량(TIMS 2025, 정문 반경 약 1.5km 격자 9칸)을 곡선 위 관측 구간으로 표시한다.
"""
from __future__ import annotations
import argparse, csv, json, os, re, statistics, subprocess, sys
from collections import Counter, defaultdict
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import detailed as c7
import scale_and_prepay as c10
import fixed_share_and_timing as c11
import run

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = HERE / '.cache' / 'c12'
REPORTS = HERE / 'reports'
LEVELS = (15, 35, 70, 100, 200, 400)
SPREADS = (.5, 1., 1.5, 2.)
DAYS = 30
SEED = c10.SEED
CAMPUS = (127.1561, 37.8707)  # 시연·실험 공통 허브(정문)
SEMESTER_MONTHS = ('2025-03', '2025-04', '2025-05', '2025-09', '2025-10', '2025-11')
VACATION_MONTHS = ('2025-01', '2025-07', '2025-08')
ATTRIBUTION = c11.ATTRIBUTION


def fare_config(path=ROOT / 'server/src/main/resources/application.yml'):
    """Read app.fare defaults: quote-bias: ${FARE_QUOTE_BIAS:0.0657}."""
    text = path.read_text(encoding='utf-8')
    def value(key):
        m = re.search(key + r':\s*\$\{[A-Z_]+:([0-9.]+)\}', text)
        if not m:
            raise ValueError('app.fare.' + key + ' missing')
        return float(m.group(1))
    return value('quote-bias'), value('quote-margin')


def tims_grid(lon, lat):
    from pyproj import Transformer
    letters = '가나다라마바사아자차카타파하'
    e, n = Transformer.from_crs(4326, 5179, always_xy=True).transform(lon, lat)
    def code(e, n):
        a, b = int((e - 700000) // 100000), int((n - 1300000) // 100000)
        col, row = int(((e - 700000) % 100000) // 500), int(((n - 1300000) % 100000) // 500)
        return f'{letters[a]}{letters[b]}{col:03d}{row:03d}'
    return code(e, n), {code(e + dx * 500, n + dy * 500) for dx in (-1, 0, 1) for dy in (-1, 0, 1)}


def observed_campus_demand(path=HERE / '.cache/tims-monthly-safe.csv'):
    """Taxi trips per day around the campus gate, by month, from TIMS 2025 grid-month counts."""
    center, near = tims_grid(*CAMPUS)
    by_month = Counter()
    with path.open(encoding='utf-8') as f:
        for r in csv.DictReader(f):
            if r['grid'] in near:
                by_month[r['month']] += int(r['trips'])
    days = {f'2025-{m:02d}': d for m, d in zip(range(1, 13), (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31))}
    per_day = {m: by_month[m] / days[m] for m in sorted(days)}
    semester = statistics.mean(per_day[m] for m in SEMESTER_MONTHS)
    vacation = statistics.mean(per_day[m] for m in VACATION_MONTHS)
    return dict(center_grid=center, grids=sorted(near), per_day_by_month=per_day, year_per_day=sum(by_month.values()) / 365,
                semester_per_day=semester, vacation_per_day=vacation, source_sha256=run.sha(path),
                meaning='TIMS 2025 영업건수(승차 기준)의 정문 반경 약 1.5km 격자 9칸 합. 학생만이 아닌 모든 택시 이용이며, 앱 요청 수로 바꾸는 비율은 가정하지 않는다.')


def compile_current():
    """Compile every production source of this checkout with the experiment adapters (no frozen-hash guard)."""
    CACHE.mkdir(parents=True, exist_ok=True)
    java0 = json.loads((HERE / '.cache/c7/java-command.json').read_text(encoding='utf-8'))
    java = Path(java0[0]); deps = HERE / '.cache' / 'deps'
    libraries = sorted(deps.glob('*.jar')); lombok = next(p for p in libraries if p.name.startswith('lombok-'))
    production = sorted((ROOT / 'matching-engine/src/main/java').rglob('*.java')) + sorted((ROOT / 'server/src/main/java').rglob('*.java'))
    adapters = sorted((HERE / 'java').rglob('*.java'))
    classes = CACHE / 'classes'; classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, libraries))
    args = ['-source', '21', '-target', '21', '-encoding', 'UTF-8', '-cp', classpath, '-processorpath', str(lombok), '-d', str(classes),
            *map(str, production + adapters)]
    argfile = CACHE / 'build.args'; argfile.write_text('\n'.join('"' + v.replace('\\', '/') + '"' for v in args), encoding='utf-8')
    with (CACHE / 'build.log').open('w', encoding='utf-8') as log:
        subprocess.run([str(java.parent / 'javac'), '@' + str(argfile)], cwd=ROOT, stdout=log, stderr=log, check=True)
    command = [str(java), '-Xmx2048m', '-cp', str(classes) + os.pathsep + classpath]
    head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(['git', 'status', '--porcelain', '--', 'matching-engine/src/main', 'server/src/main'], cwd=ROOT,
                           capture_output=True, text=True).stdout.strip()
    provenance = dict(git_head=head, production_dirty=bool(dirty),
                      production_source_sha256={p.relative_to(ROOT).as_posix(): run.sha(p) for p in production},
                      adapter_source_sha256={p.relative_to(ROOT).as_posix(): run.sha(p) for p in adapters})
    for cls in ('C11Simulation', 'C11Finance'):
        subprocess.run(command + ['com.campustaximate.server.service.' + cls, '--self-test'], cwd=ROOT, check=True,
                       stdout=subprocess.DEVNULL)
    run.json_write(CACHE / 'provenance.json', provenance)
    return command, provenance


def cases():
    hub, points, hours, header, demand = c7.demand_inputs(None, HERE / '.cache', HERE / '.cache/osrm')
    errors = [float(x) for x in (HERE / '.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    ss = [replace(c7.Scenario(), name=f'n{n}', requests=n) for n in LEVELS]
    meta = c7.write_cases(CACHE / 'cases.tsv', ss, DAYS, SEED, points, hours, header, errors)
    run.json_write(CACHE / 'case-meta.json', meta)
    return demand


def simulate(command, bias, margin):
    with (CACHE / 'sim.log').open('w', encoding='utf-8') as log:
        subprocess.run(command + ['com.campustaximate.server.service.C11Simulation', str(CACHE / 'cases.tsv'), str(HERE / '.cache/osrm/matrix.tsv'),
                                  str(HERE / '.cache/kr-public/tims-errors.csv'), str(CACHE / 'groups.tsv'), str(CACHE / 'days.csv'), '-', str(bias),
                                  'CURRENT', str(margin)], cwd=ROOT, check=True, stdout=log, stderr=log)


def n_of(cid):
    return int(cid.split('_day')[0][1:])


def finance(command, bias, margin):
    groups = c11.read_tsv(CACHE / 'groups.tsv')
    lines = ['source\tn\tday\tgroup_id\tconfirm\tfinish\traw_quote\terror_index\tunits\tsolo_errors\tmargin']
    for g in groups:
        lines.append('\t'.join(map(str, ['CURRENT', n_of(g['case_id']), int(g['case_id'].split('_day')[1]), g['group_id'], g['confirm'], g['finish'],
                                         g['raw_quote'], g['error_index'], g['units'], g['solo_errors'], margin])))
    (CACHE / 'finance-input.tsv').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    subprocess.run(command + ['com.campustaximate.server.service.C11Finance', '--fixed', str(CACHE / 'finance-input.tsv'),
                              str(HERE / '.cache/kr-public/tims-errors.csv'), str(bias), str(CACHE / 'fixed.csv')], cwd=ROOT, check=True,
                   stdout=subprocess.DEVNULL)
    return groups, c7.read_csv(CACHE / 'fixed.csv')


def summarize(groups, days, fixed):
    paths = np.random.default_rng(SEED + 12).integers(0, DAYS, size=(c11.PATHS, c11.SEMESTER))
    run.json_write(CACHE / 'semester-paths.json', paths.tolist())
    gg = defaultdict(list)
    for g in groups:
        gg[g['case_id']].append(g)
    out = []
    for n in LEVELS:
        dd = sorted([d for d in days if n_of(d['case_id']) == n], key=lambda d: d['case_id'])
        if len(dd) != DAYS:
            raise ValueError('Missing C12 days')
        daily = []
        for d in dd:
            rr = gg[d['case_id']]; matched = int(d['matched'])
            waits = [x for g in rr for x in c11.split_numbers(g['waits'])]; delays = [x for g in rr for x in c11.split_numbers(g['delays'])]
            sizes = [len(c11.split_numbers(g['units'])) for g in rr]
            daily.append(dict(match_rate=matched / int(d['requests']), mean_wait=statistics.mean(waits) if waits else None,
                              mean_delay=statistics.mean(delays) if delays else None, groups=len(rr),
                              mean_group_size=statistics.mean(sizes) if sizes else None,
                              parity=int(d['production_parity_groups']), warnings=int(d['budget_warnings'])))
        level = dict(requests=n, matching={k: c10.ci([x[k] for x in daily]) for k in ('match_rate', 'mean_wait', 'mean_delay', 'mean_group_size')},
                     production_parity_groups=sum(x['parity'] for x in daily), budget_warnings=sum(x['warnings'] for x in daily), money={})
        for sp in SPREADS:
            rows = [r for r in fixed if int(r['n']) == n and abs(float(r['spread']) - sp) < 1e-9]
            per = []; totals = []; minima = []
            for day in range(DAYS):
                rr = sorted([r for r in rows if int(r['day']) == day], key=lambda r: (int(r['finish']), int(r['group_id'])))
                people = sum(int(r['size']) for r in rr)
                traj = c11.reserve_recursion([int(r['reserve_delta']) for r in rr])
                totals.append(int(traj[-1])); minima.append(int(traj.min()))
                if people:
                    per.append(dict(saving=sum(float(r['fixed_saving_actual_sum']) for r in rr) / people,
                                    saving_krw=sum(int(r['fixed_saving_krw']) for r in rr) / people,
                                    harmed_vs_solo_meter=sum(int(r['fixed_harmed_actual']) for r in rr) / people,
                                    harmed_vs_quote=sum(int(r['fixed_harmed_quote']) for r in rr) / people,
                                    extra=sum(int(r['extra']) for r in rr), refund=sum(int(r['refund']) for r in rr),
                                    conservation=sum(int(r['conservation_errors']) for r in rr),
                                    exception_rate=sum(int(r['exceptional']) for r in rr) / len(rr)))
            stats, _ = c11.path_statistics(totals, minima, paths)
            stats.pop('trajectory', None)
            level['money'][str(sp)] = dict({k: c10.ci([x[k] for x in per]) for k in per[0]}, reserve=stats,
                                           extra_total=sum(x['extra'] for x in per), refund_total=sum(x['refund'] for x in per),
                                           conservation_total=sum(x['conservation'] for x in per))
        out.append(level)
    return out


def pct(x):
    return f'{100 * x:.1f}%'


def report(levels, observed, bias, margin, provenance):
    L = ['# C12 지금 서비스 그대로: 수요에 따른 매칭과 확정 분담금', '', ATTRIBUTION, '',
         f'운영 코드 {provenance["git_head"][:7]}{" (작업 중 변경 있음)" if provenance["production_dirty"] else ""}를 그대로 컴파일했다. '
         f'매칭은 모집 마감까지 모아 확정(S42), 요금은 확정 분담금(S43, 견적 보정 {100 * bias:.2f}% + 여유 {100 * margin:.0f}%).', '',
         '## 학교 앞 실제 택시 이용량 (관측)', '',
         f'TIMS 2025 영업건수, 정문 반경 약 1.5km 격자 9칸: 학기 중 하루 {observed["semester_per_day"]:.1f}건, '
         f'방학 하루 {observed["vacation_per_day"]:.1f}건, 연평균 {observed["year_per_day"]:.1f}건.', '',
         observed['meaning'], '',
         '## 수요에 따른 결과 (오차 1배, 30일 평균, 괄호는 95% CI)', '',
         '| 하루 요청 | 매칭률 | 1인 절약 | 1인 절약액 | 평균 지연(분) | 확정까지(분) | 더 낸 사람 | 환불 |', '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for lv in levels:
        m = lv['matching']; f = lv['money']['1.0']
        L.append(f"| {lv['requests']} | {pct(m['match_rate']['mean'])} [{pct(m['match_rate']['ci95_low'])}, {pct(m['match_rate']['ci95_high'])}] | "
                 f"{pct(f['saving']['mean'])} | {f['saving_krw']['mean']:,.0f}원 | {m['mean_delay']['mean']:.1f} | {m['mean_wait']['mean']:.1f} | "
                 f"{f['extra_total']} | {f['refund_total']} |")
    L += ['', '- 더 낸 사람·환불은 일행 전체에서 센 건수다. 확정 분담금이라 0이어야 한다.',
          '- 1인 절약은 실제 미터 요금이 붙은 혼자 탈 때 요금과 비교한 값이다.', '',
          '## 정산 적립금 (한 학기 80일, 2,000경로)', '',
          '| 하루 요청 | 오차 배수 | 학기 말 평균 | 99% 초기 기금 | 95% 초기 기금 | 확정 금액 1.5배 초과(관리자 확인) |', '|---:|---:|---:|---:|---:|---:|']
    for lv in levels:
        for sp in SPREADS:
            f = lv['money'][str(sp)]; r = f['reserve']
            L.append(f"| {lv['requests']} | {sp:g} | {r['end_mean']:,.0f}원 | {r['seed99']:,}원 | {r['seed95']:,}원 | {pct(f['exception_rate']['mean'])} |")
    L += ['', '## 가정과 한계', '',
          '- 요청 수는 시나리오다. 관측된 학교 앞 택시 이용량을 곡선 위에 표시할 뿐, 앱 이용 비율은 가정하지 않는다.',
          '- 요금 오차는 TIMS 격자·월 평균 잔차를 대리값으로 쓴다(1·1.5·2배). 확정 분담금에서는 오차가 이용자 부담이 아니라 적립금 기금 크기를 바꾼다.',
          '- 수요·목적지·경로는 C7과 같다(생활이동 2026-10, TIMS 격자, OSRM + 카카오 400건 보정).',
          f'- 운영 검증기 재실행 일치 그룹 {sum(l["production_parity_groups"] for l in levels):,}개, 1초 탐색 한도 경고 {sum(l["budget_warnings"] for l in levels)}회.', '']
    (REPORTS / 'c12-current-service.md').write_text('\n'.join(L), encoding='utf-8')
    run.json_write(REPORTS / 'c12-current-service.json', dict(generated_at=datetime.now(timezone.utc).isoformat(), bias=bias, margin=margin,
        levels=levels, observed_campus_demand=observed, provenance=dict(git_head=provenance['git_head'], production_dirty=provenance['production_dirty']),
        attribution=ATTRIBUTION, artifacts={p.relative_to(ROOT).as_posix(): run.sha(p) for p in sorted(CACHE.glob('*'))
                                             if p.is_file() and p.suffix in ('.tsv', '.csv', '.json') and p.stat().st_size < 80_000_000}))


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    bias, margin = fare_config()
    command, provenance = compile_current()
    cases()
    simulate(command, bias, margin)
    groups, fixed = finance(command, bias, margin)
    days = c7.read_csv(CACHE / 'days.csv')
    levels = summarize(groups, days, fixed)
    observed = observed_campus_demand()
    report(levels, observed, bias, margin, provenance)
    print('C12 done', flush=True)


if __name__ == '__main__':
    main()
