"""C9 dropoff design comparison on the original ninety C7 demand days."""
from pathlib import Path
import argparse, collections, csv, hashlib, json, os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import detailed as d
from osrm_matrix import digest,save
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent;CACHE=HERE/'.cache/c9'
SCENARIOS={'n50_d15':50,'base':100,'participation150':150}
POLICIES=('A','B-5','B-10','B-20','C-walk','C-taxi','D0','D15','D30')
METRICS=('match_rate','mean_saving_krw','total_saving_krw','mean_saving_ratio','mean_walking_minutes','walking_over10_share','mean_delay_minutes','harmed_rate','accepted_suggestions')

def compile_adapter():
    CACHE.mkdir(exist_ok=True);classes=CACHE/'classes';classes.mkdir(exist_ok=True)
    receipt=json.loads((HERE/'.cache/c7/execution.json').read_text(encoding='utf-8'));source=receipt['production_source']
    production=sorted((ROOT/'matching-engine/src/main/java').rglob('*.java'))+sorted((ROOT/'server/src/main/java').rglob('*.java'))
    for p in production:
        if digest(p)!=source['source_sha256'][p.relative_to(ROOT).as_posix()]:raise ValueError('Production drift: '+str(p))
    deps=[HERE/'.cache/deps'/name for name in sorted(source['dependency_sha256'])]
    for p in deps:
        if digest(p)!=source['dependency_sha256'][p.name]:raise ValueError('Dependency drift')
    java=Path(json.loads((HERE/'.cache/c7/java-command.json').read_text(encoding='utf-8'))[0]);classpath=os.pathsep.join(map(str,deps));lombok=next((HERE/'.cache/deps').glob('lombok-*.jar'));classpath+=os.pathsep+str(lombok)
    adapters=sorted((HERE/'java').rglob('*.java'));args=['-source','21','-target','21','-encoding','UTF-8','-cp',classpath,'-processorpath',str(lombok),'-d',str(classes),*map(str,production+adapters)]
    (CACHE/'build.args').write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf-8')
    with (CACHE/'build.log').open('w',encoding='utf-8') as log:subprocess.run([str(java.parent/'javac'),'@'+str(CACHE/'build.args')],cwd=ROOT,stdout=log,stderr=log,check=True)
    command=[str(java),'-Xmx1536m','-cp',str(classes)+os.pathsep+classpath,'com.campustaximate.server.service.DropoffSimulation']
    save(CACHE/'java-command.json',command)
    return command,dict(production_source_sha256=source['source_sha256'],dependency_sha256=dict(source['dependency_sha256'],**{lombok.name:digest(lombok)}),adapter_source_sha256={p.relative_to(ROOT).as_posix():digest(p) for p in adapters})

def cases():
    original=HERE/'.cache/c7/cases.tsv';receipt=json.loads((HERE/'.cache/c7/execution.json').read_text(encoding='utf-8'))
    if digest(original)!=receipt['input_sha256'][str(original)]:raise ValueError('C7 case hash changed')
    chunks=[]
    for block in original.read_text(encoding='utf-8').split('END\n'):
        if not block.strip():continue
        cid=block.splitlines()[0].split('\t')[2];sc,day=cid.rsplit('_day',1)
        if sc in SCENARIOS and int(day)<30:chunks.append(block+'END\n')
    if len(chunks)!=90:raise AssertionError('Need 90 original days')
    p=CACHE/'cases.tsv';p.write_text(''.join(chunks),encoding='utf-8');return p

def read_rows(path):
    with Path(path).open(encoding='utf-8-sig',newline='') as f:return list(csv.DictReader(f))

def measure(rows,cid,policy,n):
    m=len(rows);groups=collections.defaultdict(list)
    if len({r['id'] for r in rows})!=m:raise AssertionError('Duplicate rider')
    for r in rows:
        for field in ('solo','shared_share','last_cost','total_cost','expected_share','expected_bill','actual_bill','size','suggestion'):r[field]=int(r[field])
        for field in ('walking','delay'):r[field]=float(r[field])
        if r['shared_share']+r['last_cost']!=r['total_cost']:raise AssertionError('Total rider cost')
        groups[r['group']].append(r)
    for group in groups.values():
        if len(group)!=group[0]['size'] or sum(r['shared_share'] for r in group)!=group[0]['actual_bill'] or sum(r['expected_share'] for r in group)!=group[0]['expected_bill']:raise AssertionError('Group conservation')
    if m>n:raise AssertionError('Request conservation')
    saving=sum(r['solo']-r['total_cost'] for r in rows)
    return dict(case_id=cid,scenario=cid.rsplit('_day',1)[0],policy=policy,requests=n,matched=m,solo=n-m,match_rate=m/n,
        mean_saving_krw=saving/m if m else None,total_saving_krw=saving,mean_saving_ratio=sum(1-r['total_cost']/r['solo'] for r in rows)/m if m else None,
        mean_walking_minutes=sum(r['walking'] for r in rows)/m if m else None,walking_over10_share=sum(r['walking']>10 for r in rows)/m if m else None,
        mean_delay_minutes=sum(r['delay'] for r in rows)/m if m else None,harmed_rate=sum(r['total_cost']>r['solo'] for r in rows)/m if m else None,
        accepted_suggestions=sum(r['suggestion'] for r in rows),groups=len(groups),
        group_size_distribution={str(k):sum(g[0]['size']==k for g in groups.values())/len(groups) if groups else 0 for k in (2,3,4)},
        group_size_counts={str(k):sum(g[0]['size']==k for g in groups.values()) for k in (2,3,4)},conservation_errors=0)

def aggregate(daily,field='results'):
    results={};paired={};d_control={}
    for scenario,n in SCENARIOS.items():
        results[scenario]={};paired[scenario]={};by={p:sorted([r for r in daily if r['scenario']==scenario and r['policy']==p],key=lambda r:r['case_id']) for p in POLICIES}
        for p,rows in by.items():
            if len(rows)!=30:raise AssertionError('Missing days')
            results[scenario][p]={m:d.ci([r[m] for r in rows]) for m in METRICS}
            for key in ('group_size_distribution','group_size_counts'):
                results[scenario][p][key]={str(k):d.ci([r[key][str(k)] for r in rows]) for k in (2,3,4)}
            paired[scenario][p]={m:d.ci([r[m]-a[m] if r[m] is not None and a[m] is not None else None for r,a in zip(rows,by['A'])]) for m in METRICS}
            paired[scenario][p]['group_size_distribution']={str(k):d.ci([r['group_size_distribution'][str(k)]-a['group_size_distribution'][str(k)] for r,a in zip(rows,by['A'])]) for k in (2,3,4)}
        d_control[scenario]={p:{m:d.ci([r[m]-a[m] if r[m] is not None and a[m] is not None else None for r,a in zip(by[p],by['D0'])]) for m in METRICS} for p in ('D15','D30')}
    return results,paired,d_control

def fmt(x,scale=1):
    if x['mean'] is None:return 'NA'
    return f"{scale*x['mean']:.2f} [{scale*x['ci95_low']:.2f}, {scale*x['ci95_high']:.2f}]"

def markdown(report):
    lines=['# C9 하차 서비스 설계 비교','',f"상태: {report['status']}. C7의 동일한 90개 수요일(50·100·150명/일, 각 30일). 모든 값은 일별 평균 [일 단위 부트스트랩 95% CI]. 실제 운영 실적이 아닌 공개자료 기반 모의실험이다.",'',
    '## 설계와 계산 규칙','',
    '- A: 기존 문 앞 하차. B-5/10/20: 지정 K개 허브 중 수요격자→허브 네트워크 최단거리로 배정. C-walk/C-taxi: 매 회차 생산 문 앞 매칭을 먼저 실행하고, 남은 풀에 마지막 기회 요청이 있으면 2~4명 문 앞/허브 혼합 제안을 시도한다. C의 K는 아래 사전 규칙으로 결정한다.',
    '- 허브 후보: Kakao SW8 역·철도역, 버스터미널 키워드 검색 및 포천/소흘 시내 실제 버스정류장. 영역 검색은 45개 초과 시 사각형을 재분할하고 끝 페이지까지 수집한다. 시내 보강 키워드는 API 최대 45건이며 완전한 정류장 목록은 아니다.',
    '- p-median은 수요확률 가중 네트워크 거리의 greedy BUILD 후 모든 개선 1-swap을 반복한 국소해다. 전역 최적해를 주장하지 않는다.',
    '- 보행 거리는 WGS84 좌표의 대권거리 × 1.3, 속도 4.5km/h이다. 1.2km 이하는 걷고, 초과하면 같은 C7 출발지 요금모형으로 허브→문 앞 단독택시 요금을 더한다. 자동차 구간은 OSRM car, C7과 동일한 시간 보정계수를 적용한다.',
    '- B는 모든 구성원의 예상 공유분담금+라스트마일 요금이 원래 문 앞 단독 견적보다 엄격하게 저렴해야 확정한다. C는 모든 구성원이 최소 10% 절약 및 최종 목적지 기준 15분 이내 추가시간을 만족해야 한다. C-walk는 택시 라스트마일을 허용하지 않는다.',
    '- 개인 합리성·수용은 예약 시 알 수 있는 예상 견적 기준이다. 실현 공유요금과 원래 단독요금은 C7의 같은 TIMS 잔차 추출을 재사용하므로 사후 harmed는 0으로 보장되지 않는다. 라스트마일 택시는 동일 견적 요금을 비용으로 사용하며 별도의 잔차는 추정하지 않는다.',
    '- 공유비는 실제 생산 비율정산 계산기, 가능성/경로검사는 생산 EstimateCalculator와 RouteGroupVerifier, A/B/D 선택은 생산 MatchingRound를 호출한다. 허브 대안도 목적지가 변경된 생산 지연·절약 검사를 통과해야 하므로 원래 문 앞 기준으로는 가능한 일부 대안이 보수적으로 탈락할 수 있다.',
    '- C 제안은 동일 풀의 2~4명과 각 구성원의 문 앞/배정 허브 조합을 열거하고, 마지막 기회 인원→총 인원→기다린 시간→거리 절약 순서의 결정적 탐욕 포장으로 선택한다. 배정 이외의 허브, 전체 일자 최적 제안은 탐색하지 않는다.',
    '- D0/D15/D30: 즉시요청의 원래 유효 출발창(원래 요청~5분 모집의 확정시각+준비·배차)을 고정 예약창으로 표현한다. 예약요청 창은 그대로 둔다. 요청시각만 0/15/30분 앞당긴다. 즉시→예약 전환의 5분 회차·조기확정 차이가 있으므로 A 대비와 D0 대비를 함께 제시한다.',
    '- 절약·보행·지연·harmed 평균과 10분 초과 비율의 분모는 매칭 승객, 총 절약은 일 전체이며 미매칭은 원래 단독택시(절약·보행·지연 0)다. 그룹분포는 그룹 수 기준 일별 비율. accepted_suggestions는 실제 확정 제안 중 허브를 선택한 승객 수다. 지연은 차 안 시간+라스트마일−원래 단독시간의 양수부이며 예약 대기는 포함하지 않는다.',
    '', '## 허브 선택','',f"기본 K={report['hubs']['default_k']}. 규칙: K20의 수요가중 네트워크 거리의 1.25배 이내인 가장 작은 K. 성능표를 보기 전에 선택한다.",'','| K | 수요가중 격자→허브 km |','|---|---:|']
    for k,v in report['hubs']['sets'].items():lines.append(f"| {k} | {v['weighted_network_km']:.3f} |")
    base=report['results']['base'];diff=report['paired_vs_A']['base']
    lines+=['','## 결과 해석','',f"100명/일에서 A 매칭률은 {base['A']['match_rate']['mean']*100:.2f}%, 평균 절약률은 {base['A']['mean_saving_ratio']['mean']*100:.2f}%다. B 전면 허브 하차는 세 K 모두 A보다 매칭률과 일 총 절약액이 낮았다. 라스트마일 비용과 개인 합리성 조건을 포함한 현재 모형에서는 전면 허브 전환의 이득을 관찰하지 못했다.",f"혼합 제안의 A 대비 매칭률 차이는 walk-only {fmt(diff['C-walk']['match_rate'],100)}pp, walk-or-taxi {fmt(diff['C-taxi']['match_rate'],100)}pp다. 허브 수용은 각각 하루 평균 {base['C-walk']['accepted_suggestions']['mean']:.2f}명, {base['C-taxi']['accepted_suggestions']['mean']:.2f}명으로 제한적이다.",f"15분 사전예약은 A 대비 {fmt(diff['D15']['match_rate'],100)}pp, 30분은 {fmt(diff['D30']['match_rate'],100)}pp다. 더 긴 예약이 항상 더 나은 것은 아니다. D0 전환 자체의 손실과 생산 온라인 선택·회차에 따른 결과를 함께 해석해야 한다."]
    lines+=['','## 결과 비교','','단위: 매칭·절약률·harmed·10분 초과는 %, 금액 원, 시간 분.','', '| 일 수요 | 설계 | 매칭 % | 평균 절약 원/매칭인 | 총 절약 원/일 | 평균 절약 % |','|---|---|---:|---:|---:|---:|']
    for sc,pols in report['results'].items():
        for p,m in pols.items():lines.append('| '+' | '.join([str(SCENARIOS[sc]),p,fmt(m['match_rate'],100),fmt(m['mean_saving_krw']),fmt(m['total_saving_krw']),fmt(m['mean_saving_ratio'],100)])+' |')
    lines+=['','| 일 수요 | 설계 | 보행 분 | 보행>10분 % | 지연 분 | harmed % | 허브 수용 인/일 |','|---|---|---:|---:|---:|---:|---:|']
    for sc,pols in report['results'].items():
        for p,m in pols.items():lines.append('| '+' | '.join([str(SCENARIOS[sc]),p,fmt(m['mean_walking_minutes']),fmt(m['walking_over10_share'],100),fmt(m['mean_delay_minutes']),fmt(m['harmed_rate'],100),fmt(m['accepted_suggestions'])])+' |')
    lines+=['','## A 대비 일별 대응 차이','','양수는 해당 지표가 A보다 큼. 모든 금액·보행·지연·harmed·제안수 차이와 CI는 JSON에 포함한다.','', '| 일 수요 | 설계 | 매칭 pp | 평균 절약 원 | 총 절약 원/일 | 보행 분 | 지연 분 | harmed pp |','|---|---|---:|---:|---:|---:|---:|---:|']
    for sc,pols in report['paired_vs_A'].items():
        for p,m in pols.items():
            if p=='A':continue
            lines.append('| '+' | '.join([str(SCENARIOS[sc]),p,fmt(m['match_rate'],100),fmt(m['mean_saving_krw']),fmt(m['total_saving_krw']),fmt(m['mean_walking_minutes']),fmt(m['mean_delay_minutes']),fmt(m['harmed_rate'],100)])+' |')
    lines+=['','## 사전예약 대조와 hindsight 격차','','| 일 수요 | 설계 | D0 대비 매칭 pp | D0 대비 총 절약 원 | A 대비 C8 hindsight 격차 회복률 % |','|---|---|---:|---:|---:|']
    for sc,pols in report['advance_vs_D0'].items():
        for p,m in pols.items():lines.append('| '+' | '.join([str(SCENARIOS[sc]),p,fmt(m['match_rate'],100),fmt(m['total_saving_krw']),fmt(report['hindsight_recovery'][sc][p],100)])+' |')
    lines+=['','회복률은 일별 (D 매칭률−A 매칭률)/(C8 hindsight 매칭률−A 매칭률)의 평균이다. 고정 창의 표현·회차 전환이 섞이므로 사전예약만의 인과효과나 실제 운영 개선으로 해석할 수 없다.','', '## 그룹 크기 분포','','| 일 수요 | 설계 | 2명 % | 3명 % | 4명 % |','|---|---|---:|---:|---:|']
    for sc,pols in report['results'].items():
        for p,m in pols.items():lines.append('| '+' | '.join([str(SCENARIOS[sc]),p,*[fmt(m['group_size_distribution'][str(k)],100) for k in (2,3,4)]])+' |')
    lines+=['','## 실제 허브 목록','','수요 커버는 해당 K에서 이 허브로 배정된 전체 C7 격자 수요확률이다. 보행 가능 범위의 커버율과 다르다.','', '| K | 이름 | 위도 | 경도 | 배정 수요 % | 격자 수 |','|---|---|---:|---:|---:|---:|']
    for k,v in report['hubs']['sets'].items():
        for h in v['hubs']:lines.append(f"| {k} | {h['name']} | {h['lat']:.7f} | {h['lon']:.7f} | {100*h['demand_covered']:.3f} | {h['assigned_grids']} |")
    lines+=['','## 검증','',json.dumps(report['verification'],ensure_ascii=False,indent=2),'','입력·검색 응답·노선표·생산 소스·실험 소스 해시는 JSON과 experiments/.cache/hubs/search-manifest.json에 기록했다. 승객별 정산과 일별 집계는 experiments/.cache/c9에 보존한다.','', '## 한계','',
    '- 라스트마일 행동과 보행 수용은 가정이며, 실제 승객 수용을 검증하지 않았다. 걷기 경로·횡단·언덕·기상·환승 접근성을 측정하지 않았다.',
    '- 허브 선택은 모형 기반이다. 교통노드 검색 후보와 단일 배정·국소 p-median에 의존하며, 실제 승하차 공간·정차 허가·시설 안전성은 확인하지 않았다.',
    '- 공개 TIMS 격자와 생활이동을 결합한 대리수요이며 학생 개별 이동 기록이 아니다. 30일 CI는 생성 수요일 변동만 반영하고 허브·요금·시간·행동 가정의 모형 불확실성을 포함하지 않는다.',
    '- 자동차 OSRM은 교통상황·배차·대기·수요 공급 제약을 재현하지 못한다. 요금은 C7 견적 근사식을 재사용했으며 실제 미터·행정구역별 모든 운임·통행료를 재현하지 않는다.',
    '- 사후 요금 잔차로 개인 합리성의 예상과 실현은 다를 수 있다. hub 요금의 잔차와 라스트마일 택시의 독립 잔차 자료는 없어 후자는 견적 고정이다.',
    '- 생산 매처의 후보/검색/검증 예산과 예약 회차는 유지되며 C 제안은 실험용 탐욕 정책이다. 모든 설계가 전역 최적인 서비스라는 결론을 내릴 수 없다.',
    '- 생산 코드는 수정하지 않았으며 커밋하지 않았다.']
    return '\n'.join(lines)+'\n'

def main():
    p=argparse.ArgumentParser();p.add_argument('--reuse',action='store_true');args=p.parse_args();CACHE.mkdir(exist_ok=True)
    if not args.reuse:
        command,prov=compile_adapter();casefile=cases();hubs=json.loads((HERE/'.cache/hubs/selection.json').read_text(encoding='utf-8'))
        with (CACHE/'run.log').open('w',encoding='utf-8') as log:subprocess.run(command+list(map(str,[casefile,HERE/'.cache/osrm/matrix.tsv',HERE/'.cache/hubs/matrix.tsv',HERE/'.cache/hubs/assignments.tsv',HERE/'.cache/kr-public/tims-errors.csv',CACHE/'riders.csv',hubs['default_k']])),cwd=ROOT,stdout=log,stderr=log,check=True)
        prov['inputs']={str(q):digest(q) for q in (casefile,HERE/'.cache/c7/cases.tsv',HERE/'.cache/osrm/matrix.tsv',HERE/'.cache/hubs/matrix.tsv',HERE/'.cache/hubs/assignments.tsv',HERE/'.cache/hubs/selection.json',HERE/'.cache/kr-public/tims-errors.csv')}
        prov['riders_sha256']=digest(CACHE/'riders.csv');save(CACHE/'execution.json',prov)
    else:
        prov=json.loads((CACHE/'execution.json').read_text(encoding='utf-8'))
        if digest(CACHE/'riders.csv')!=prov['riders_sha256']:raise ValueError('Changed riders')
        for q,h in prov['inputs'].items():
            if digest(q)!=h:raise ValueError('Changed input '+q)
        for q,h in prov['production_source_sha256'].items():
            if digest(ROOT/q)!=h:raise ValueError('Changed production '+q)
        for q,h in prov['adapter_source_sha256'].items():
            if digest(ROOT/q)!=h:raise ValueError('Changed adapter '+q)
        hubs=json.loads((HERE/'.cache/hubs/selection.json').read_text(encoding='utf-8'))
    grouped=collections.defaultdict(list)
    for row in read_rows(CACHE/'riders.csv'):grouped[row['case_id'],row['policy']].append(row)
    daily=[measure(grouped[f'{sc}_day{day:02}',policy],f'{sc}_day{day:02}',policy,n) for sc,n in SCENARIOS.items() for day in range(30) for policy in POLICIES]
    save(CACHE/'daily.json',daily);results,paired,control=aggregate(daily)
    old={r['case_id']:r for r in read_rows(HERE/'.cache/c7/days.csv')};c8=json.loads((HERE/'.cache/c8/daily.json').read_text(encoding='utf-8'));c8prod={r['case_id']:r for r in c8 if r['policy']=='PROD'};hindsight={r['case_id']:r for r in c8 if r['policy']=='HINDSIGHT'}
    parity=[]
    for r in daily:
        if r['policy']=='A' and r['matched']!=int(old[r['case_id']]['matched']):parity.append(dict(case_id=r['case_id'],c7=int(old[r['case_id']]['matched']),c9=r['matched']))
    value_parity=[]
    for r in daily:
        if r['policy']!='A':continue
        oldrow=c8prod[r['case_id']]
        for newkey,oldkey in [('total_saving_krw','total_saving_krw'),('mean_saving_ratio','mean_saving'),('mean_delay_minutes','mean_delay')]:
            if abs(r[newkey]-oldrow[oldkey])>1e-8:value_parity.append(dict(case_id=r['case_id'],metric=newkey,difference=r[newkey]-oldrow[oldkey]))
    recovery={sc:{p:d.ci([(next(r['match_rate'] for r in daily if r['case_id']==a['case_id'] and r['policy']==p)-a['match_rate'])/(hindsight[a['case_id']]['match_rate']-a['match_rate']) for a in daily if a['scenario']==sc and a['policy']=='A' and hindsight[a['case_id']]['match_rate']>a['match_rate']]) for p in ('D15','D30')} for sc in SCENARIOS}
    report=dict(status='COMPLETED' if not parity and not value_parity else 'BASELINE_MISMATCH',days_per_demand=30,results=results,paired_vs_A=paired,advance_vs_D0=control,hindsight_recovery=recovery,hubs=hubs,
        verification=dict(demand_days=90,policy_days=len(daily),rider_settlements=sum(r['matched'] for r in daily),conservation_errors=0,c7_matched_count_mismatches=parity,c8_baseline_value_mismatches=value_parity,tests=json.loads((CACHE/'test-receipt.json').read_text(encoding='utf-8')) if (CACHE/'test-receipt.json').exists() else dict(status='PENDING',command='./experiments/.cache/venv/python.exe -m pytest experiments/tests')),provenance=prov)
    report['provenance']['analysis_source_sha256']={str(q.relative_to(ROOT)):digest(q) for q in (HERE/'dropoff_hubs.py',HERE/'dropoff_designs.py',HERE/'tests/test_dropoff.py')}
    save(HERE/'reports/c9-dropoff-designs.json',report);(HERE/'reports/c9-dropoff-designs.md').write_text(markdown(report),encoding='utf-8')
    print(json.dumps(report['verification'],ensure_ascii=False));print('report written')
if __name__=='__main__':main()
