"""C7: seeded public-demand proxy days, production matching and settlement, daily uncertainty."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timezone
from functools import lru_cache
import json
import math
from operator import mul
from pathlib import Path
import random
import statistics
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run
from fares import quantile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
CACHE = HERE / '.cache' / 'c7'
REPORTS = HERE / 'reports'
SEOUL_URL = 'https://www.data.go.kr/data/15115859/fileData.do'
TIMS_URL = 'https://www.data.go.kr/data/15165682/fileData.do'
STANDS_URL = 'https://www.data.go.kr/data/15088028/fileData.do'
GG_URL = 'https://data.gg.go.kr/portal/data/service/selectServicePage.do?infId=MZCREO5CKHZM6PJEA55P37391662&infSeq=1'
BOOTSTRAP_SEED = 20261119
BOOTSTRAP_REPETITIONS = 10000
# Assumptions fixed before running outcomes, not estimated student dismissal peaks.
HOUR_MULTIPLIERS = {12: .5, 13: .5, 14: 1., 15: 1., 16: 2., 17: 2., 18: 2., 19: 1.5, 20: 1.5, 21: 1.}


@dataclass(frozen=True)
class Scenario:
    name: str = 'base'
    requests: int = 100
    delay: int = 15
    saving: float = .10
    instant: float = .30
    noshow: float = 0.
    unpaid: float = 0.
    empirical: bool = True
    axis: str = '기본'


def scenarios():
    base = Scenario()
    result = [base]
    for n in (50, 100, 200, 400):
        for d in (10, 15, 20):
            if (n, d) != (100, 15):
                result.append(replace(base, name=f'n{n}_d{d}', requests=n, delay=d, axis='요청수×지연'))
    for s in (.05, .15):
        result.append(replace(base, name=f'saving{round(s*100)}', saving=s, axis='절약기준'))
    for i in (0., .60):
        result.append(replace(base, name=f'instant{round(i*100)}', instant=i, axis='즉시비율'))
    result += [replace(base, name='noshow5', noshow=.05, axis='노쇼'),
               replace(base, name='no_error', empirical=False, axis='요금오차'),
               replace(base, name='unpaid5', unpaid=.05, axis='미납'),
               replace(base, name='noshow5_no_error', noshow=.05, empirical=False, axis='오차×노쇼')]
    return result


def manifest(path, url=None, **extra):
    p = Path(path).resolve()
    return dict(path=str(p), url=url, bytes=p.stat().st_size, sha256=run.sha(p), **extra)


def weight_destinations(points, grid_counts):
    multiplicity = Counter(p['grid'] for p in points)
    for p in points:
        p['grid_trips_2025'] = int(grid_counts.get(p['grid'], 0))
        p['weight'] = p['grid_trips_2025']/multiplicity[p['grid']]
    if not points or any(p['weight'] <= 0 for p in points):
        raise ValueError('Missing positive monthly TIMS weight; do not silently impute')
    total = sum(p['weight'] for p in points)
    for p in points:
        p['probability'] = p['weight']/total
    return multiplicity


def demand_inputs(legacy, public_cache):
    import duckdb
    data = Path(legacy) / 'data'
    points = json.loads((data / 'korea_points.json').read_text(encoding='utf-8'))
    with (data / 'kakao_solo.csv').open(encoding='utf-8-sig') as f:
        solos = {int(r['point']): r for r in csv.DictReader(f) if r['direction'] == 'FROM_HUB'}
    hub = next(p for p in points if p['id'] == 0)
    candidates = [dict(p, **{k: solos[p['id']][k] for k in ('km', 'seconds', 'fare')})
                  for p in points if p['id'] in solos and p['id'] != 0]
    db = duckdb.connect()
    def sqlpath(p):
        return "'" + str(p.resolve()).replace('\\', '/').replace("'", "''") + "'"
    tims_path = public_cache / 'tims-monthly-safe.csv'
    grid_counts = dict(db.execute(f"SELECT grid,sum(trips) FROM read_csv_auto({sqlpath(tims_path)}) GROUP BY grid").fetchall())
    multiplicity = weight_destinations(candidates,grid_counts)
    seoul_path = public_cache / 'seoul-safe.csv'
    # Only pickup/dropoff/cancel timestamps and year/weekdays are used. No sensitive columns.
    counts = dict(db.execute(f"""WITH t AS (
        SELECT try_cast(승차일시 AS TIMESTAMP) pickup,try_cast(하차일시 AS TIMESTAMP) dropoff,
          try_cast(취소일시 AS TIMESTAMP) cancel FROM read_csv({sqlpath(seoul_path)},all_varchar=true))
        SELECT hour(pickup),count(*) FROM t WHERE pickup>=TIMESTAMP '2025-01-01'
          AND pickup<TIMESTAMP '2026-01-01' AND dropoff>pickup AND cancel IS NULL
          AND isodow(pickup)<=5 GROUP BY hour(pickup) ORDER BY hour(pickup)""").fetchall())
    db.close()
    hours = []
    denom = sum(counts.get(h, 0)*m for h, m in HOUR_MULTIPLIERS.items())
    for h in range(24):
        hours.append(dict(hour=h, observed_weekday_trips=counts.get(h, 0),
                          multiplier=HOUR_MULTIPLIERS.get(h, 0),
                          probability=counts.get(h, 0)*HOUR_MULTIPLIERS.get(h, 0)/denom))
    matrix = data / 'kakao_matrix.tsv'
    with matrix.open(encoding='utf-8-sig') as f:
        pairs = {(int(r['i']), int(r['j'])) for r in csv.DictReader(f, delimiter='\t')}
    ids = {p['id'] for p in candidates}
    missing = [(i, j) for i in ids for j in ids | {0} if i != j and (i, j) not in pairs]
    if missing:
        raise ValueError(f'Matrix missing {len(missing)} required legs; new API disabled')
    header = (data / 'korea_cases.txt').open(encoding='utf-8-sig').readline().strip().split('\t')
    if (float(header[6]),float(header[7])) != (hub['lat'],hub['lon']):
        raise ValueError('Historical case hub differs from cached point hub')
    meta = dict(candidate_points=len(candidates), source_points_excluding_hub=len(points)-1,
                points_excluded_without_cached_solo=[p['id'] for p in points if p['id'] and p['id'] not in solos],
                unique_grids=len(multiplicity), destination_weight_rule='2025 TIMS grid pickup sum / cached point count in same grid; normalized across available grids',
                weekday_completed_pickups=sum(counts.values()), hourly_distribution=hours,
                time_rule='2025 weekday completed noncancelled Seoul dcall pickup hours, restrict 12:00..21:59 KST, multiply fixed dismissal weights, normalize; minute/second uniform within hour',
                reservation_window='request+25..45min departure window, collection deadline request+20min; assumption',
                tims_input_is_student_od=False, matrix_complete_for_selected_points=True)
    return hub, candidates, hours, header, meta


def generate_day(points, hours, seed, count):
    """Separate stable random streams per request, paired across OAT conditions and demand sizes."""
    rows = []
    for i in range(1, count+1):
        rng = random.Random(seed*1000003+i*9176)
        p = rng.choices(points, weights=[p['weight'] for p in points])[0]
        hour = rng.choices(hours, weights=[h['probability'] for h in hours])[0]['hour']
        arrival = (hour-12)*3600+rng.randrange(3600)
        rows.append(dict(id=i, point=p, arrival=arrival, instant_draw=rng.random(),
                         paid_draw=rng.random(), noshow_draw=rng.random(), error_draw=rng.random()))
    return rows


def write_cases(target, ss, days, seed, points, hours, header, errors):
    case_meta = {}
    with target.open('w', encoding='utf-8', newline='\n') as f:
        for sc in ss:
            for day in range(days):
                day_seed = seed+day*104729
                cid = f'{sc.name}_day{day:02}'
                fields = header[:18]
                fields[1:4] = ['C7_PUBLIC_PROXY', cid, 'timeline']
                fields[16] = 'CACHE_MATRIX'
                fields += [str(sc.delay), str(sc.saving), str(day_seed), str(int(sc.empirical))]
                f.write('\t'.join(fields)+'\n')
                for r in generate_day(points, hours, day_seed, sc.requests):
                    p = r['point']
                    e = errors[min(len(errors)-1, int(r['error_draw']*len(errors)))]
                    f.write('\t'.join(map(str, ['R', r['id'], p['id'], p['lat'], p['lon'], p['km'],
                        p['seconds'], p['fare'], r['arrival'], r['arrival']+1200,
                        int(r['instant_draw'] < sc.instant), int(r['paid_draw'] >= sc.unpaid),
                        int(r['noshow_draw'] < sc.noshow), e]))+'\n')
                f.write('END\n')
                case_meta[cid] = dict(scenario=sc.name, day=day, seed=day_seed)
    return case_meta


@lru_cache(maxsize=64)
def bootstrap_day_weights(n, seed, repetitions):
    rng = random.Random(seed)
    weights = []
    for _ in range(repetitions):
        counts = [0]*n
        for i in rng.choices(range(n),k=n):
            counts[i] += 1
        weights.append(tuple(counts))
    return tuple(weights)


def ci(values, seed=BOOTSTRAP_SEED):
    """Day-cluster bootstrap percentile CI for the mean; no clipping or rider resampling."""
    vals = [float(v) for v in values if v is not None]
    if not vals:
        return dict(mean=None, ci95_low=None, ci95_high=None, valid_days=0)
    mean = statistics.mean(vals)
    if any(not math.isfinite(v) for v in vals):
        raise ValueError('Nonfinite daily metric')
    if min(vals) == max(vals):
        return dict(mean=mean, ci95_low=mean, ci95_high=mean, valid_days=len(vals))
    means = [sum(map(mul,vals,weights))/len(vals)
             for weights in bootstrap_day_weights(len(vals),seed,BOOTSTRAP_REPETITIONS)]
    return dict(mean=mean, ci95_low=quantile(means,.025), ci95_high=quantile(means,.975), valid_days=len(vals))


def read_csv(path):
    with Path(path).open(encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f))


def stands_summary(path):
    if not path.is_file():
        return dict(status='NOT_ACQUIRED', rows=0, has_coordinates=None, candidates=[])
    with path.open(encoding='cp949', newline='') as f:
        rows = list(csv.DictReader(f))
    columns = list(rows[0]) if rows else []
    candidates = [r for r in rows if any(k in r.get('택시승강장명','')+r.get('택시승차장 주소','')
                  for k in ('대진대', '선단동', '송우리'))]
    return dict(status='ACQUIRED', rows=len(rows), columns=columns,
                has_coordinates=any(k in columns for k in ('위도','경도','lat','lon','latitude','longitude')),
                candidates=candidates,
                selection='대진대/선단동/송우리 text match only; distances not computed, no geocoding or new route calls')


def check_c6(java, legacy, errors):
    """Replay existing C6 reference without changing its published reports."""
    cases, matrix, _ = run.import_legacy(legacy,60,'single')
    output, riders, settings, groups = [CACHE/('c6-regression-'+s) for s in
                                       ('cases.csv','riders.csv','settings.properties','groups.tsv')]
    command = java[:-1]+['com.campustaximate.server.service.ExperimentRunner']
    with (CACHE/'c6-regression.log').open('w',encoding='utf-8') as log:
        subprocess.run(command+list(map(str,(cases,output,matrix)))+['1']+
                       list(map(str,(riders,settings,groups))),cwd=REPO,check=True,stdout=log,stderr=log)
    now = run.summarize(output,riders)
    old = json.loads((REPORTS/'korea-cached.json').read_text(encoding='utf-8'))['baseline']
    excluded = {'engine_and_offline_route_ms'}
    differences = {k:dict(before=old[k],after=now[k]) for k in old if k not in excluded and old[k] != now.get(k)}
    sensitivity = run.bootstrap_sensitivity(command,groups,errors,CACHE/'c6-regression-bootstrap.csv',20261119,1000)
    old_sensitivity = json.loads((REPORTS/'c6-error-sensitivity.json').read_text(encoding='utf-8'))[
        'cohorts']['korea-cached']['distributions']['korea_primary']['scenarios']
    sensitivity_same = sensitivity == old_sensitivity
    result = dict(status='UNCHANGED' if not differences and sensitivity_same else 'DIFFERENCES',
                  matching_requests=now['requests'],matched=now['matched'],baseline=now,
                  differences=differences,korea_bootstrap_1000_identical=sensitivity_same,
                  sensitivity=sensitivity,excluded_comparison_fields=sorted(excluded),
                  reference_files=[manifest(REPORTS/'korea-cached.json'),manifest(REPORTS/'c6-error-sensitivity.json')])
    run.json_write(CACHE/'c6-regression.json',result)
    return result


def daily_metrics(day, riders, groups):
    n = int(day['requests'])
    confirmed = [r for r in riders if r['status'] in ('boarded', 'noshow')]
    boarded = [r for r in riders if r['status'] == 'boarded']
    nonpayers = [r for r in boarded if r['current_nonpayer'] == '1']
    absent = [r for r in riders if r['status'] == 'noshow']
    if len(riders) != n or len({r['id'] for r in riders}) != n:
        raise ValueError('Missing or duplicated request rows')
    if any(None in r for r in riders):
        raise ValueError('Rider output schema mismatch')
    if len(confirmed) != int(day['matched']):
        raise ValueError('Matched counter mismatch')
    if sum(int(day[k]) for k in ('matched', 'expired', 'unpaid')) != n:
        raise ValueError('Request accounting mismatch')
    def mean(rows, k):
        return statistics.mean(float(r[k]) for r in rows) if rows else None
    def p90(rows, k):
        return quantile([float(r[k]) for r in rows], .9)
    total_groups = len(groups)
    result = dict(matching_rate=len(confirmed)/n, boarded_rate=len(boarded)/n,
                  expired_rate=int(day['expired'])/n, unpaid_rate=int(day['unpaid'])/n,
                  matched=len(confirmed), boarded=len(boarded), groups=total_groups,
                  noshow_rate_matched=(len(confirmed)-len(boarded))/len(confirmed) if confirmed else None,
                  mean_delay_minutes=mean(confirmed, 'delay_minutes'),
                  p90_delay_minutes=p90(confirmed, 'delay_minutes'),
                  mean_wait_minutes=mean(confirmed, 'wait_minutes'), p90_wait_minutes=p90(confirmed, 'wait_minutes'),
                  conservation_errors=sum(int(g['conservation_errors']) for g in groups)+int(day['conservation_errors']),
                  budget_warning_rounds=int(day['budget_warning_rounds']), max_round_ms=float(day['max_round_ms']),
                  verification_unavailable=int(day['verification_unavailable']),
                  groups_all_absent=sum(int(g['cancelled_all_absent']) for g in groups))
    for size in (2, 3, 4):
        count = sum(int(g['size']) == size for g in groups)
        result[f'groups_{size}_count'] = count
        result[f'groups_{size}_rate'] = count/total_groups if total_groups else None
    for method in ('ratio', 'equal'):
        result[f'mean_{method}_saving'] = mean(boarded, f'{method}_saving')
        result[f'p90_{method}_saving'] = p90(boarded, f'{method}_saving')
        result[f'mean_{method}_saving_all'] = sum(float(r[f'{method}_saving']) for r in boarded)/n
        result[f'mean_expected_{method}_saving'] = mean(confirmed, f'expected_{method}_saving')
        result[f'{method}_harmed_rate'] = mean(boarded, f'{method}_harmed')
        result[f'{method}_additional_rate'] = mean(nonpayers, f'{method}_additional')
        affected = [r for r in boarded if int(r['absent_count']) > 0]
        result[f'mean_{method}_noshow_burden_increase'] = mean(affected, f'{method}_burden_increase')
        result[f'p90_{method}_noshow_burden_increase'] = p90(affected, f'{method}_burden_increase')
        result[f'mean_{method}_noshow_expected_burden_change'] = mean(affected, f'{method}_expected_burden_change')
        result[f'mean_{method}_compensation_credit_krw'] = mean(affected, f'{method}_compensation_credit_krw')
        result[f'mean_{method}_noshow_charge_krw'] = mean(absent, f'{method}_noshow_charge_krw')
        result[f'p90_{method}_noshow_charge_krw'] = p90(absent, f'{method}_noshow_charge_krw')
        result[f'{method}_noshow_charge_total_krw'] = sum(float(r[f'{method}_noshow_charge_krw']) for r in absent)
        result[f'{method}_noshow_charged_rate'] = sum(float(r[f'{method}_noshow_charge_krw'])>0 for r in absent)/len(absent) if absent else None
        result[f'mean_{method}_noshow_cap_used'] = statistics.mean(
            float(r[f'{method}_noshow_charge_krw'])/float(r[f'{method}_expected_share_krw']) for r in absent) if absent else None
        result[f'raw_mean_{method}_saving'] = mean(boarded, f'raw_{method}_saving')
        result[f'raw_p90_{method}_saving'] = p90(boarded, f'raw_{method}_saving')
        result[f'raw_{method}_harmed_rate'] = mean(boarded, f'raw_{method}_harmed')
        result[f'raw_{method}_additional_rate'] = mean(nonpayers, f'raw_{method}_additional')
        result[f'raw_mean_{method}_noshow_burden_increase'] = mean(affected, f'raw_{method}_burden_increase')
        result[f'raw_p90_{method}_noshow_burden_increase'] = p90(affected, f'raw_{method}_burden_increase')
        if sum(float(r[f'{method}_compensation_credit_krw']) for r in boarded) != result[f'{method}_noshow_charge_total_krw']:
            raise ValueError('Compensation credit/charge mismatch')
        for g in groups:
            if int(g[f'{method}_credit_total_krw']) != int(g[f'{method}_charge_total_krw']):
                raise ValueError('Group compensation ledger mismatch')
    result['noshow_affected_survivors'] = sum(int(r['absent_count']) > 0 for r in boarded)
    for hour in HOUR_MULTIPLIERS:
        hr = [r for r in riders if int(r['hour']) == hour]
        hm = sum(r['status'] in ('boarded', 'noshow') for r in hr)
        result[f'hour_{hour}_requests'] = len(hr)
        result[f'hour_{hour}_matched'] = hm
        result[f'hour_{hour}_matching_rate'] = hm/len(hr) if hr else None
    if result['conservation_errors']:
        raise ValueError('Nonzero settlement conservation errors')
    return result


LIMITATIONS = [
    'TIMS 승차 밀도를 하교 목적지 확률로 옮긴 대리 수요이며 실제 대진대 학생 OD·시간표·이용량이 아니다. 50~400건은 관측 수요가 아닌 가정이다.',
    '서울 장콜의 완료된 평일 승차 시간 분포는 대학생 하교와 다르다. 오후~야간 재가중, 시간 내 균등 도착, 20분 모집·25~45분 출발창은 가정이다. 공휴일은 별도 제거하지 않았다.',
    '기존 경로 행렬 약 51.5%는 관측 구간 합성이다. 고정 행렬은 시간대·요일·실시간 교통을 반영하지 않는다. 다중경유 요금은 C6의 단독 견적 보정 모델이다.',
    '서울 장콜 실제 경로를 일반택시 요금으로 환산한 C5 오차를 포천 공유·단독 요금에 독립 이식했다. 실제 일반택시 영수증·동승 운행 검증이 아니다.',
    '미납은 매칭 투입 전 이탈하는 참여 감소 대리 실험이다. 앱의 확정 후 실제 선결제 만료·축소·재매칭 상태 흐름을 재현하지 않는다. 추가 결제 계산에만 실제 PaymentService.prepayAmount를 사용한다.',
    '노쇼는 확정 후 독립 이탈하며 기존 전체 경로·청구액을 유지한다. 주 결과는 실제 CompensationService.apply로 노쇼 확정 후 보전을 적용한 순부담이다. 모든 부재는 CONFIRMED 판정, 승계 필요 시 수락, 보전액 전액 회수라는 가정이다. 조사·소명·회수 실패·경로 단축·재매칭은 미모형화한다. 전원 노쇼는 취소로 집계하며 청구·보전 0이다.',
    'NoShowService.open의 원래 결제자 noCompensation=true 예외를 그대로 반영한다. 원래 결제자 부재만으로는 남은 사람에게 보전이 발생하지 않는다. 서비스 호출은 DB 대신 실험 내부 저장소를 연결해 실제 계산·장부 메서드를 실행하며 실제 금융 거래를 만들지 않는다.',
    '대진대 단일 출발점, 걷기 0, 하나의 성별 풀, 최대 4명이다. 포천 택시승차장 CSV에는 좌표가 없어 다중 승차점·최단 거리 배정을 하지 않는다.',
    '95% 구간은 고정된 모형 아래 독립 모의 일자 간 변동이다. 자료 전이·가정·노선 합성의 불확실성을 포함하지 않으며 실제 학생 효과의 신뢰구간이 아니다.',
    '엔진의 실제 1,000ms 탐색 제한을 유지한다. 고정 입력·시드는 재현되지만 기계 부하로 탐색 경계 결과가 달라질 수 있다. 회차 소요시간≥탐색한도를 보수적 경고로 공개한다.',
    'OAT와 요청수×지연 격자 외 상호작용은 검증하지 않았다. 노쇼×오차 한 쌍만 추가로 비교한다. 결과가 약하거나 차이가 없어도 설정·분포를 재조정하지 않는다.',
]

DEFINITIONS = {
    'matching_rate': '확정 매칭 인원 / 모든 생성 요청(미납·만료 포함); 노쇼도 확정 인원에는 포함, boarded_rate 별도',
    'saving': '주 결과: 실제 탑승자 1−보전 후 순분담금/독립 단독 실제 요금; raw_는 보전 전 참고. 일별 평균과 일별 P90을 각각 일자 간 평균. 전체 요청 기준은 미탑승 0이며 노쇼자의 별도 보전 부담은 별도 지표.',
    'delay': '확정 인원의 max(0, 걷기+동승시간−단독시간), 분; 시간대별 교통 오차를 추가하지 않음',
    'wait': '요청 생성부터 회차 확정까지 분, 확정 인원 기준; 예약 출발 준비시간과 구별',
    'harmed': '보전 후 순분담금 > 독립 단독 실제 요금 / 실제 탑승자; raw_는 동일 분모 보전 전',
    'additional': '보전 후 순분담금 > PaymentService.prepayAmount(원래 예상 분담금) / 현재 비결제자인 탑승자. PaymentLifecycleService처럼 승계한 현재 결제자는 제외하며 1/N은 자체 예상 선결제 사용. raw_도 동일 분모. 이전 판의 원래 비결제자 분모와 구별.',
    'noshow_burden': '노쇼 발생 일행의 생존자만: (보전 후 순분담금−같은 실제 청구액의 노쇼 없는 분담금)/노쇼 없는 분담금. raw_는 보전 전. 부호 있는 변화율이며 보전이 요금 오차까지 메우면 음수 가능; 발생확률과 달리 0~100%로 제한되는 지표가 아님.',
    'compensation': '실제 CompensationService.apply 호출. increases=max(0,실제 raw 몫−확정 때 예상 몫), cap=CONFIRMED && !noCompensation 부재자의 확정 때 예상 몫 합계. SettlementCalculator.compensation으로 생존자 크레딧, compensationBurdens로 여러 노쇼자의 상한 비율 청구. 원래 결제자는 NoShowService.open처럼 면제.',
    'noshow_charge': '원/노쇼자: 실제 보전 청구액의 일별 평균·P90, 분모는 면제자·전원취소의 0원을 포함한 모든 노쇼자. 지급자 비율·일별 보전 총액·상한 사용률도 공개. 탑승자 손해/추가 결제 분모와 분리.',
    'group_size': '확정 당시 2/3/4명 일행 건수 및 일행 기준 비율; 사람 기준 비율 아님',
    'hourly': '요청 시각 KST 시간별 확정 인원/생성 요청; 요청 없는 일자의 비율은 null이며 valid_days 공개',
    'ci95': f'모든 지표는 일(day) 단위 복원추출 {BOOTSTRAP_REPETITIONS:,}회, 고정 시드 {BOOTSTRAP_SEED}, 각 반복 일별 지표 평균의 2.5/97.5 백분위(선형 보간). 비율 발생확률은 구성상 0~100%이며 사후 절단하지 않음. 조건부 지표는 null 일자 제외 후 valid_days 공개. 같은 유효 일수의 지표는 같은 일자 추출 가중치를 공유. 상수 표본은 정확히 같은 퇴화 부트스트랩 결과로 계산.',
    'paired_design': '일자별 독립 시드, 같은 일자의 OAT 조건은 요청·이탈·오차 난수 공유. 오차·노쇼만 다른 경우 같은 매칭 결과를 재사용.',
    'conservation': '각 운영 일행: 보전 전 분담금 합=청구액, 생존자 보전 후 순부담 합+노쇼자 보전 부담 합=청구액, 크레딧 합=보전 청구 합. 비율/1N 모두 실제 코드로 검사하고 오류 시 중단.',
}


def aggregate(ss, case_meta, days_path, riders_path, groups_path):
    rr, gg = defaultdict(list), defaultdict(list)
    for row in read_csv(riders_path):
        rr[row['case_id']].append(row)
    for row in read_csv(groups_path):
        gg[row['case_id']].append(row)
    daily = []
    for row in read_csv(days_path):
        cid = row['case_id']
        daily.append(dict(case_id=cid, **case_meta[cid], **daily_metrics(row, rr[cid], gg[cid])))
    result = []
    for sc in ss:
        selected = [d for d in daily if d['scenario'] == sc.name]
        if len(selected) != len([v for v in case_meta.values() if v['scenario'] == sc.name]):
            raise ValueError('Incomplete days')
        keys = [k for k in selected[0] if k not in ('case_id', 'scenario', 'day', 'seed')]
        result.append(dict(parameters=asdict(sc), days=len(selected),
                           metrics={k: ci([d[k] for d in selected]) for k in keys}))
    for scenario in result:
        for key,m in scenario['metrics'].items():
            if probability_metric(key):
                for stat in ('mean','ci95_low','ci95_high'):
                    if m[stat] is not None and not 0 <= m[stat] <= 1:
                        raise ValueError('Probability outside [0,1]: '+key)
    return result, daily


def probability_metric(key):
    return key.endswith('_rate') or key == 'noshow_rate_matched' or key.endswith('_noshow_cap_used')


def cell(m, percent=False):
    if m['mean'] is None:
        return '미측정'
    scale, suffix = (100, '%') if percent else (1, '')
    mean = f"{m['mean']*scale:.2f}{suffix}"
    if m['ci95_low'] is None:
        return mean
    return f"{mean} [{m['ci95_low']*scale:.2f}, {m['ci95_high']*scale:.2f}]"


def markdown(report):
    by_name = {s['parameters']['name']: s for s in report['scenarios']}
    b = by_name['base']['metrics']
    lines = ['# C7 상세 모의 운영', '', report['conclusion'], '',
             '발표용: '+report['presentation_sentence'], '',
             f'모든 표는 **일별 지표 평균 [95% CI]**이며, 일 단위 복원추출 {BOOTSTRAP_REPETITIONS:,}회·고정 시드 {BOOTSTRAP_SEED}의 백분위 구간이다. 주 금융 지표는 실제 보전 적용 후 순부담 기준이다. 절약률은 실제 탑승자 기준, 매칭률은 미납 포함 전체 요청 기준이다.', '',
             '## 기본 조건', '',
             f"고정 시드 {report['seed']}, 조건당 {report['days_per_condition']}일. 하루 100건·지연 15분·절약 10%·즉시 30%·노쇼 0%·미납 0%·C5 경험 오차.", '',
             '| 지표 | 평균 [95% CI] |', '|---|---:|']
    for title, key, pct in [('확정 매칭률','matching_rate',True),('실제 탑승률','boarded_rate',True),
         ('비율 정산 실제 절약률','mean_ratio_saving',True),('1/N 실제 절약률','mean_equal_saving',True),
         ('비율 정산 P90 절약률','p90_ratio_saving',True),('1/N P90 절약률','p90_equal_saving',True),
         ('전체 요청 비율 절약률(미탑승=0)','mean_ratio_saving_all',True),
         ('평균 지연(분)','mean_delay_minutes',False),('P90 지연(분)','p90_delay_minutes',False),
         ('평균 확정 대기(분)','mean_wait_minutes',False),('P90 확정 대기(분)','p90_wait_minutes',False),
         ('비율 정산 손해 비율','ratio_harmed_rate',True),('1/N 손해 비율','equal_harmed_rate',True),
         ('비율 정산 추가 결제','ratio_additional_rate',True),('1/N 추가 결제','equal_additional_rate',True),
         ('2명 일행 비율','groups_2_rate',True),('3명 일행 비율','groups_3_rate',True),('4명 일행 비율','groups_4_rate',True)]:
        lines.append(f'| {title} | {cell(b[key], pct)} |')
    def table(title, subset):
        lines.extend(['', '## '+title, '', '| 조건 | 매칭률 | 비율 절약률 | 1/N 절약률 | 평균 지연(분) | 대기(분) |',
                      '|---|---:|---:|---:|---:|---:|'])
        for s in subset:
            p, m = s['parameters'], s['metrics']
            label = f"{p['requests']}건 / {p['delay']}분 / {p['saving']:.0%} / 즉시{p['instant']:.0%}"
            lines.append('| '+label+' | '+' | '.join(cell(m[k], pct) for k, pct in [
                ('matching_rate',True),('mean_ratio_saving',True),('mean_equal_saving',True),
                ('mean_delay_minutes',False),('mean_wait_minutes',False)])+' |')
    all_s = report['scenarios']
    table('발표용 곡선: 하루 요청 수', sorted([s for s in all_s if s['parameters']['delay']==15 and s['parameters']['axis'] in ('기본','요청수×지연')],key=lambda s:s['parameters']['requests']))
    table('발표용 곡선: 지연 한도', sorted([s for s in all_s if s['parameters']['requests']==100 and s['parameters']['axis'] in ('기본','요청수×지연')],key=lambda s:s['parameters']['delay']))
    table('발표용 곡선: 절약 기준', [by_name['saving5'],by_name['base'],by_name['saving15']])
    table('즉시 비율', [by_name['instant0'],by_name['base'],by_name['instant60']])
    table('요청 수×지연 한도 전체 격자', sorted([s for s in all_s if s['parameters']['axis'] in ('기본','요청수×지연')],key=lambda s:(s['parameters']['requests'],s['parameters']['delay'])))
    lines += ['', '## 오차·노쇼·미납: 주 결과(보전 후)', '',
              '| 조건 | 매칭률 | 탑승률 | 비율 절약률 | 1/N 절약률 | 손해(비율) | 손해(1/N) | 추가 결제(비율) | 추가 결제(1/N) | 노쇼 생존자 순부담 변화(비율) |',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for name in ('no_error','base','noshow5_no_error','noshow5','unpaid5'):
        s = by_name[name]
        label = {'no_error':'오차 없음·노쇼0%', 'base':'경험 오차·노쇼0%',
                 'noshow5_no_error':'오차 없음·노쇼5%', 'noshow5':'경험 오차·노쇼5%', 'unpaid5':'경험 오차·미납5%'}[name]
        lines.append('| '+label+' | '+' | '.join(cell(s['metrics'][k],True) for k in (
            'matching_rate','boarded_rate','mean_ratio_saving','mean_equal_saving','ratio_harmed_rate','equal_harmed_rate',
            'ratio_additional_rate','equal_additional_rate','mean_ratio_noshow_burden_increase'))+' |')
    lines += ['', '## 노쇼 보전 전후 비교: 전 수치는 참고', '',
              '각 셀은 보전 전 → 보전 후이며 같은 일행·실제 청구액·현재 비결제자 분모를 사용한다. 원래 결제자 노쇼는 실제 코드처럼 보전 면제다. 추가 결제 분모에서도 승계한 현재 결제자를 제외했다.', '',
              '| 조건 | 정산 | 절약률 전→후 | 손해 비율 전→후 | 추가 결제 전→후 | 생존자 부담 변화 전→후 |',
              '|---|---|---:|---:|---:|---:|']
    for name in ('noshow5_no_error','noshow5'):
        m=by_name[name]['metrics']
        for method,label in (('ratio','비율'),('equal','1/N')):
            pairs=[(f'raw_mean_{method}_saving',f'mean_{method}_saving'),
                   (f'raw_{method}_harmed_rate',f'{method}_harmed_rate'),
                   (f'raw_{method}_additional_rate',f'{method}_additional_rate'),
                   (f'raw_mean_{method}_noshow_burden_increase',f'mean_{method}_noshow_burden_increase')]
            lines.append('| '+('오차 없음' if name.endswith('no_error') else '경험 오차')+'·노쇼5% | '+label+' | '+
                         ' | '.join(cell(m[a],True)+' → '+cell(m[b],True) for a,b in pairs)+' |')
    lines += ['', '## 노쇼자 보전 부담', '',
              '노쇼자당 금액은 면제자·전원 취소의 0원도 포함한다. 현금 회수가 모두 이뤄진다는 가정이며, 크레딧 합과 청구 합을 검증했다.', '',
              '| 조건 | 정산 | 노쇼자당 평균(원) | 노쇼자당 P90(원) | 보전 청구 비율 | 일평균 총보전(원) | 평균 상한 사용률 |',
              '|---|---|---:|---:|---:|---:|---:|']
    for name in ('noshow5_no_error','noshow5'):
        m=by_name[name]['metrics']
        for method,label in (('ratio','비율'),('equal','1/N')):
            keys=[(f'mean_{method}_noshow_charge_krw',False),(f'p90_{method}_noshow_charge_krw',False),
                  (f'{method}_noshow_charged_rate',True),(f'{method}_noshow_charge_total_krw',False),
                  (f'mean_{method}_noshow_cap_used',True)]
            lines.append('| '+('오차 없음' if name.endswith('no_error') else '경험 오차')+'·노쇼5% | '+label+' | '+
                         ' | '.join(cell(m[k],pct) for k,pct in keys)+' |')
    lines += ['', '## 기본 조건 시간대별', '', '| 요청 시간(KST) | 일평균 요청 | 매칭률 | 유효 일수 |', '|---|---:|---:|---:|']
    for h in HOUR_MULTIPLIERS:
        lines.append(f"| {h}시 | {b[f'hour_{h}_requests']['mean']:.2f} | {cell(b[f'hour_{h}_matching_rate'],True)} | {b[f'hour_{h}_matching_rate']['valid_days']} |")
    lines += ['', '## 입력과 자료 확보', '',
              f"TIMS 월별 자료를 {report['demand']['candidate_points']}개 캐시 목적지({report['demand']['unique_grids']}개 격자)에 연결했다. 같은 격자 안 대표점 수로 나누어 중복 가중을 막았다.",
              '단독 캐시 없는 지점 제외: '+str(report['demand']['points_excluded_without_cached_solo']),
              f"포천 승차장 CSV: {report['stands']['rows']}곳. 열: {', '.join(report['stands'].get('columns',[]))}. 좌표 열 유무: {report['stands']['has_coordinates']}. 기존 허브 좌표를 신규 승차장 좌표로 간주하지 않았다.",
              '대진대/선단동/송우리 명칭·주소 후보(거리 미계산): '+', '.join(r['택시승강장명'] for r in report['stands']['candidates']),
              '경기 정류소: 반환 HTML이 sdkInput 값 중간에서 끝나 CSV/API 링크·시간대 열을 확인할 수 없었다. 자료 미확보이며 인증키 필요 여부도 미확인이다.', '',
              '| 시각 | 장콜 평일 완료 건수 | 가정 배수 | 생성 확률 |', '|---|---:|---:|---:|']
    for h in report['demand']['hourly_distribution']:
        lines.append(f"| {h['hour']}시 | {h['observed_weekday_trips']} | {h['multiplier']} | {h['probability']:.4%} |")
    lines += ['', '## 설계·정의', '',
              f"{len(all_s)}조건×{report['days_per_condition']}일. 기본값 중심 OAT + 요청수×지연 4×3 격자 + 노쇼×오차 한 쌍. 즉시·노쇼·미납은 요청별 독립 Bernoulli 확률이며 실제 표본 비율은 매일 변한다.",
              '1분마다 실제 MatchingRound를 호출하고 예약은 생산 5분 규칙으로 확정한다. 2차 RouteGroupVerifier도 같은 지연·절약 설정을 받도록 고정 상수를 제거했다. 코드 기본값 15분·10%는 그대로다.',
              *['- '+k+': '+v for k,v in DEFINITIONS.items()], '', '## 검증·한계', '',
              f"총 생성 요청 {report['verification']['generated_requests']:,}, 정산 합계 오류 {report['verification']['conservation_errors']}건, 새 카카오 API 호출 0회. 회차 시간 경고 {report['verification']['budget_warning_rounds']}회.",
              'C6 회귀 확인: '+str(report['verification'].get('c6_regression',{}).get('status','미실행')),
              *['- '+x for x in LIMITATIONS], '',
              '입력 URL·크기·SHA256: [c7-input-MANIFEST.json](c7-input-MANIFEST.json). 조건별 모든 지표와 CI는 JSON/CSV, 일자별 집계는 c7-detailed-sim-days.csv.', '']
    return '\n'.join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--legacy-realdata', type=Path, default=REPO.parent/'taximate-matching-sim'/'realdata')
    p.add_argument('--public-cache', type=Path, default=HERE/'.cache')
    p.add_argument('--days', type=int, default=30)
    p.add_argument('--seed', type=int, default=20261119)
    p.add_argument('--java-home', default=str(HERE/'.cache'/'jdk21'))
    p.add_argument('--boot-package', type=Path)
    p.add_argument('--build', action='store_true')
    p.add_argument('--maven', default='mvn')
    p.add_argument('--only', nargs='+', help='Subset for diagnostic run; does not overwrite final reports')
    p.add_argument('--reuse-output', action='store_true', help='Validate hashes and aggregate completed C7 cache without rerunning engine')
    p.add_argument('--verify-c6', action='store_true', help='Rerun C6 Korean cached matching and 1000-replicate error comparison without modifying C6 reports')
    args = p.parse_args()
    if args.days < 30:
        p.error('Each condition requires at least 30 independent days')
    CACHE.mkdir(parents=True, exist_ok=True)
    ss = scenarios()
    if args.only:
        ss = [s for s in ss if s.name in args.only]
        if not ss or set(args.only)-{s.name for s in ss}:
            p.error('Unknown scenario')
    hub, points, hours, header, demand = demand_inputs(args.legacy_realdata, args.public_cache)
    error_path = args.public_cache/'kr-errors.csv'
    c5 = json.loads((REPORTS/'public-fares.json').read_text(encoding='utf-8'))
    if run.sha(error_path) != c5['korea']['errors']['sha256']:
        raise ValueError('C5 errors hash mismatch')
    errors = [float(s) for s in error_path.read_text(encoding='utf-8').splitlines() if s.strip()]
    if len(errors) != c5['korea']['overall']['n'] or any(not math.isfinite(e) or e <= -1 for e in errors):
        raise ValueError('Invalid C5 empirical sample')
    for file, expected in ((args.public_cache/'kr-public'/'seoul_dcall_2025.csv',c5['korea']['source']['sha256']),
                           (args.public_cache/'kr-public'/'tims_2025.zip',c5['tims']['monthly']['source']['sha256'])):
        if run.sha(file) != expected:
            raise ValueError('Public original changed since C5: '+str(file))
    cases = CACHE/'cases.tsv'
    case_meta = write_cases(cases, ss, args.days, args.seed, points, hours, header, errors)
    matrix = args.legacy_realdata/'data'/'kakao_matrix.tsv'
    # Explicit dependency package only; current application classes always compiled directly.
    boot = args.boot_package or REPO/'server'/'target'/'server-0.1.0.jar'
    inputs = [manifest(args.public_cache/'kr-public'/'seoul_dcall_2025.csv', SEOUL_URL, role='original; only allowlisted normalized timestamps used'),
              manifest(args.public_cache/'seoul-safe.csv', SEOUL_URL, role='allowlisted normalized input'),
              manifest(args.public_cache/'kr-public'/'tims_2025.zip', TIMS_URL),
              manifest(args.public_cache/'tims-monthly-safe.csv', TIMS_URL, role='normalized monthly grids'),
              manifest(error_path, SEOUL_URL, role='derived C5 general-taxi counterfactual relative errors'),
              manifest(REPORTS/'public-fares.json', role='C5 definition and error sample hash'),
              *[manifest(args.legacy_realdata/'data'/f, role='read-only historical cache') for f in
                ('korea_points.json','kakao_solo.csv','kakao_matrix.tsv','korea_cases.txt')],
              manifest(cases, role='generated seeded C7 proxy requests')]
    for filename, url in [('kr-public/pocheon-taxi-stands.csv',STANDS_URL), ('gg-stops-page.html',GG_URL)]:
        f = args.public_cache/filename
        if f.is_file():
            inputs.append(manifest(f,url,role='stand addresses only' if filename.endswith('.csv') else 'incomplete catalogue only; not passenger data'))
    acquire = args.public_cache/'c7-download-manifest.json'
    acquired = json.loads(acquire.read_text(encoding='utf-8')) if acquire.is_file() else {'failures':[{'error':'C7 acquisition was not run'}]}
    fingerprints = {i['path']: i['sha256'] for i in inputs}
    receipt_path = CACHE/'execution.json'
    riders, groups, days = [CACHE/f for f in ('riders.csv','groups.csv','days.csv')]
    home = run.jdk_home(args.java_home)
    if args.reuse_output:
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        if receipt['input_sha256'] != fingerprints:
            raise ValueError('Cached execution inputs changed')
        source = receipt['production_source']
        for relative, digest in source['compiled_source_sha256'].items():
            if run.sha(REPO/relative) != digest:
                raise ValueError('Compiled sources changed since cached execution')
        for f in (riders,groups,days):
            if run.sha(f) != receipt['output_sha256'][f.name]:
                raise ValueError('Cached output changed')
    else:
        classpath, source = run.compile_current(home,args.build,args.maven,boot_package=boot)
        (HERE/'.cache'/'runtime-classpath.txt').write_text(classpath,encoding='utf-8')
        (HERE/'.cache'/'runtime-java.txt').write_text(str(home/'bin'/'java'),encoding='utf-8')
        java = [str(home/'bin'/'java'), '-Xmx1024m', '-cp', classpath, 'com.campustaximate.server.service.DetailedSimulation']
        subprocess.run(java+['--self-test'],cwd=REPO,check=True)
        (CACHE/'java-command.json').write_text(json.dumps(java),encoding='utf-8')
        # Logs can be inspected without exposing any individual original trip record.
        with (CACHE/'execution.log').open('w',encoding='utf-8') as log:
            subprocess.run(java+list(map(str,(cases,matrix,error_path,riders,groups,days))),cwd=REPO,check=True,stdout=log,stderr=log)
        run.json_write(receipt_path,dict(input_sha256=fingerprints,production_source=source,
            output_sha256={f.name:run.sha(f) for f in (riders,groups,days)},completed_at=datetime.now(timezone.utc).isoformat()))
    results, daily = aggregate(ss,case_meta,days,riders,groups)
    if args.only:
        run.json_write(CACHE/'diagnostic-summary.json',results)
        print(json.dumps([dict(parameters=r['parameters'], matching=r['metrics']['matching_rate']) for r in results],ensure_ascii=False))
        return
    by = {r['parameters']['name']:r['metrics'] for r in results}
    c6 = None
    if args.verify_c6:
        java = json.loads((CACHE/'java-command.json').read_text(encoding='utf-8'))
        c6 = check_c6(java,args.legacy_realdata,error_path)
    elif (CACHE/'c6-regression.json').is_file():
        c6 = json.loads((CACHE/'c6-regression.json').read_text(encoding='utf-8'))
    b,noshow = by['base'],by['noshow5']
    conclusion = (f"공개자료 대리 수요 하루 100건에서 확정 매칭률 {b['matching_rate']['mean']:.2%}, "
        f"탑승자 비율 정산 평균 절약률 {b['mean_ratio_saving']['mean']:.2%}, "
        f"평균 확정 대기 {b['mean_wait_minutes']['mean']:.2f}분이다. 노쇼 5%·경험 오차에서 실제 앱 보전 적용으로 "
        f"생존자 순부담 변화 {noshow['raw_mean_ratio_noshow_burden_increase']['mean']:.2%}→{noshow['mean_ratio_noshow_burden_increase']['mean']:.2%}, "
        f"손해 비율 {noshow['raw_ratio_harmed_rate']['mean']:.2%}→{noshow['ratio_harmed_rate']['mean']:.2%}다. 실제 학생 운영 성과를 의미하지 않는다.")
    report = dict(schema_version=2,status='COMPLETED',generated_at=datetime.now(timezone.utc).isoformat(),
        seed=args.seed,days_per_condition=args.days,conditions=len(ss),new_kakao_api_calls=0,
        historical_kakao_api_calls=1490,conclusion=conclusion,
        presentation_sentence=(f"공개자료 대리 수요 모의실험에서 하루 100건의 매칭률은 {b['matching_rate']['mean']:.2%}였다. "
            f"노쇼 5%에서는 실제 보전 규칙 적용 후 손해 비율 {noshow['ratio_harmed_rate']['mean']:.2%}, "
            f"현재 비결제자 추가 결제 {noshow['ratio_additional_rate']['mean']:.2%}이며 실제 학생 운영 성과는 아니다."),
        confidence_interval=dict(method='day-cluster bootstrap percentile',repetitions=BOOTSTRAP_REPETITIONS,
            seed=BOOTSTRAP_SEED,percentiles=[.025,.975],unit='independent simulated day',interpolation='linear',
            clipping=False,conditional_metrics='drop null days; report valid_days',
            constant_samples='exact degenerate bootstrap distribution; singleton interval is descriptive only'),
        compensation=dict(service='server/src/main/java/com/campustaximate/server/service/CompensationService.java#apply',
            calculators=['matching-engine/src/main/java/com/campustaximate/settlement/SettlementCalculator.java#compensation',
                         'matching-engine/src/main/java/com/campustaximate/settlement/SettlementCalculator.java#compensationBurdens'],
            exemption_source='server/src/main/java/com/campustaximate/server/service/NoShowService.java#open',
            additional_payer_exclusion='server/src/main/java/com/campustaximate/server/service/PaymentLifecycleService.java#finalizeSettlement',
            adapter='experiments/java/com/campustaximate/server/service/SimulationCompensation.java',
            original_payer_exempt=True,in_memory_repository_ports=True,all_absentees_adjudicated_confirmed=True,
            full_recovery_assumed=True,successor_accepts_assumed=True),
        design='OAT around base + demand×delay 4×3 grid + no-error/noshow interaction',
        demand=demand,stands=stands_summary(args.public_cache/'kr-public'/'pocheon-taxi-stands.csv'),
        definitions=DEFINITIONS,limitations=LIMITATIONS,scenarios=results,
        production_source=source,inputs_manifest='c7-input-MANIFEST.json',acquisition=acquired,
        verification=dict(generated_requests=sum(int(d['requests']) for d in read_csv(days)),
            conservation_errors=sum(d['conservation_errors'] for d in daily),
            budget_warning_rounds=sum(d['budget_warning_rounds'] for d in daily),
            max_round_ms=max(d['max_round_ms'] for d in daily),
            unavailable=sum(d['verification_unavailable'] for d in daily),
            production_self_test='passed: thresholds, settlement, prepay, actual CompensationService single/multiple/cap/exemption/idempotence/ledger conservation',
            probability_ci_bounds_checked=True,
            c6_regression=c6,
            observed_students=0),
        tool_sha256={str(f.relative_to(REPO)):run.sha(f) for f in (Path(__file__),HERE/'run.py',HERE/'acquire-public.mjs')})
    report['git_metadata_status'] = 'NOT_QUERIED: user prohibited git operations'
    run.json_write(REPORTS/'c7-input-MANIFEST.json',dict(generated_at=report['generated_at'],files=inputs,
        acquisition=acquired,generated_input_sha256=run.sha(cases),
        source_dependencies=source,cache_output_sha256={f.name:run.sha(f) for f in (riders,groups,days)}))
    run.json_write(REPORTS/'c7-detailed-sim.json',report)
    (REPORTS/'c7-detailed-sim.md').write_text(markdown(report),encoding='utf-8')
    with (REPORTS/'c7-detailed-sim.csv').open('w',encoding='utf-8-sig',newline='') as f:
        fields = list(asdict(ss[0]))+['days','metric','mean','ci95_low','ci95_high','valid_days']
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader()
        for s in results:
            for key,m in s['metrics'].items():
                writer.writerow(dict(**s['parameters'],days=s['days'],metric=key,**m))
    with (REPORTS/'c7-detailed-sim-days.csv').open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=list(daily[0]));writer.writeheader();writer.writerows(daily)
    print(conclusion)


if __name__ == '__main__':
    main()
