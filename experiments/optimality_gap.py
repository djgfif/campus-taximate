"""C8: external exhaustive production feasibility + exact memoized set packing.

Run with the existing public experiment Python environment. No network or solver
packages are needed. All four policies use the exact same generated C7 requests.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parent))
import detailed as d
import run

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent
CACHE=HERE/'.cache'/'c8'
SCENARIOS=('base','n50_d15','participation150')
POLICIES=('PROD','ROUND-OPT','ROUND-OPT-SAVING','HINDSIGHT')


def solve_exact(groups, objective='lex', last=(), arrivals=None, second=0, order=None):
    """Unlimited exact subset DP. Scores are integer tuples, never big-M weights.

    Recurrence branches on matching the earliest remaining vertex in each of
    its feasible incident hyperedges, or leaving it unmatched. Every packing
    occurs in one branch. Memoization changes runtime, not feasible solutions.
    The final tie is production's lexicographically smallest sorted rider IDs.
    """
    started=time.perf_counter(); arrivals=arrivals or {}; last=set(last)
    people=set(i for g in groups for i in g['ids'])
    people=sorted(people,key=lambda i:(order.get(i,i),i) if order else i)
    index={v:i for i,v in enumerate(people)}
    incident=[[] for _ in people]; weighted=[]
    for g in groups:
        mask=sum(1<<index[i] for i in g['ids'])
        if objective=='lex':
            score=(sum(i in last for i in g['ids']),len(g['ids']),sum(max(0,second-arrivals.get(i,second)) for i in g['ids']),g['saved_meters'])
        elif objective=='saving': score=(g['expected_saving_krw'],)
        elif objective=='hindsight': score=(len(g['ids']),g['expected_saving_krw'])
        else: raise ValueError(objective)
        j=len(weighted);weighted.append((mask,score,g['key']))
        for i in g['ids']: incident[index[i]].append(j)
    zero=(0,)*(4 if objective=='lex' else 2 if objective=='hindsight' else 1)
    @lru_cache(None)
    def dp(mask):
        if not mask:return zero,(),()
        bit=mask&-mask; v=bit.bit_length()-1
        best=dp(mask^bit)
        for j in incident[v]:
            gm,score,key=weighted[j]
            if mask&gm!=gm:continue
            sub,ids,keys=dp(mask^gm)
            candidate=(tuple(a+b for a,b in zip(score,sub)),tuple(sorted(ids+tuple(groups[j]['ids']))),keys+(key,))
            if candidate[0]>best[0] or candidate[0]==best[0] and (candidate[1],candidate[2])<(best[1],best[2]):best=candidate
        return best
    score,ids,keys=dp((1<<len(people))-1)
    elapsed=time.perf_counter()-started
    info=dict(seconds=elapsed,states=dp.cache_info().currsize,groups=len(groups),riders=len(people),status='EXACT',score=score)
    dp.cache_clear()
    return list(keys),info


def read_export(path):
    days=[]; day=None
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        p=line.split('\t');kind=p[0]
        if kind=='D':day=dict(case_id=p[1],requests=int(p[2]),seed=int(p[3]),inputs={},groups={},rounds=[],prod=[])
        elif kind=='I':day['inputs'][int(p[1])]=dict(arrival=int(p[2]),expiry=int(p[3]),window_start=int(p[4]),window_end=int(p[5]))
        elif kind=='G':
            key=p[1];day['groups'][key]=dict(key=key,ids=tuple(map(int,key.split(','))),size=int(p[2]),saved_meters=int(p[3]),
                expected_saving_krw=int(p[4]),total_saving_krw=int(p[5]),ratio_saving_sum=float(p[6]),delay_sum=float(p[7]),
                expected_ratio_saving_sum=float(p[8]),expected_bill=int(p[9]),actual_bill=int(p[10]),actual_solo=int(p[11]))
        elif kind=='R':day['rounds'].append(dict(second=int(p[1]),last=tuple(map(int,p[2].split(','))) if p[2] else (),expired=tuple(map(int,p[3].split(','))) if p[3] else (),groups=p[4].split(';') if p[4] else []))
        elif kind=='H':day['hindsight']=p[1].split(';') if p[1] else []
        elif kind=='P':day['prod'].append((int(p[1]),p[2]))
        elif kind=='T':day['timing']=dict(enumeration_seconds=float(p[1])/1000,prod_seconds=float(p[2])/1000,enumerated=int(p[3]),production_verifier_passes=int(p[4]))
        elif kind=='END':days.append(day)
        else:raise ValueError('Unknown export record '+kind)
    return days


def measure(day, selected, policy):
    groups=[day['groups'][key] for _,key in selected];ids=[i for g in groups for i in g['ids']]
    if len(ids)!=len(set(ids)):raise AssertionError('rider conservation')
    if any(g['actual_solo']-g['actual_bill']!=g['total_saving_krw'] for g in groups):raise AssertionError('money conservation')
    matched=len(ids)
    return dict(case_id=day['case_id'],scenario=day['case_id'].rsplit('_day',1)[0],policy=policy,requests=day['requests'],
        matched_riders=matched,match_rate=matched/day['requests'],mean_saving=sum(g['ratio_saving_sum'] for g in groups)/matched if matched else None,
        total_saving_krw=sum(g['total_saving_krw'] for g in groups),expected_total_saving_krw=sum(g['expected_saving_krw'] for g in groups),
        expected_mean_saving=sum(g['expected_ratio_saving_sum'] for g in groups)/matched if matched else None,
        mean_delay=sum(g['delay_sum'] for g in groups)/matched if matched else None,
        group_size_counts={str(n):sum(g['size']==n for g in groups) for n in (2,3,4)},
        group_size_distribution={str(n):sum(g['size']==n for g in groups)/len(groups) if groups else 0. for n in (2,3,4)},
        conservation_errors=0,selected=selected)


def evaluate(day):
    result=[measure(day,day['prod'],'PROD')];times=[]
    arrivals={i:r['arrival'] for i,r in day['inputs'].items()}
    for policy,objective in (('ROUND-OPT','lex'),('ROUND-OPT-SAVING','saving')):
        finished=set();selected=[]
        for rnd in day['rounds']:
            groups=[day['groups'][key] for key in rnd['groups'] if not finished.intersection(day['groups'][key]['ids'])]
            keys,info=solve_exact(groups,objective,rnd['last'],arrivals,rnd['second'])
            times.append(dict(case_id=day['case_id'],policy=policy,second=rnd['second'],**info))
            selected.extend((rnd['second'],k) for k in keys)
            for k in keys:
                if k not in rnd['groups']:raise AssertionError('not production-verified')
                finished.update(day['groups'][k]['ids'])
                if k not in day['hindsight']:raise AssertionError('hindsight not a superset')
            finished.update(rnd['expired'])
        if finished!=set(day['inputs']):raise AssertionError('request accounting')
        result.append(measure(day,selected,policy))
    keys,info=solve_exact([day['groups'][k] for k in day['hindsight']],'hindsight',order={i:r['window_end'] for i,r in day['inputs'].items()})
    times.append(dict(case_id=day['case_id'],policy='HINDSIGHT',second=None,**info))
    result.append(measure(day,[(None,k) for k in keys],'HINDSIGHT'))
    if result[-1]['matched_riders']<max(r['matched_riders'] for r in result[:-1]):raise AssertionError('invalid upper bound')
    return result,times


def compile_export():
    # Recompile every production source from disk into this benchmark's cache.
    # Dependency hashes are verified against the existing C7 execution receipt.
    receipt=json.loads((HERE/'.cache'/'c7'/'execution.json').read_text(encoding='utf-8'))
    source=receipt['production_source'];deps=HERE/'.cache'/'deps'
    for name,digest in source['dependency_sha256'].items():
        if run.sha(deps/name)!=digest:raise ValueError('Dependency changed '+name)
    production=sorted((ROOT/'matching-engine'/'src'/'main'/'java').rglob('*.java'))+sorted((ROOT/'server'/'src'/'main'/'java').rglob('*.java'))
    for p in production:
        rel=p.relative_to(ROOT).as_posix()
        if run.sha(p)!=source['source_sha256'][rel]:raise ValueError('Production changed since C7 '+rel)
    java0=json.loads((HERE/'.cache'/'c7'/'java-command.json').read_text(encoding='utf-8'));java=Path(java0[0]);home=java.parent
    libraries=sorted(deps.glob('*.jar'));lombok=next(p for p in libraries if p.name.startswith('lombok-'))
    import os
    classpath=os.pathsep.join(map(str,libraries));classes=CACHE/'classes';classes.mkdir(exist_ok=True)
    adapters=sorted((HERE/'java').rglob('*.java'))
    args=['-source','21','-target','21','-encoding','UTF-8','-cp',classpath,'-processorpath',str(lombok),'-d',str(classes),*map(str,production+adapters)]
    argfile=CACHE/'build.args';argfile.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf-8')
    with (CACHE/'build.log').open('w',encoding='utf-8') as log:subprocess.run([str(home/'javac'),'@'+str(argfile)],cwd=ROOT,stdout=log,stderr=log,check=True)
    command=[str(java),'-Xmx1536m','-cp',str(classes)+os.pathsep+classpath,'com.campustaximate.server.service.OptimalityExport']
    provenance=dict(production_source_sha256={p.relative_to(ROOT).as_posix():run.sha(p) for p in production},
        adapter_source_sha256={p.relative_to(ROOT).as_posix():run.sha(p) for p in adapters},dependency_sha256=source['dependency_sha256'],jdk_major=21)
    return command,provenance


def prepare_cases(days=30):
    # Preserve the existing C7 request stream byte for byte for the three cohorts.
    original=HERE/'.cache'/'c7'/'cases.tsv'
    receipt=json.loads((HERE/'.cache'/'c7'/'execution.json').read_text(encoding='utf-8'))
    if run.sha(original)!=receipt['input_sha256'][str(original)]:raise ValueError('C7 request stream changed')
    text=original.read_text(encoding='utf-8');blocks=[]
    for chunk in text.split('END\n'):
        if not chunk.strip():continue
        header=chunk.splitlines()[0].split('\t');cid=header[2];scenario,day=cid.rsplit('_day',1)
        if scenario in SCENARIOS and int(day)<days:blocks.append(chunk+'END\n')
    if len(blocks)!=len(SCENARIOS)*days:raise ValueError('Missing C7 cases')
    target=CACHE/'cases.tsv';target.write_text(''.join(blocks),encoding='utf-8');return target


def timing_summary(times):
    values=[x['seconds'] for x in times]
    return dict(solves=len(times),seconds_total=sum(values),seconds_mean=statistics.mean(values),seconds_max=max(values),
        states_max=max(x['states'] for x in times),all_exact=all(x['status']=='EXACT' for x in times))


def create_report(days, daily, times, provenance):
    metrics=('match_rate','matched_riders','mean_saving','total_saving_krw','mean_delay','expected_total_saving_krw','expected_mean_saving')
    results={};gaps={}
    for scenario in SCENARIOS:
        results[scenario]={}
        for policy in POLICIES:
            rows=[r for r in daily if r['scenario']==scenario and r['policy']==policy]
            results[scenario][policy]={**{key:d.ci([r[key] for r in rows]) for key in metrics},
                'group_size_distribution':{str(n):d.ci([r['group_size_distribution'][str(n)] for r in rows]) for n in (2,3,4)},
                'group_size_counts':{str(n):d.ci([r['group_size_counts'][str(n)] for r in rows]) for n in (2,3,4)}}
        gaps[scenario]={}
        for label,a,b in [('algorithm_loss','PROD','ROUND-OPT'),('batching_online_loss','ROUND-OPT','HINDSIGHT')]:
            left=[r for r in daily if r['scenario']==scenario and r['policy']==a];right=[r for r in daily if r['scenario']==scenario and r['policy']==b]
            gaps[scenario][label]={m:d.ci([y[m]-x[m] for x,y in zip(left,right)]) for m in metrics}
    report=dict(status='COMPLETED',days_per_scenario=len(days)//3,results=results,gaps=gaps,
        calibration=json.loads((HERE/'reports'/'c8-calibration-selection.json').read_text(encoding='utf-8')),
        solver=dict(algorithm='unlimited exact memoized subset dynamic programming; branching in earliest-window-end order for hindsight',
            policies={p:timing_summary([t for t in times if t['policy']==p]) for p in POLICIES if p!='PROD'},
            production_seconds=sum(x['timing']['prod_seconds'] for x in days),enumeration_seconds=sum(x['timing']['enumeration_seconds'] for x in days)),
        verification=dict(production_verifier_passes=sum(x['timing']['production_verifier_passes'] for x in days),
            enumerated_groups_including_repeated_rounds=sum(x['timing']['enumerated'] for x in days),conservation_errors=0,
            hindsight_contains_all_selected_online_groups=True,all_solves_exact=True,lp_relaxations=0),
        provenance=provenance,
        interpretation=['Simulated public-data proxy demand, not observed students or real payments.',
            'PROD calls unchanged MatchingRound: greedy initialization plus DFS, bounded by wall time and 100000 nodes; candidate cap 12 and secondary checks per anchor 3.',
            'ROUND-OPT sums (last-chance riders, matched riders, waited seconds, rounded saved meters), then smallest sorted matched IDs, matching the production Packer objective. Full feasible group set, no search/candidate/verification budgets.',
            'Each rolling policy owns its waiting pool. Day-level algorithm gap includes subsequent queue feedback and can be negative; it is not a sum of same-state round regret.',
            'ROUND-OPT-SAVING maximizes expected quote saving KRW each round, then deterministic ID tie. It does not know future residuals.',
            'HINDSIGHT maximizes matched riders then expected quote saving over a full day, with overlapping departure windows. Reservation windows unchanged; instant window is earliest through latest production-eligible minute confirmation plus 10 min preparation/dispatch. Online round confirmation/early-confirm restrictions are relaxed. Every selected group passes the production verifier with a fixed feasible departure.',
            'HINDSIGHT is an exact upper bound on matched riders within this day/window/route model. Its saving and delay are feasible-solution metrics, not independent upper bounds on those metrics.',
            'Reported ratio mean saving and total KRW use the identical C7 seeded TIMS group residual and rider solo residual, with production ratio settlement and integer conservation. Expected saving metrics additionally show the optimization objective.',
            'Metrics and group-size shares are means of daily metrics; 95% CIs use existing 10000-replicate day-cluster bootstrap. Gaps use paired daily differences; units are fractions, multiply by 100 for percentage points.',
            'All requests share the original C7 male gender pool and hub; the benchmark enforces pool parity but does not infer population gender effects.'])
    return report


def markdown(report):
    def fmt(m,scale=1):return f"{m['mean']*scale:.2f} [{m['ci95_low']*scale:.2f}, {m['ci95_high']*scale:.2f}]"
    lines=['# C8 production optimality gap','','30 seeded C7 days per scenario. Values: daily mean [day-bootstrap 95% CI]. Saving is residual-adjusted ratio settlement. Money is KRW. Delay is minutes.','',
        '| Scenario | Policy | Match % | Matched/day | Mean saving % | Total saving/day KRW | Delay min |',
        '|---|---|---:|---:|---:|---:|---:|']
    for scenario,policies in report['results'].items():
        for policy,m in policies.items():lines.append('| '+' | '.join([scenario,policy,fmt(m['match_rate'],100),fmt(m['matched_riders']),fmt(m['mean_saving'],100),fmt(m['total_saving_krw']),fmt(m['mean_delay'])])+' |')
    lines+=['','## Paired gaps','','Positive means the second policy has the larger metric; online queue feedback can make algorithm gaps negative.','',
        '| Scenario | Gap | Match pp | Matched/day | Total saving KRW/day |','|---|---|---:|---:|---:|']
    for scenario,gg in report['gaps'].items():
        for label,m in gg.items():lines.append('| '+' | '.join([scenario,label,fmt(m['match_rate'],100),fmt(m['matched_riders']),fmt(m['total_saving_krw'])])+' |')
    lines+=['','## Group-size distribution','','Daily share of groups, with 95% CIs. Daily group counts also appear in JSON.','',
        '| Scenario | Policy | 2 riders % | 3 riders % | 4 riders % |','|---|---|---:|---:|---:|']
    for scenario,policies in report['results'].items():
        for policy,m in policies.items():lines.append('| '+' | '.join([scenario,policy,*[fmt(m['group_size_distribution'][str(n)],100) for n in (2,3,4)]])+' |')
    lines+=['','## Expected monetary objective','','These expected-quote savings are optimized before TIMS residual draws; the headline table reports residual-adjusted outcomes.','', '| Scenario | Policy | Expected saving/day KRW | Expected mean saving % |','|---|---|---:|---:|']
    for scenario,policies in report['results'].items():
        for policy,m in policies.items():lines.append('| '+' | '.join([scenario,policy,fmt(m['expected_total_saving_krw']),fmt(m['expected_mean_saving'],100)])+' |')
    lines+=['','## Exact solver time','','Includes all round calls, even empty feasible sets. Java enumeration/verification is reported separately.','',
        '| Policy | Solves | Total s | Mean ms | Max s | Max DP states |','|---|---:|---:|---:|---:|---:|']
    for policy,t in report['solver']['policies'].items():lines.append(f"| {policy} | {t['solves']} | {t['seconds_total']:.3f} | {t['seconds_mean']*1000:.3f} | {t['seconds_max']:.3f} | {t['states_max']} |")
    lines += ['',f"Enumeration + production verification: {report['solver']['enumeration_seconds']:.3f}s; production selection: {report['solver']['production_seconds']:.3f}s.",
        '', '## Calibration decision','', 'Kept the original global 1.567022x correction. Five-fold train CV chose distance bands (19.84% MAPE; global 25.15%, affine 20.66%). One held-out assessment improved overall MAPE 22.69% to 17.05% and >=30km 27.20% to 10.10%, but worsened 5-15km 19.20% to 23.63% (+4.43pp), exceeding the predeclared 2pp band regression tolerance. No fallback model was chosen using held-out data. Matrix unchanged; existing C7 results remain current. Full coefficients and band results: c8-calibration-selection.json.',
        '', '## Interpretation and boundaries','']
    lines += ['- '+x for x in report['interpretation']]
    lines += ['', '## Same production-state round regret','',json.dumps(report['same_state_regret'],ensure_ascii=False,indent=2),'', 'This compares exhaustive packing with PROD on PROD’s own waiting pool, before queue feedback. Objective suboptimality compares the first four production priorities; flattened-ID ties are excluded. Solve time is additional to the policy table.', '', '## Verification','',json.dumps(report['verification'],ensure_ascii=False,indent=2),
              '', 'Exact input/source/dependency hashes are in JSON. Daily policy metrics, chosen groups, all solve times and Java export remain in experiments/.cache/c8. No production files changed; no commit.']
    return '\n'.join(lines)+'\n'



def verify_selection(daily):
    """Re-enter the production verifier for every actually selected group."""
    selected=CACHE/'selected.tsv'
    with selected.open('w',encoding='utf-8') as f:
        for row in daily:
            for second,key in row['selected']:
                f.write(f"{row['case_id']}\t{row['policy']}\t{second if second is not None else -1}\t{key}\n")
    import os
    java0=json.loads((HERE/'.cache'/'c7'/'java-command.json').read_text(encoding='utf-8'))
    java=Path(java0[0]);deps=sorted((HERE/'.cache'/'deps').glob('*.jar'))
    classpath=os.pathsep.join(map(str,[CACHE/'classes',*deps]));source=HERE/'java'/'com'/'campustaximate'/'server'/'service'/'OptimalityParity.java'
    argfile=CACHE/'parity-build.args'
    args=['-proc:none','-source','21','-target','21','-encoding','UTF-8','-cp',classpath,'-d',str(CACHE/'classes'),str(source)]
    argfile.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf-8')
    with (CACHE/'parity.log').open('w',encoding='utf-8') as log:
        subprocess.run([str(java.parent/'javac'),'@'+str(argfile)],cwd=ROOT,stdout=log,stderr=log,check=True)
        subprocess.run([str(java),'-Xmx1024m','-cp',classpath,'com.campustaximate.server.service.OptimalityParity',str(CACHE/'cases.tsv'),str(HERE/'.cache'/'osrm'/'matrix.tsv'),str(selected),str(CACHE/'parity.json')],cwd=ROOT,stdout=log,stderr=log,check=True)
    receipt=json.loads((CACHE/'parity.json').read_text(encoding='utf-8'))
    receipt.update(selected_sha256=run.sha(selected),verifier_source_sha256=run.sha(source))
    run.json_write(CACHE/'parity.json',receipt)
    return receipt


def production_state_regret(days):
    rows=[]
    for day in days:
        finished=set();arrivals={i:r['arrival'] for i,r in day['inputs'].items()}
        selected=defaultdict(list)
        for second,key in day['prod']:selected[second].append(key)
        for rnd in day['rounds']:
            groups=[day['groups'][k] for k in rnd['groups'] if not finished.intersection(day['groups'][k]['ids'])]
            if groups:
                keys,info=solve_exact(groups,'lex',rnd['last'],arrivals,rnd['second'])
                def score(kk):
                    gg=[day['groups'][k] for k in kk];ids=[i for g in gg for i in g['ids']]
                    return (sum(i in rnd['last'] for i in ids),len(ids),sum(rnd['second']-arrivals[i] for i in ids),sum(g['saved_meters'] for g in gg))
                prod=selected[rnd['second']];ps=score(prod);opt=score(keys)
                if ps>opt:raise AssertionError('PROD exceeds exact score')
                rows.append(dict(case_id=day['case_id'],second=rnd['second'],production_score=ps,optimal_score=opt,
                    objective_suboptimal=ps<opt,matched_rider_delta=opt[1]-ps[1],seconds=info['seconds']))
            for key in selected[rnd['second']]:finished.update(day['groups'][key]['ids'])
            finished.update(rnd['expired'])
    run.json_write(CACHE/'same-state-regret.json',rows)
    return dict(nonempty_rounds=len(rows),objective_suboptimal_rounds=sum(r['objective_suboptimal'] for r in rows),
        solve_seconds=sum(r['seconds'] for r in rows),by_scenario={sc:dict(nonempty_rounds=sum(r['case_id'].startswith(sc+'_day') for r in rows),
        objective_suboptimal_rounds=sum(r['objective_suboptimal'] for r in rows if r['case_id'].startswith(sc+'_day'))) for sc in SCENARIOS})

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--reuse-export',action='store_true');parser.add_argument('--days',type=int,default=30);args=parser.parse_args()
    if args.days!=30:parser.error('Final C8 report requires exactly 30 C7 days per scenario')
    CACHE.mkdir(parents=True,exist_ok=True)
    c7receipt=json.loads((HERE/'.cache'/'c7'/'execution.json').read_text(encoding='utf-8'))
    for source in (HERE/'.cache'/'osrm'/'matrix.tsv',HERE/'.cache'/'osrm'/'solo.csv',HERE/'.cache'/'kr-public'/'tims-errors.csv'):
        if run.sha(source)!=c7receipt['input_sha256'][str(source)]:raise ValueError('Rerun C7 after changed input: '+str(source))
    export=CACHE/'feasible.tsv';receipt=CACHE/'export-receipt.json';matrix=HERE/'.cache'/'osrm'/'matrix.tsv';errors=HERE/'.cache'/'kr-public'/'tims-errors.csv'
    if args.reuse_export:
        provenance=json.loads(receipt.read_text(encoding='utf-8'))
        for path,digest in provenance['inputs'].items():
            if run.sha(path)!=digest:raise ValueError('Changed input '+path)
        for path,digest in provenance['production_source_sha256'].items():
            if run.sha(ROOT/path)!=digest:raise ValueError('Production source changed '+path)
        for path,digest in provenance['adapter_source_sha256'].items():
            if run.sha(ROOT/path)!=digest:raise ValueError('Adapter source changed '+path)
        if run.sha(export)!=provenance['export_sha256']:raise ValueError('Export changed')
    else:
        cases=prepare_cases(args.days);command,provenance=compile_export()
        with (CACHE/'export.log').open('w',encoding='utf-8') as log:subprocess.run(command+list(map(str,(cases,matrix,errors,export))),cwd=ROOT,stdout=log,stderr=log,check=True)
        provenance['inputs']={str(p):run.sha(p) for p in (cases,matrix,errors,HERE/'.cache'/'osrm'/'solo.csv',HERE/'.cache'/'c7'/'cases.tsv',HERE/'.cache'/'c7'/'execution.json')}
        provenance['export_sha256']=run.sha(export);run.json_write(receipt,provenance)
    days=read_export(export);daily=[];times=[]
    for day in days:
        rr,tt=evaluate(day);daily.extend(rr);times.extend(tt)
        print(day['case_id'],[(r['policy'],r['matched_riders']) for r in rr],flush=True)
        run.json_write(CACHE/'progress.json',dict(completed_days=len(daily)//4,latest=day['case_id']))
    run.json_write(CACHE/'daily.json',daily);run.json_write(CACHE/'solve-times.json',times)
    report=create_report(days,daily,times,provenance)
    report['provenance']['analysis_source_sha256']={p.relative_to(ROOT).as_posix():run.sha(p) for p in (Path(__file__),HERE/'detailed.py',HERE/'calibration_refinement.py',HERE/'tests'/'test_optimality.py')}
    c7days={row['case_id']:row for row in d.read_csv(HERE/'.cache'/'c7'/'days.csv')}
    for row in daily:
        if row['policy']=='PROD' and row['matched_riders']!=int(c7days[row['case_id']]['matched']):raise AssertionError('C7 PROD reproduction')
    report['verification']['c7_prod_daily_matched_counts_identical']=True
    report['verification']['selected_group_replay']=verify_selection(daily)
    report['same_state_regret']=production_state_regret(days)
    run.json_write(HERE/'reports'/'c8-optimality-gap.json',report)
    (HERE/'reports'/'c8-optimality-gap.md').write_text(markdown(report),encoding='utf-8')
    print(json.dumps(report['solver'],ensure_ascii=False))

if __name__=='__main__':main()
