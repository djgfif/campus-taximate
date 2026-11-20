"""C10 paired demand curve and production-calculator cash-ledger counterfactuals."""
from __future__ import annotations
import argparse
from collections import defaultdict
import csv
from dataclasses import replace
from datetime import datetime, timezone
from functools import lru_cache
import json
from pathlib import Path
import random
import statistics
import subprocess
import sys
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
import detailed as c7
import run

HERE=Path(__file__).resolve().parent
REPO=HERE.parent
CACHE=HERE/'.cache'/'c10'
REPORTS=HERE/'reports'
LEVELS=(25,50,75,100,150,200,300,400,600,800,1000)
BUFFERS=(100,110,115,120,125,130,140,150)
SEED=20261119
DAYS=30
JAVA_SOURCE=HERE/'java/com/campustaximate/server/service/C10Prepay.java'

def scenarios():
    return [replace(c7.Scenario(),name='base' if n==100 else f'n{n}',requests=n) for n in LEVELS]

def instrument(source):
    """Keep the matching algorithm intact; only add passive capture outside its deadline."""
    edits={
      '            Instant slot=BASE.plusSeconds(second);':
      '            Instant slot=BASE.plusSeconds(second);\n            C10Prepay.round(waiting,slot,s);',
      '                double e=scaled[new Random(errorSeed).nextInt(scaled.length)];':
      '                int errorIndex=new Random(errorSeed).nextInt(scaled.length);\n                double e=scaled[errorIndex];\n                C10Prepay.capture(c.id,groupCount,route.total(),units,payer,g.order().stream().map(r->cfg.flags().get(r.id()).soloError()).toList(),errorIndex);',
      '        days.flush();riders.flush();groups.flush();':
      '        C10Prepay.day(c.id,rounds);\n        days.flush();riders.flush();groups.flush();'}
    source=source.replace('DetailedSimulation','C10DetailedSimulation')
    for old,new in edits.items():
        if source.count(old)!=1:raise ValueError('C7 adapter changed: review capture point')
        source=source.replace(old,new)
    return source

def add_solo_indices(ledger):
    """Recover original C7 draw row IDs without changing or redrawing realised bills."""
    meta=json.loads((CACHE/'case-meta.json').read_text())
    data=json.loads((HERE/'.cache/osrm/destinations.json').read_text(encoding='utf-8'))
    demand=json.loads((CACHE/'demand.json').read_text(encoding='utf-8'))
    hours=demand['hourly_distribution'];points=data['points']
    riders=defaultdict(list)
    for r in c7.read_csv(CACHE/'riders.csv'):
        if r['status']=='boarded':riders[r['case_id']].append(r)
    generated={};offset=defaultdict(int)
    error_values=[float(x) for x in (HERE/'.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    count=len(error_values)
    for r in ledger:
        cid=r['case_id']
        if not cid.startswith(('base_day','n50_day','n300_day')):continue
        n=100 if cid.startswith('base_day') else int(cid.split('_')[0][1:])
        if cid not in generated:
            generated[cid]={q['id']:int(q['error_draw']*count) for q in c7.generate_day(points,hours,meta[cid]['seed'],n)}
        size=len(r['units'].split(';'));rr=riders[cid][offset[cid]:offset[cid]+size];offset[cid]+=size
        r['solo_indices']=[generated[cid][int(q['id'])] for q in rr]
        assert len(r['solo_indices'])==size
        assert all(abs(error_values[i]-float(e))<1e-12 for i,e in zip(r['solo_indices'],r['solo_errors'].split(';'))), 'Recovered solo draw differs from C7 ledger'
    return ledger

def fit_bias(errors,ledger,seed=SEED):
    """Row-disjoint fit: lock all fixed C7 evaluation draws into heldout first.

    Uniformly sample half the remaining grid-month rows for training, without
    looking at their residual values. Repeated values in distinct rows remain
    distinct observations. This is not a geographical or future-month holdout.
    """
    ledger=[r for r in ledger if r['case_id'].startswith(('base_day','n50_day','n300_day'))]
    used={int(r['error_index']) for r in ledger}
    used.update(i for r in ledger for i in r['solo_indices'])
    remaining=sorted(set(range(len(errors)))-used)
    random.Random(seed).shuffle(remaining)
    train=sorted(remaining[:len(remaining)//2]);held=sorted(set(range(len(errors)))-set(train))
    if not train or not held:raise ValueError('Insufficient disjoint residual rows')
    assert not set(train)&used
    return dict(mean=statistics.mean(errors[i] for i in train),train_indices=train,
      heldout_indices=held,evaluation_indices=sorted(used),train_n=len(train),heldout_n=len(held),
      method='Lock all fixed C7 evaluation group/solo row indices to heldout; uniformly shuffle remaining row IDs with seed and fit half; no geographic holdout')

@lru_cache(maxsize=32)
def weights(n):
    return np.asarray(c7.bootstrap_day_weights(n,SEED,10000),dtype=float)/n

def ci(values):
    x=np.asarray([v for v in values if v is not None],dtype=float)
    if not len(x):return dict(mean=None,ci95_low=None,ci95_high=None,valid_days=0)
    b=np.sum(weights(len(x))*x,axis=1)
    lo,hi=np.quantile(b,[.025,.975])
    return dict(mean=float(np.mean(x)),ci95_low=float(lo),ci95_high=float(hi),valid_days=len(x))

def mean(rows,key):
    return statistics.mean(float(r[key]) for r in rows) if rows else None

def positive(rows,key):
    return [float(r[key]) for r in rows if float(r[key])>0]

def financial_day(rows):
    npay=[r for r in rows if r['nonpayer']=='1']; pay=[r for r in rows if r['nonpayer']=='0']
    extra=positive(npay,'extra');refund=positive(npay,'refund')
    return dict(additional_charge_rate=sum(float(r['extra'])>0 for r in npay)/len(npay),
      refund_rate=sum(float(r['refund'])>0 for r in npay)/len(npay),
      mean_extra_krw=statistics.mean(extra) if extra else None,p90_extra_krw=c7.quantile(extra,.9) if extra else None,
      mean_refund_krw=statistics.mean(refund) if refund else None,p90_refund_krw=c7.quantile(refund,.9) if refund else None,
      mean_extra_all_nonpayers_krw=mean(npay,'extra'),mean_refund_all_nonpayers_krw=mean(npay,'refund'),
      mean_upfront_krw=mean(npay,'upfront'),mean_payer_burden_change_krw=mean(pay,'payer_increase'),
      p90_payer_burden_change_krw=c7.quantile([float(r['payer_increase']) for r in pay],.9),
      mean_payer_loss_vs_solo_krw=mean(pay,'payer_loss'),
      mean_payer_difference_vs_solo_krw=statistics.mean(float(r['net'])-float(r['solo']) for r in pay),
      payer_harmed_rate=sum(float(r['net'])>float(r['solo']) for r in pay)/len(pay),
      harmed_rate=sum(float(r['net'])>float(r['solo']) for r in rows)/len(rows),
      sensitivity_group_floor_rate=mean(rows,'sensitivity_group_floor'),sensitivity_solo_floor_rate=mean(rows,'sensitivity_solo_floor'),
      conservation_errors=sum(int(r['conservation_errors']) for r in rows))

def parity(riders,groups):
    oldr=[r for r in c7.read_csv(HERE/'.cache/c7/riders.csv') if r['case_id'].startswith('base_day')]
    oldg=[r for r in c7.read_csv(HERE/'.cache/c7/groups.csv') if r['case_id'].startswith('base_day')]
    newr=[r for r in riders if r['case_id'].startswith('base_day')]
    newg=[r for r in groups if r['case_id'].startswith('base_day')]
    if oldr!=newr or oldg!=newg:raise ValueError('100/day C7 base parity failed; do not publish')
    return dict(status='EXACT',rider_rows=len(oldr),group_rows=len(oldg),
      comparison='All rider and group CSV fields, order included; elapsed timing excluded because separate run')

def simulation_files():
    return [CACHE/f for f in ('cases.tsv','case-meta.json','demand.json','riders.csv','groups.csv','days.csv','ledger.tsv','diagnostics.csv','C10DetailedSimulation.java')]+[
      HERE/'.cache/osrm/matrix.tsv',HERE/'.cache/kr-public/tims-errors.csv',HERE/'.cache/c7/riders.csv',HERE/'.cache/c7/groups.csv']

def record_simulation():
    run.json_write(CACHE/'simulation-receipt.json',dict(completed_at=datetime.now(timezone.utc).isoformat(),
      files={str(p.relative_to(REPO)):run.sha(p) for p in simulation_files()}))

def verify_simulation():
    receipt=json.loads((CACHE/'simulation-receipt.json').read_text(encoding='utf-8'))
    for relative,digest in receipt['files'].items():
        if run.sha(REPO/relative)!=digest:raise ValueError('Simulation cache changed: '+relative)

def compile_adapter():
    CACHE.mkdir(parents=True,exist_ok=True)
    receipt=json.loads((HERE/'.cache/c7/execution.json').read_text(encoding='utf-8'))
    for relative,digest in receipt['production_source']['compiled_source_sha256'].items():
        if relative.startswith(('server/','matching-engine/')) and run.sha(REPO/relative)!=digest:
            raise ValueError('C7 compiled production source changed: '+relative)
    src=CACHE/'C10DetailedSimulation.java'
    src.write_text(instrument((JAVA_SOURCE.parent/'DetailedSimulation.java').read_text(encoding='utf-8')),encoding='utf-8')
    command=json.loads((HERE/'.cache/c7/java-command.json').read_text(encoding='utf-8'))
    classes=CACHE/'classes';classes.mkdir(exist_ok=True)
    subprocess.run([str(Path(command[0]).with_name('javac.exe')),'-proc:none','-encoding','UTF-8','-cp',command[3],
      '-d',str(classes),str(JAVA_SOURCE),str(src)],cwd=REPO,check=True)
    java=[command[0],'-Xmx2048m','-cp',str(classes)+';'+command[3],'com.campustaximate.server.service.C10Prepay']
    (CACHE/'java-command.json').write_text(json.dumps(java),encoding='utf-8')
    subprocess.run(java+['--self-test'],cwd=REPO,check=True)
    return java,receipt['production_source']

def generate():
    points_data=HERE/'.cache/osrm'
    # Full cached matrix validation and all route/hash checks are inherited from C7.
    hub,points,hours,header,demand=c7.demand_inputs(None,HERE/'.cache',points_data)
    errors=[float(x) for x in (HERE/'.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    if run.sha(HERE/'.cache/kr-public/tims-errors.csv')!=json.loads((REPORTS/'public-fares.json').read_text(encoding='utf-8'))['tims']['errors']['sha256']:
        raise ValueError('C5 residual source mismatch')
    meta=c7.write_cases(CACHE/'cases.tsv',scenarios(),DAYS,SEED,points,hours,header,errors)
    (CACHE/'case-meta.json').write_text(json.dumps(meta),encoding='utf-8')
    return errors,demand

def assemble(errors,bias,source,demand):
    riders=c7.read_csv(CACHE/'riders.csv');groups=c7.read_csv(CACHE/'groups.csv')
    check=parity(riders,groups)
    rr=defaultdict(list);gg=defaultdict(list)
    for r in riders:rr[r['case_id']].append(r)
    for r in groups:gg[r['case_id']].append(r)
    meta=json.loads((CACHE/'case-meta.json').read_text())
    diagnostics={r['case_id']:r for r in c7.read_csv(CACHE/'diagnostics.csv')}
    daily=[]
    for d in c7.read_csv(CACHE/'days.csv'):
        cid=d['case_id'];m=c7.daily_metrics(d,rr[cid],gg[cid]);diag=diagnostics[cid]
        m.update({k:int(diag[k]) for k in ('rounds','candidate_cap_rounds','candidate_cap_anchors','eligible_anchors')})
        m['candidate_cap_round_rate']=m['candidate_cap_rounds']/m['rounds']
        m['budget_warning_round_rate']=m['budget_warning_rounds']/m['rounds']
        daily.append(dict(case_id=cid,**meta[cid],**m))
    # KRW savings are recomputed by the production ledger, including independent solo errors.
    fr=defaultdict(list)
    for r in c7.read_csv(CACHE/'finance.csv'):fr[(r['case_id'],r['rule'],float(r['spread']))].append(r)
    for d in daily:
        rows=fr.get((d['case_id'],'current',1.))
        if rows:
            d['mean_saving_krw']=statistics.mean(float(r['solo'])-float(r['net']) for r in rows)
            fm=financial_day(rows)
            assert abs(fm['additional_charge_rate']-d['ratio_additional_rate'])<1e-12
            assert abs(fm['harmed_rate']-d['ratio_harmed_rate'])<1e-12
    # Finance export covers all loads for KRW savings; report rule sweeps at 50,100,300 only.
    scale=[]
    keys=('matching_rate','mean_wait_minutes','mean_ratio_saving','mean_saving_krw','mean_delay_minutes',
      'groups_2_rate','groups_3_rate','groups_4_rate','ratio_additional_rate','ratio_harmed_rate','equal_harmed_rate',
      'candidate_cap_round_rate','budget_warning_round_rate')
    for sc in scenarios():
        ds=[d for d in daily if d['scenario']==sc.name]
        scale.append(dict(requests=sc.requests,days=len(ds),metrics={k:ci([d[k] for d in ds]) for k in keys},
          counts={k:sum(d[k] for d in ds) for k in ('rounds','candidate_cap_rounds','candidate_cap_anchors','eligible_anchors','budget_warning_rounds','conservation_errors')},
          max_round_ms=max(d['max_round_ms'] for d in ds)))
    finance=[]
    for n in (50,100,300):
        sc='base' if n==100 else f'n{n}'
        for spread in (.5,1.,1.5):
            for rule in rule_specs(bias['mean']):
                ds=[financial_day(fr[(f'{sc}_day{d:02}',rule['name'],spread)]) for d in range(DAYS)]
                finance.append(dict(requests=n,spread=spread,rule=rule,days=DAYS,
                  metrics={k:ci([d[k] for d in ds]) for k in ds[0]}))
    thresholds={}
    for target in (.5,.7,.8):
        reached=[s for s in scale if s['metrics']['matching_rate']['mean']>=target]
        first=reached[0] if reached else None
        below=[s['requests'] for s in scale if first and s['requests']<first['requests']]
        thresholds[str(target)]=dict(first_tested_requests=first['requests'] if first else None,
          crossing_bracket=[max(below),first['requests']] if below else None,
          rate=first['metrics']['matching_rate'] if first else None)
    buffers=[]
    for n in (50,100,300):
        for spread in (.5,1.,1.5):
            subset=[f for f in finance if f['requests']==n and f['spread']==spread and f['rule']['name'].startswith('buffer')]
            for target in (.05,.02):
                ok=[f for f in subset if f['metrics']['additional_charge_rate']['mean']<=target]
                confident=[f for f in subset if f['metrics']['additional_charge_rate']['ci95_high']<=target]
                buffers.append(dict(requests=n,spread=spread,target=target,
                  smallest_tested_buffer=ok[0]['rule']['buffer'] if ok else None,
                  rate=ok[0]['metrics']['additional_charge_rate'] if ok else None,
                  smallest_upper_ci_passing_buffer=confident[0]['rule']['buffer'] if confident else None,
                  max_tested_buffer=150))
                # Same day resamples across all buffers; censored replicates stay explicit.
                bs=np.stack([weights(DAYS) @ np.asarray([financial_day(fr[(f"{('base' if n==100 else 'n'+str(n))}_day{d:02}",f"buffer{b}",spread)])['additional_charge_rate'] for d in range(DAYS)]) for b in BUFFERS],axis=1)
                required=np.asarray([next((BUFFERS[j] for j,v in enumerate(row) if v<=target),151) for row in bs])
                bounds=np.quantile(required,[.025,.975],method='inverted_cdf')
                buffers[-1]['minimum_tested_buffer_bootstrap_ci95']=[int(v) if v<=150 else '>150' for v in bounds]
                buffers[-1]['bootstrap_above_150_probability']=float(np.mean(required>150))
    report=dict(schema_version=1,status='COMPLETED',generated_at=datetime.now(timezone.utc).isoformat(),seed=SEED,days=DAYS,
      scale=scale,scale_thresholds=thresholds,finance=finance,buffer_thresholds=buffers,
      bias_fit={k:v for k,v in bias.items() if k not in ('train_indices','heldout_indices','evaluation_indices')},
      parity=check,production_source=source,demand=demand,
      uncertainty='일별 지표 평균; 일자 군집 부트스트랩 10,000회, 시드 20261119, 2.5/97.5 백분위. 오차 분포 추정 불확실성 및 실제 학생 효과는 포함하지 않음.',
      enrollment=dict(status='NOT_ACQUIRED',sources=['https://www.data.go.kr/data/15164558/fileData.do',
        'https://m.academyinfo.go.kr/pubinfo/pubinfo0020/list.do?schlId=0000097'],
        reason='원문 카탈로그와 대학 페이지를 열었으나 정적 응답에 대진대 학생 수가 없었음. 숫자를 임의 사용하지 않음.',
        participation_formula='학생 수 S 확인 시 요청수 N의 학생 대비 비율은 100*N/S %. 1인 하루 1요청이라는 시나리오이며 참여율 추정치가 아님.'),
      verification=dict(conservation_errors=sum(int(r['conservation_errors']) for rows in fr.values() for r in rows),
        simulated_requests=sum(s.requests*DAYS for s in scenarios()),new_route_api_calls=0),
      definitions=dict(financial_denominator='추가/환불/선결제: 비결제자. 금액 평균/P90: 해당 사건 발생자만, 사건 없는 날 null 제외. all_nonpayers는 0원 포함. P90은 일별 P90의 평균.',
        payer_loss='결제자 max(0,실제 순부담-동일 오차 배수 단독 실제 요금); 결제자 부담 변화는 현행 실제 몫 대비 면제 이전액.',
        harmed='전체 실제 탑승자 중 순부담>독립 단독 실제 요금. 버퍼·견적 보정은 최종 몫 불변; 면제만 결제자로 부담 이전.',
        candidate_cap='생산 TripPlan.eligible 및 CandidateRanker.nearestByAngle의 동일 회원·차단 필터로 풀별 peers>11인 기준자/회차 수. 시간 제한 이전의 cap 노출 계측이며 실제 rank 호출 수가 아님.',
        budget='회차 전체 경과시간>=생산 searchMillis(1000ms), 검증 포함 보수적 경고. 정확한 deadline 분기나 100000-node 중단 횟수는 생산 API가 노출하지 않음.',
        sensitivity='TIMS 격자-월 집계 잔차는 견적-미터 오차의 대리값. 전체 평균 유지, 동일 그룹 및 개인 잔차의 편차만 .5/1/1.5배. 1.5배 변환에서 비양수인 가상 요금은 1원 하한 처리하고 해당 비율 별도 공개. 이 하한 이후 평균은 정확히 보존되지 않음.'),
      recommendation='하루 100건·기본 오차에서 추가 결제율의 95% CI 상한까지 5% 이하를 목표로 하면 130% 버퍼, 2% 이하를 목표로 하면 140% 버퍼를 권한다. 점추정만 보면 최소 125%/130%지만 각 CI 상한은 목표를 넘는다. 편차 1.5배에서도 보수적으로 5%/2%를 만족하려면 140%/150%다. 편향 보정+120%는 기본에서 더 적은 선결제액으로 5% 목표를 만족하는 후보지만, 실제 Kakao-미터 검증 없이 TIMS 평균을 운영 견적에 적용하는 것은 보류한다. 소액 면제는 결제자의 손실을 늘리므로 기본 규칙으로 권하지 않는다. 이 권고는 실험 결과이며 생산 규칙을 변경하지 않았다.',
      artifacts={str(p.relative_to(REPO)):run.sha(p) for p in (Path(__file__),JAVA_SOURCE,CACHE/'C10DetailedSimulation.java',CACHE/'cases.tsv',CACHE/'ledger.tsv',CACHE/'finance.csv',CACHE/'bias-split.json',CACHE/'simulation-receipt.json',HERE/'tests/test_scale_and_prepay.py',HERE/'.cache/kr-public/tims-errors.csv',HERE/'.cache/osrm/matrix.tsv')})
    run.json_write(REPORTS/'c10-scale-and-prepay.json',report)
    (REPORTS/'c10-scale-and-prepay.md').write_text(markdown(report),encoding='utf-8')
    run.json_write(CACHE/'daily.json',daily)
    return report

def rule_specs(bias):
    return [dict(name='current',buffer=115,bias=0.,waiver=0)]+[
      dict(name=f'buffer{b}',buffer=b,bias=0.,waiver=0) for b in BUFFERS]+[
      dict(name=f'bias{b}',buffer=b,bias=bias,waiver=0) for b in (115,120,130)]+[
      dict(name=f'waiver{w}',buffer=115,bias=0.,waiver=w) for w in (300,500,1000)]

def markdown(r):
    cell=c7.cell
    lines=['# C10 수요 규모와 선결제 규칙 비교','',
      '생산 코드는 변경하지 않았다. C7과 동일한 생성기·일자 시드·15분 지연·10% 절약 기준·즉시 30%·노쇼/미납 0%로 실행했다. 각 조건 30일이며 실제 학생 운영 성과가 아니다.',
      '',r['uncertainty'],'','## 수요 규모 곡선','',
      '| 요청/일 | 매칭률 | 대기 분 | 절약률 | 절약 원 | 지연 분 | 추가 결제율 | 손해율 |',
      '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for s in r['scale']:
        m=s['metrics'];lines.append('| '+str(s['requests'])+' | '+' | '.join(cell(m[k],pct) for k,pct in [
          ('matching_rate',True),('mean_wait_minutes',False),('mean_ratio_saving',True),('mean_saving_krw',False),
          ('mean_delay_minutes',False),('ratio_additional_rate',True),('ratio_harmed_rate',True)])+' |')
    lines+=['','문턱은 연속 수요를 추정한 값이 아니라 처음 넘은 시험 지점이다.']
    for t,s in r['scale_thresholds'].items():lines.append(f"- 매칭률 {float(t):.0%}: {s['first_tested_requests']}건/일; 교차 구간 {s['crossing_bracket']}; 도달 지점 CI {s['rate']}")
    lines+=['','| 요청/일 | 2명 일행 | 3명 | 4명 | 후보 cap 노출 회차/전체 | 시간 경고/전체 |','|---:|---:|---:|---:|---:|---:|']
    for s in r['scale']:
        m=s['metrics'];c=s['counts'];lines.append('| '+str(s['requests'])+' | '+' | '.join(cell(m[f'groups_{i}_rate'],True) for i in (2,3,4))+f" | {c['candidate_cap_rounds']}/{c['rounds']} | {c['budget_warning_rounds']}/{c['rounds']} |")
    lines+=['',r['definitions']['candidate_cap'],r['definitions']['budget'],'','## 학생 수 대비 해석','',
      r['enrollment']['reason'],r['enrollment']['participation_formula'],'',
      '[대학알리미 원문 카탈로그](https://www.data.go.kr/data/15164558/fileData.do) · [대진대 공시 페이지](https://m.academyinfo.go.kr/pubinfo/pubinfo0020/list.do?schlId=0000097)',
      '','## 동일 일행·실제 요금 선결제 비교','',
      '비율 정산 기준이다. 모든 규칙에서 버퍼가 최종 요금을 할인하지는 않는다. 면제 W는 부족분 전체가 W 이하일 때만 면제하며 원래 결제자가 전액 흡수한다. 플랫폼 부담은 0원이다.',
      '',r['definitions']['financial_denominator'],r['definitions']['payer_loss'],
      '',f"편향 보정 적합 평균: {r['bias_fit']['mean']:.6%}; 훈련 {r['bias_fit']['train_n']}행, 보류 {r['bias_fit']['heldout_n']}행. C7에서 이미 사용한 그룹·개인 잔차의 원래 행 번호 전부를 보류에 고정하고, 남은 행 번호를 잔차값을 보지 않고 시드로 섞어 절반을 훈련에 할당했다. 서로 다른 격자-월 행의 같은 수치는 별도 관측이다. 일행별 실현 청구액을 바꾸지 않으면서 훈련 누수를 막기 위한 draw-disjoint 분할이며 지리·월별 외삽 검증은 아니다."]
    for n in (50,100,300):
      for spread in (.5,1.,1.5):
        lines+=['',f'### 하루 {n}건 · 오차 편차 {spread}배','',
          '| 규칙 | 추가율 | 추가 평균/P90 원 | 환불률 | 환불 평균/P90 원 | 선결제 원 | 결제자 부담 증가 원 | 결제자 단독 대비 손실 원 | 전체 손해율 | 보존 오류 |',
          '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        for f in r['finance']:
          if (f['requests'],f['spread'])!=(n,spread):continue
          m=f['metrics'];lines.append('| '+f['rule']['name']+' | '+cell(m['additional_charge_rate'],True)+' | '+
            cell(m['mean_extra_krw'])+' / '+cell(m['p90_extra_krw'])+' | '+cell(m['refund_rate'],True)+' | '+
            cell(m['mean_refund_krw'])+' / '+cell(m['p90_refund_krw'])+' | '+cell(m['mean_upfront_krw'])+' | '+
            cell(m['mean_payer_burden_change_krw'])+' | '+cell(m['mean_payer_loss_vs_solo_krw'])+' | '+
            cell(m['harmed_rate'],True)+' | '+str(m['conservation_errors']['mean'])+' |')
    lines+=['','## 버퍼 목표','',
      '| 요청/일 | 편차 배수 | 추가율 목표 | 최소 시험 버퍼 (버퍼 95% 구간) | 그 추가율 [CI] | CI 상한까지 목표 이하인 최소 버퍼 |',
      '|---:|---:|---:|---:|---:|---:|']
    for t in r['buffer_thresholds']:
        lines.append(f"| {t['requests']} | {t['spread']} | {t['target']:.0%} | {t['smallest_tested_buffer'] or '150% 이내 미도달'} ({t['minimum_tested_buffer_bootstrap_ci95']}) | "+
          (cell(t['rate'],True) if t['rate'] else '미도달')+f" | {t['smallest_upper_ci_passing_buffer'] or '미도달'} |")
    lines+=['','버퍼 구간은 같은 일자를 짝지어 재표집한 각 반복에서 목표 이하인 첫 시험 버퍼의 2.5/97.5 백분위다. 150% 밖은 >150으로 검열하며, 미시험 버퍼를 추정하지 않는다.', '', '## 권고와 한계','',r['recommendation'],r['definitions']['sensitivity'],
      'JSON의 sensitivity_group_floor_rate/sensitivity_solo_floor_rate는 이 양수 하한 발생 비율이다. 기준 1배 비교에는 하한이 발생하지 않는다.',
      '평균+6.62%·편차 28.29pp라는 지시서 요약 대신 입력 파일의 실제 잔차를 그대로 사용했다. TIMS 집계 오차와 Kakao 견적-실제 미터 오차가 같다는 검증은 없다. 환불·추가 결제 이행은 전액 성공을 가정한다. 선결제 인상에 따른 수요 이탈·카드 한도·결제 실패는 모델링하지 않았다.',
      'C7 캐시의 100건/일 모든 승객·일행 필드를 새 실행과 대조하여 정확히 일치했다. 지시서의 이전 기준 수치는 현 산출물로 대체한다.',
      f"전체 보존 오류 {r['verification']['conservation_errors']}건. 매칭 요청 {r['verification']['simulated_requests']:,}건. 새 경로 API 호출 0회.",
      '재현: experiments/.cache/venv/python.exe experiments/scale_and_prepay.py; 집계 재실행: 같은 명령에 --reuse-output. 입력·도구·정산 원장 해시는 JSON artifacts, 생산 소스 해시는 production_source에 기록한다.']
    return '\n'.join(lines)+'\n'

def main():
    p=argparse.ArgumentParser();p.add_argument('--reuse-output',action='store_true');args=p.parse_args()
    CACHE.mkdir(parents=True,exist_ok=True)
    java,source=compile_adapter()
    errors_path=HERE/'.cache/kr-public/tims-errors.csv'
    if not args.reuse_output:
        errors,demand=generate()
        run.json_write(CACHE/'demand.json',demand)
        with (CACHE/'execution.log').open('w',encoding='utf-8') as log:
            subprocess.run(java+[str(CACHE/'cases.tsv'),str(HERE/'.cache/osrm/matrix.tsv'),str(errors_path),
              *[str(CACHE/f) for f in ('riders.csv','groups.csv','days.csv','ledger.tsv','diagnostics.csv')]],
              cwd=REPO,check=True,stdout=log,stderr=log)
        record_simulation()
    else:
        verify_simulation()
        errors=[float(x) for x in errors_path.read_text().splitlines() if x.strip()]
        demand=json.loads((CACHE/'demand.json').read_text(encoding='utf-8'))
    with (CACHE/'ledger.tsv').open(encoding='utf-8',newline='') as f:ledger=list(csv.DictReader(f,delimiter='\t'))
    ledger=add_solo_indices(ledger)
    bias=fit_bias(errors,ledger);run.json_write(CACHE/'bias-split.json',bias)
    rules=rule_specs(bias['mean'])
    (CACHE/'rules.csv').write_text('\n'.join(f"{r['name']},{r['buffer']},{r['bias']},{r['waiver']}" for r in rules),encoding='utf-8')
    subprocess.run(java+['--rules',str(CACHE/'ledger.tsv'),str(CACHE/'rules.csv'),str(errors_path),str(CACHE/'finance.csv')],cwd=REPO,check=True)
    r=assemble(errors,bias,source,demand)
    print(json.dumps(dict(status=r['status'],thresholds=r['scale_thresholds'],verification=r['verification']),ensure_ascii=False))

if __name__=='__main__':main()
