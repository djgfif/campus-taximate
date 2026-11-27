"""C12: 지금 서비스 그대로(마감 확정 + v3 후불 1회 결제, 실효 여유분 0%)를 수요 곡선 위에서 다시 잰다.

- 매칭·검증·정산은 현재 체크아웃의 운영 코드(matching-engine, server)를 그대로 컴파일해 쓴다.
- 실효 여유분은 server/src/main/resources/application.yml의 payment.flow-version과 fare.quote-margin을 RouteConfig 규칙대로 읽는다. 견적 보정은 SIM_QUOTE_BIAS를 쓴다(fare_config 참고).
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
ROUTING = HERE / '.cache' / 'osrm-campus'  # 캠퍼스 정문에서 출발하는 경로(C7 캐시의 출발점은 포천 시내였다)
SEMESTER_MONTHS = ('2025-03', '2025-04', '2025-05', '2025-09', '2025-10', '2025-11')
VACATION_MONTHS = ('2025-01', '2025-07', '2025-08')
ATTRIBUTION = c11.ATTRIBUTION


# 실험의 견적은 카카오가 아니라 OSRM 경로에 포천 요금표 공식을 적용한 값이다. 그 견적이 실제 요금(TIMS 2025)보다
# 평균 6.57% 낮다는 것은 C5에서 확인했으므로 실험 견적에는 이 보정을 그대로 쓴다. 앱은 카카오 견적을 쓰고,
# 카카오 견적은 이미 실제보다 조금 높아(C16) 앱의 quote-bias는 0이다. 두 값은 견적 출처가 달라 따로 둔다.
SIM_QUOTE_BIAS = 0.0657


def fare_config(path=ROOT / 'server/src/main/resources/application.yml'):
    """(실험 견적 보정, 앱 실효 여유분). RouteConfig와 같이 flow-version >= 3이면 여유분은 0.0이다."""
    text = path.read_text(encoding='utf-8')
    def value(key):
        m = re.search(key + r':\s*\$\{[A-Z_]+:([0-9.]+)\}', text)
        if not m:
            raise ValueError('app.fare.' + key + ' missing')
        return float(m.group(1))
    fv = re.search(r'flow-version:\s*\$\{[A-Z_]+:(\d+)\}', text)
    flow_version = int(fv.group(1)) if fv else 2
    effective_margin = 0.0 if flow_version >= 3 else value('quote-margin')
    return SIM_QUOTE_BIAS, effective_margin


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


def ensure_routing():
    """C7 규칙 그대로 목적지 격자와 OSRM 행렬을 캠퍼스 정문 출발로 다시 만든다. 시간 보정은 C7 카카오 400건 모델을 그대로 쓴다."""
    import math, osrm_matrix as om
    from fares import korea_tariff
    manifest_path = ROUTING / 'MANIFEST.json'
    if manifest_path.exists() and json.loads(manifest_path.read_text(encoding='utf-8')).get('status') == 'CALIBRATED':
        return
    if not (ROUTING / 'raw_matrix.tsv').exists():
        om.build(cache=HERE / '.cache', output=ROUTING, url='http://127.0.0.1:5000', hub=om.CAMPUS_HUB, workers=4)
    model_path = HERE / '.cache/osrm/calibration-model.json'
    model = json.loads(model_path.read_text(encoding='utf-8'))
    with (ROUTING / 'raw_matrix.tsv').open(encoding='utf-8', newline='') as src, (ROUTING / 'matrix.tsv').open('w', encoding='utf-8', newline='') as dst:
        w = csv.DictWriter(dst, fieldnames=['i', 'j', 'km', 'seconds'], delimiter='	'); w.writeheader()
        for r in csv.DictReader(src, delimiter='	'):
            r['seconds'] = math.ceil(float(r['seconds']) * model['duration_ratio']); w.writerow(r)
    data = json.loads((ROUTING / 'destinations.json').read_text(encoding='utf-8'))
    solos = []
    for p in data['points']:
        sec = math.ceil(p['solo_seconds'] * model['duration_ratio'])
        solos.append(dict(point=p['id'], direction='FROM_HUB', km=p['solo_km'], seconds=sec, fare=korea_tariff(p['solo_km'], sec / 60, 12, 'pocheon')))
    om.write_csv(ROUTING / 'solo.csv', solos)
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    manifest.update(status='CALIBRATED', calibration=model, calibration_source=om.artifact(model_path))
    manifest['files'] = [om.artifact(ROUTING / f) for f in ('destinations.json', 'raw_matrix.tsv', 'snaps.csv', 'matrix.tsv', 'solo.csv')]
    om.save(manifest_path, manifest)


def cases():
    hub, points, hours, header, demand = c7.demand_inputs(None, HERE / '.cache', ROUTING)
    errors = [float(x) for x in (HERE / '.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    # 운영은 같은 성별끼리만 묶는다. 요청마다 대진대 재학생 남성 비율(대학알리미 2026)로 성별을 뽑는다.
    import campus_survey as c13
    ss = [replace(c7.Scenario(), name=f'n{n}', requests=n, male_share=c13.male_shares()[('대진대학교', '본교')]) for n in LEVELS]
    meta = c7.write_cases(CACHE / 'cases.tsv', ss, DAYS, SEED, points, hours, header, errors)
    run.json_write(CACHE / 'case-meta.json', meta)
    return demand


def simulate(command, bias, margin):
    with (CACHE / 'sim.log').open('w', encoding='utf-8') as log:
        subprocess.run(command + ['com.campustaximate.server.service.C11Simulation', str(CACHE / 'cases.tsv'), str(ROUTING / 'matrix.tsv'),
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
                                    postpaid_saving=sum(float(r['current_saving_actual_sum']) for r in rr) / people,
                                    postpaid_saving_krw=sum(int(r['current_saving_krw']) for r in rr) / people,
                                    harmed_vs_solo_meter=sum(int(r['fixed_harmed_actual']) for r in rr) / people,
                                    harmed_vs_quote=sum(int(r['fixed_harmed_quote']) for r in rr) / people,
                                    extra=sum(int(r['extra']) for r in rr), refund=sum(int(r['refund']) for r in rr),
                                    conservation=sum(int(r['conservation_errors']) for r in rr),
                                    exception_rate=sum(int(r['exceptional']) for r in rr) / len(rr)))
            stats, _ = c11.path_statistics(totals, minima, paths)
            stats.pop('trajectory', None)
            level['money'][str(sp)] = dict({k: c10.ci([x[k] for x in per]) for k in per[0]}, reserve=stats,
                                           normal_payments_per_rider=1, operator_reserve=0,
                                           extra_total=sum(x['extra'] for x in per), refund_total=sum(x['refund'] for x in per),
                                           conservation_total=sum(x['conservation'] for x in per))
        out.append(level)
    return out


def pct(x):
    return f'{100 * x:.1f}%'


def report(levels, observed, bias, margin, provenance):
    L = ['# C12 지금 서비스 그대로: 수요에 따른 매칭과 후불 1회 정산', '', ATTRIBUTION, '',
         f'운영 코드 {provenance["git_head"][:7]}{" (작업 중 변경 있음)" if provenance["production_dirty"] else ""}를 그대로 컴파일했다. '
         f'매칭은 모집 마감까지 모아 확정하며, 정산은 확인형 후불 1회 결제(실효 여유분 {100 * margin:.0f}% · 실험 견적 보정 {100 * bias:.2f}% · 실제 요금 비율 배분)다.', '',
         '출발점은 대진대 정문(37.8707, 127.1561)이다. 11/20 이전 C7~C12 값은 포천 시내(37.874, 127.205)에서 출발한 값이라 이 결과로 대체한다.', '',
         f"운영은 같은 성별끼리만 묶는다. 요청자 성별은 대진대 재학생 남성 비율 {100 * __import__('campus_survey').male_shares()[('대진대학교', '본교')]:.1f}%(대학알리미 2026)로 뽑았다. "
         '11/21 이전 C7~C13은 전원 남성으로 계산해 매칭률이 높게 나왔다.', '',
         '## 학교 앞 실제 택시 이용량 (관측)', '',
         f'TIMS 2025 영업건수, 정문 반경 약 1.5km 격자 9칸: 학기 중 하루 {observed["semester_per_day"]:.1f}건, '
         f'방학 하루 {observed["vacation_per_day"]:.1f}건, 연평균 {observed["year_per_day"]:.1f}건.', '',
         observed['meaning'], '',
         '## 수요에 따른 결과 (오차 1배, 30일 평균, 괄호는 95% CI)', '',
         '| 하루 요청 | 매칭률 | 1인 절약(매칭된 사람) | 1인 절약(요청자 전체) | 1인 절약액(매칭된 사람) | 평균 지연(분) | 확정까지(분) | 정상 이용 결제 횟수 | 운영자 적립금 |',
         '|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for lv in levels:
        m = lv['matching']; f = lv['money']['1.0']
        L.append(f"| {lv['requests']} | {pct(m['match_rate']['mean'])} [{pct(m['match_rate']['ci95_low'])}, {pct(m['match_rate']['ci95_high'])}] | "
                 f"{pct(f['postpaid_saving']['mean'])} | {pct(m['match_rate']['mean'] * f['postpaid_saving']['mean'])} | {f['postpaid_saving_krw']['mean']:,.0f}원 | "
                 f"{m['mean_delay']['mean']:.1f} | {m['mean_wait']['mean']:.1f} | {f['normal_payments_per_rider']}회 | {f['operator_reserve']}원 |")
    L += ['', '- 1인 절약은 실제 미터 요금이 붙은 혼자 탈 때 요금 대비 후불 1회 정산 분담금(P_i = 실제 요금 × 혼자 요금 비율)의 절약률·절약액이다.',
          '- 요청자 전체는 매칭되지 못한 사람(절약 0)까지 포함한 평균(매칭률 × 매칭된 사람 절약)이다.',
          '- 정상 이용 시 동승자는 영수증 확인 후 1회만 결제하며 운영자 적립금(0원)·추가 결제(0건)·부분 환불(0건)이 없다.', '',
          '## 오차 배수별 1.5배 초과(관리자 심사) 및 합계 보존 검증', '',
          '| 하루 요청 | 오차 배수 | 매칭자 1인 절약 | 매칭자 1인 절약액 | 예상 1.5배 초과(관리자 심사) | 원 단위 합계 오류 |', '|---:|---:|---:|---:|---:|---:|']
    for lv in levels:
        for sp in SPREADS:
            f = lv['money'][str(sp)]
            L.append(f"| {lv['requests']} | {sp:g} | {pct(f['postpaid_saving']['mean'])} | {f['postpaid_saving_krw']['mean']:,.0f}원 | {pct(f['exception_rate']['mean'])} | {f['conservation_total']}건 |")
    L += ['', '## 가정과 한계', '',
          '- 요청 수는 시나리오다. 관측된 학교 앞 택시 이용량을 곡선 위에 표시할 뿐, 앱 이용 비율은 가정하지 않는다.',
          '- 요금 오차는 TIMS 격자·월 평균 잔차를 대리값으로 쓴다(1·1.5·2배). 후불 1회 정산에서는 실제 요금을 비율대로 나누며, 1.5배를 초과하는 이상 요금만 관리자 심사로 자동 확정을 차단한다.',
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
    ensure_routing()
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
