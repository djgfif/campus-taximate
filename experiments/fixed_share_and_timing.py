"""C11: fixed prices/operator reserve and paired online confirmation policies."""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
from dataclasses import replace
from datetime import datetime,timezone
from functools import lru_cache
import csv,json,math,random,statistics,subprocess,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
import detailed as c7
import scale_and_prepay as c10
import optimality_gap as c8
import run
HERE=Path(__file__).resolve().parent
ROOT=HERE.parent
CACHE=HERE/'.cache/c11'
REPORTS=HERE/'reports'
LEVELS=(50,100,300)
MARGINS=(0.,.005,.01,.02,.03,.05)
SPREADS=(.5,1.,1.5)
POLICIES=('PROD','WAIT-TO-DEADLINE','EARLY0','EARLY5','EARLY10','EARLY15','EARLY20','FORECAST-AWARE')
SEED=20261119
TRAIN_SEED=SEED+100000000
DAYS=30
SEMESTER=80
PATHS=2000

def read_tsv(path):
    with Path(path).open(encoding='utf-8',newline='') as f:return list(csv.DictReader(f,delimiter='\t'))
def split_numbers(s,kind=float):return [kind(x) for x in s.split(';')]
def scenario(n):return 'base' if n==100 else f'n{n}'
def n_case(cid):return 100 if cid.startswith('base_') else int(cid.split('_')[0].replace('train','').removeprefix('n'))
def key(source,n,margin,spread):return f'{source}:{n}:{margin:.3f}:{spread:.1f}'

def compile_java():
    CACHE.mkdir(parents=True,exist_ok=True)
    c8.CACHE=CACHE
    java,provenance=c8.compile_export()
    java[-1]='com.campustaximate.server.service.C11Simulation'
    run.json_write(CACHE/'java-command.json',java);run.json_write(CACHE/'production-source.json',provenance)
    for cls in ('C11Simulation','C11Finance'):
        subprocess.run(java[:-1]+['com.campustaximate.server.service.'+cls,'--self-test'],cwd=ROOT,check=True)
    return java

def inputs():
    c10.verify_simulation()
    c10report=json.loads((REPORTS/'c10-scale-and-prepay.json').read_text(encoding='utf-8'))
    split=HERE/'.cache/c10/bias-split.json'
    if run.sha(split)!={k.replace('\\','/'):v for k,v in c10report['artifacts'].items()}[split.relative_to(ROOT).as_posix()]:raise ValueError('C10 bias split changed')
    b=json.loads(split.read_text(encoding='utf-8'))['mean']
    data=json.loads((HERE/'.cache/osrm/destinations.json').read_text(encoding='utf-8'))
    solos={int(r['point']):r for r in c7.read_csv(HERE/'.cache/osrm/solo.csv')}
    for p in data['points']:
        r=solos[p['id']];p.update(km=float(r['km']),seconds=int(r['seconds']),fare=int(r['fare']))
    demand=json.loads((HERE/'.cache/c10/demand.json').read_text(encoding='utf-8'))
    errors=[float(x) for x in (HERE/'.cache/kr-public/tims-errors.csv').read_text().splitlines() if x.strip()]
    text=(HERE/'.cache/c10/cases.tsv').read_text(encoding='utf-8');blocks=[]
    for block in text.split('END\n'):
        if not block.strip():continue
        cid=block.splitlines()[0].split('\t')[2]
        if cid.rsplit('_day',1)[0] in ('base','n50','n300'):blocks.append(block+'END\n')
    if len(blocks)!=90:raise ValueError('Missing paired C10 cases')
    (CACHE/'eval-cases.tsv').write_text(''.join(blocks),encoding='utf-8')
    header=blocks[0].splitlines()[0].split('\t')[:18]
    ss=[replace(c7.Scenario(),name=f'train{n}',requests=n) for n in LEVELS]
    meta=c7.write_cases(CACHE/'train-cases.tsv',ss,DAYS,TRAIN_SEED,data['points'],demand['hourly_distribution'],header,errors)
    eval_seeds={SEED+d*104729 for d in range(DAYS)}
    if {m['seed'] for m in meta.values()}&eval_seeds:raise ValueError('Forecast train/test seed overlap')
    run.json_write(CACHE/'train-meta.json',meta)
    return b,data,demand

def simulate(java,cases,prefix,bias,forecast='-'):
    with (CACHE/(prefix+'.log')).open('w',encoding='utf-8') as log:
        subprocess.run(java+[str(cases),str(HERE/'.cache/osrm/matrix.tsv'),str(HERE/'.cache/kr-public/tims-errors.csv'),
          str(CACHE/(prefix+'-groups.tsv')),str(CACHE/(prefix+'-days.csv')),str(forecast),str(bias)],cwd=ROOT,check=True,stdout=log,stderr=log)

def bearing(hub,p):
    lat1,lat2=map(math.radians,(hub['lat'],p['lat']));lon=math.radians(p['lon']-hub['lon'])
    return math.atan2(math.sin(lon)*math.cos(lat2),math.cos(lat1)*math.sin(lat2)-math.sin(lat1)*math.cos(lat2)*math.cos(lon))
def feature(hub,p):return math.floor(bearing(hub,p)/(math.pi/6))%12,0 if p['solo_km']<10 else 1 if p['solo_km']<25 else 2

def fit_forecast(data,demand,train_groups,train_meta,eval_seeds):
    if any(not r['case_id'].startswith('train') for r in train_groups):raise ValueError('Only simulated training days may fit forecast')
    train_seeds={m['seed'] for m in train_meta.values()}
    if train_seeds&set(eval_seeds):raise ValueError('Forecast day split overlap')
    counts=Counter();sizes=defaultdict(list)
    for n in LEVELS:
        for day in range(DAYS):
            for r in c7.generate_day(data['points'],demand['hourly_distribution'],TRAIN_SEED+day*104729,n):
                sector,band=feature(data['hub'],r['point']);counts[(n,12+r['arrival']//3600,sector,band)]+=1
    for r in train_groups:
        units=split_numbers(r['units']);expected=split_numbers(r['expected'])
        sizes[(n_case(r['case_id']),len(units))].extend(1-a/u for a,u in zip(expected,units))
    gains={}
    for n in LEVELS:
        for size in (2,3,4):
            gains[(n,size)]=max(0.,statistics.mean(sizes[(n,size+1)])-statistics.mean(sizes[(n,size)])) if size<4 and sizes[(n,size)] and sizes[(n,size+1)] else 0.
    model=dict(train_seeds=sorted(train_seeds),eval_seeds=sorted(set(eval_seeds)),training_days_per_load=DAYS,
      rates={':'.join(map(str,k)):v/(DAYS*60) for k,v in sorted(counts.items())},
      gains={':'.join(map(str,k)):v for k,v in gains.items()},threshold_saving_fraction=.01,
      compatibility='Same 30-degree bearing sector and solo distance band (<10,10-25,25-60km) as farthest rider; geometric proxy, not future feasibility knowledge.',
      method='Poisson P(at least one)=1-exp(-integrated historical bucket arrivals); gain=max(0,train expected ratio saving at size n+1 minus n); confirm if product <=1pp or last chance. Train is independent simulated history, not observed app use.')
    return model

def write_forecast(model):
    run.json_write(CACHE/'forecast.json',model)
    (CACHE/'forecast.tsv').write_text('\n'.join(['R\t'+k+'\t'+str(v) for k,v in model['rates'].items()]+['G\t'+k+'\t'+str(v) for k,v in model['gains'].items()]),encoding='utf-8')

def prepare_finance(groups):
    old=read_tsv(HERE/'.cache/c10/ledger.tsv')
    selected=[r for r in old if r['case_id'].startswith(('base_day','n50_day','n300_day'))]
    prod={(r['case_id'],r['group_id']):r for r in groups if r['policy']=='PROD'}
    lines=['source\tn\tday\tgroup_id\tconfirm\tfinish\traw_quote\terror_index\tunits\tsolo_errors\tmargin']
    for r in selected:
        g=prod[(r['case_id'],r['group_id'])]
        if int(r['expected_bill'])!=int(g['raw_quote']) or r['units']!=g['units'] or r['error_index']!=g['error_index']:
            raise ValueError('C10 matched group/bill draw parity failed')
        if not np.allclose(split_numbers(r['solo_errors']),split_numbers(g['solo_errors']),rtol=0,atol=1e-12):raise ValueError('C10 solo draw parity failed')
        lines.append('\t'.join(map(str,['C10',n_case(g['case_id']),int(g['case_id'].split('_day')[1]),g['group_id'],g['confirm'],g['finish'],r['expected_bill'],r['error_index'],r['units'],r['solo_errors'],'all'])))
    if len(selected)!=len(prod):raise ValueError('C10 group count parity failed')
    for g in groups:
        if g['policy'].startswith('MARKUP'):
            lines.append('\t'.join(map(str,['PRECHECK',n_case(g['case_id']),int(g['case_id'].split('_day')[1]),g['group_id'],g['confirm'],g['finish'],g['raw_quote'],g['error_index'],g['units'],g['solo_errors'],g['policy'][6:]])))
    (CACHE/'finance-input.tsv').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return len(selected)

# Remaining aggregation functions follow below.

def exact_cardinality(day):
    """C8 hindsight primary objective only, exact DP with a cardinality upper bound.

    Try matching earliest window-end first; stop a state as soon as every
    remaining vertex is covered. No time/node limit, no objective relaxation.
    """
    import time
    started=time.perf_counter()
    groups=[day['groups'][k] for k in day['hindsight']]
    people=sorted({i for g in groups for i in g['ids']},key=lambda i:(day['inputs'][i]['window_end'],i))
    index={i:j for j,i in enumerate(people)};incident=[[] for _ in people]
    for g in groups:
        mask=sum(1<<index[i] for i in g['ids'])
        for i in g['ids']:incident[index[i]].append((mask,len(g['ids'])))
    for row in incident:row.sort(key=lambda t:-t[1])
    @lru_cache(None)
    def dp(mask):
        if not mask:return 0
        bit=mask&-mask;v=bit.bit_length()-1;upper=mask.bit_count();best=0
        for gm,size in incident[v]:
            if mask&gm==gm:
                best=max(best,size+dp(mask^gm))
                if best==upper:return best
        return max(best,dp(mask^bit))
    score=dp((1<<len(people))-1)
    info=dict(case_id=day['case_id'],matched=score,match_rate=score/day['requests'],status='EXACT',states=dp.cache_info().currsize,
      seconds=time.perf_counter()-started,groups=len(groups),objective='Maximum matched riders; C8 primary hindsight objective, saving tie omitted')
    dp.cache_clear();return info

def hindsight(java):
    source=HERE/'java/com/campustaximate/server/service/C11Hindsight.java'
    subprocess.run([str(Path(java[0]).with_name('javac')),'-proc:none','-encoding','UTF-8','-cp',java[3],'-d',str(CACHE/'classes'),str(source)],cwd=ROOT,check=True)
    with (CACHE/'hindsight-export.log').open('w',encoding='utf-8') as log:
        subprocess.run(java[:-1]+['com.campustaximate.server.service.C11Hindsight',str(CACHE/'eval-cases.tsv'),str(HERE/'.cache/osrm/matrix.tsv'),
          str(HERE/'.cache/kr-public/tims-errors.csv'),str(CACHE/'hindsight-300.tsv')],cwd=ROOT,check=True,stdout=log,stderr=log)
    old=json.loads((HERE/'.cache/c8/daily.json').read_text(encoding='utf-8'))
    result=[dict(case_id=r['case_id'].replace('n50_d15','n50'),matched=r['matched_riders'],match_rate=r['match_rate'],status='EXACT',source='C8 fixed cohort') for r in old if r['policy']=='HINDSIGHT' and r['scenario'] in ('base','n50_d15')]
    for day in c8.read_export(CACHE/'hindsight-300.tsv'):
        info=exact_cardinality_mip(day);result.append(info);run.json_write(CACHE/'hindsight.json',result)
        print('HINDSIGHT',info,flush=True)
    return result

def seed_fund(required,probability):
    """Smallest empirical integer seed reaching the specified path coverage."""
    a=np.maximum(0,np.asarray(required,dtype=np.int64))
    return int(np.quantile(a,probability,method='inverted_cdf'))

def reserve_recursion(increments,initial=0):
    a=np.asarray(increments,dtype=np.int64);return np.concatenate(([initial],initial+np.cumsum(a)))

def path_statistics(day_totals,day_minima,paths):
    totals=np.asarray(day_totals,dtype=np.int64)[paths]
    ending=np.cumsum(totals,axis=1)
    previous=np.concatenate((np.zeros((len(paths),1),dtype=np.int64),ending[:,:-1]),axis=1)
    minimum=np.minimum(0,np.min(previous+np.asarray(day_minima,dtype=np.int64)[paths],axis=1))
    required=-minimum;s95=seed_fund(required,.95);s99=seed_fund(required,.99)
    return dict(end_mean=float(np.mean(ending[:,-1])),end_p5=float(np.quantile(ending[:,-1],.05)),end_p1=float(np.quantile(ending[:,-1],.01)),
      probability_ever_negative=float(np.mean(minimum<0)),seed95=s95,seed99=s99,
      probability_never_negative_seed95=float(np.mean(minimum+s95>=0)),probability_never_negative_seed99=float(np.mean(minimum+s99>=0)),
      trajectory=[dict(day=i+1,mean=float(np.mean(ending[:,i])),p5=float(np.quantile(ending[:,i],.05)),p1=float(np.quantile(ending[:,i],.01))) for i in range(paths.shape[1])]),required

def finance(java,bias,groups):
    parity=prepare_finance(groups)
    command=java[:-1]+['com.campustaximate.server.service.C11Finance']
    subprocess.run(command+['--fixed',str(CACHE/'finance-input.tsv'),str(HERE/'.cache/kr-public/tims-errors.csv'),str(bias),str(CACHE/'fixed.csv')],cwd=ROOT,check=True)
    rows=c7.read_csv(CACHE/'fixed.csv');cohorts=defaultdict(list)
    for r in rows:cohorts[key(r['source'],int(r['n']),float(r['margin']),float(r['spread']))].append(r)
    paths=np.random.default_rng(SEED+11).integers(0,DAYS,size=(PATHS,SEMESTER))
    (CACHE/'semester-paths.tsv').write_text('\n'.join('\t'.join(map(str,path)) for path in paths),encoding='utf-8')
    summaries=[];surplus_input=['key\tday\tconfirm\tfinish\tnormal\tzero\tmeter\texceptional\tsize'];seedlines=[]
    daily_all=[]
    for k,cohort in sorted(cohorts.items()):
        source,n,m,spread=k.split(':');n=int(n);m=float(m);spread=float(spread)
        daily=[];totals=[];minima=[];ob_totals=[];ob_minima=[];all_totals=[];all_minima=[]
        for day in range(DAYS):
            rr=sorted([r for r in cohort if int(r['day'])==day],key=lambda r:(int(r['finish']),int(r['group_id'])))
            people=sum(int(r['size']) for r in rr)
            if not people:raise ValueError('No matched riders in reserve day')
            ds=dict(day=day,matched=people,groups=len(rr),fixed_saving_actual=sum(float(r['fixed_saving_actual_sum']) for r in rr)/people,
              fixed_saving_quote=sum(float(r['fixed_saving_quote_sum']) for r in rr)/people,
              current_saving_actual=sum(float(r['current_saving_actual_sum']) for r in rr)/people,
              fixed_saving_krw=sum(int(r['fixed_saving_krw']) for r in rr)/people,
              current_saving_krw=sum(int(r['current_saving_krw']) for r in rr)/people,
              harmed_actual=sum(int(r['fixed_harmed_actual']) for r in rr)/people,
              harmed_quote=sum(int(r['fixed_harmed_quote']) for r in rr)/people,
              min_saving_violation_rate=sum(int(r['min_saving_violations']) for r in rr)/people,
              violating_groups=sum(int(r['min_saving_violations'])>0 for r in rr),
              exception_rate=sum(int(r['exceptional']) for r in rr)/len(rr),exception_count=sum(int(r['exceptional']) for r in rr),
              exception_delta=sum(int(r['objection_delta']) for r in rr),
              conservation_errors=sum(int(r['conservation_errors']) for r in rr),extra=sum(int(r['extra']) for r in rr),refund=sum(int(r['refund']) for r in rr))
            daily.append(ds);daily_all.append(dict(key=k,**ds))
            trajectory=reserve_recursion([int(r['reserve_delta']) for r in rr]);totals.append(trajectory[-1]);minima.append(trajectory.min())
            objection=reserve_recursion([int(r['objection_delta']) for r in rr]);ob_totals.append(objection[-1]);ob_minima.append(objection.min())
            combined=reserve_recursion([int(r['fixed_quote'])-int(r['meter']) for r in rr]);all_totals.append(combined[-1]);all_minima.append(combined.min())
            for r in rr:surplus_input.append('\t'.join(map(str,[k,day,r['confirm'],r['finish'],r['fixed_quote'],r['zero_quote'],r['meter'],r['exceptional'],r['size']])))
        metrics={metric:c10.ci([d[metric] for d in daily]) for metric in daily[0] if metric!='day'}
        stats,required=path_statistics(totals,minima,paths);obstats,_=path_statistics(ob_totals,ob_minima,paths)
        allstats,_=path_statistics(all_totals,all_minima,paths)
        active=statistics.mean(d['matched'] for d in daily);boardings=float(np.mean(np.asarray([d['matched'] for d in daily])[paths].sum(axis=1)))
        stats.update(seed95_per_daily_active_rider=stats['seed95']/active,seed99_per_daily_active_rider=stats['seed99']/active,
          end_reserve_per_daily_active_rider=stats['end_mean']/active,seed99_per_boarding=stats['seed99']/boardings,
          daily_active_riders=active,semester_boardings_mean=boardings)
        summaries.append(dict(key=k,source=source,requests=n,margin=m,spread=spread,metrics=metrics,reserve=stats,objection_bridge=obstats,combined_operator=allstats,
          observed_violating_groups=sum(d['violating_groups'] for d in daily),observed_groups=sum(d['groups'] for d in daily)))
        seedlines.append(k+'\t'+str(stats['seed99']))
    (CACHE/'surplus-input.tsv').write_text('\n'.join(surplus_input)+'\n',encoding='utf-8')
    (CACHE/'seeds.tsv').write_text('\n'.join(seedlines)+'\n',encoding='utf-8')
    with (CACHE/'surplus.log').open('w',encoding='utf-8') as log:
        subprocess.run(command+['--surplus',str(CACHE/'surplus-input.tsv'),str(CACHE/'seeds.tsv'),str(CACHE/'semester-paths.tsv'),str(CACHE/'surplus.csv')],cwd=ROOT,check=True,stdout=log,stderr=log)
    sr=defaultdict(list)
    for r in c7.read_csv(CACHE/'surplus.csv'):sr[r['key']].append(r)
    for s in summaries:
        rr=sr[s['key']];stats=s['reserve'];end=np.asarray([int(r['ending']) for r in rr]);minimum=np.asarray([int(r['min_balance']) for r in rr])
        reduction=np.asarray([int(r['price_reduction']) for r in rr]);boardings=np.asarray([int(r['active_boardings']) for r in rr])
        s['surplus_policy']=dict(initial_seed=stats['seed99'],cap=2*stats['seed99'],end_mean=float(end.mean()),end_p5=float(np.quantile(end,.05)),end_p1=float(np.quantile(end,.01)),
          probability_ever_negative=float(np.mean(minimum<0)),zero_margin_ride_share=sum(int(r['zero_margin_rides']) for r in rr)/sum(int(r['total_rides']) for r in rr),
          mean_rider_price_reduction_krw=float(np.mean(reduction/boardings)),
          mean_end_change_vs_constant_margin=float(end.mean()-stats['seed99']-stats['end_mean']),conservation_errors=sum(int(r['conservation_errors']) for r in rr),
          pricing='At confirmation use m=0 if settled reserve > 2*seed99, otherwise selected m; freeze price until meter settlement. Meter events in chronological order, settlements before confirmations on ties; no re-matching after lowering margin.')
    run.json_write(CACHE/'finance-daily.json',daily_all);run.json_write(CACHE/'reserve-results.json',summaries)
    return summaries,parity

def timing(groups,days,hindsight_rows):
    gg=defaultdict(list)
    for g in groups:gg[(g['case_id'],g['policy'])].append(g)
    daily=[]
    hindsight_by={r['case_id']:r for r in hindsight_rows}
    for d in days:
        policy=d['policy'];cid=d['case_id'];rr=gg[(cid,policy)];matched=int(d['matched']);n=int(d['requests']);count=len(rr)
        if not matched:raise ValueError('Undefined conditional timing outcome')
        sizes=[len(split_numbers(g['units'])) for g in rr]
        savings=[1-a/u for g in rr for a,u in zip(split_numbers(g['actual']),split_numbers(g['solos']))]
        waits=[x for g in rr for x in split_numbers(g['waits'])];delays=[x for g in rr for x in split_numbers(g['delays'])]
        row=dict(case_id=cid,day=int(cid.split('_day')[1]),requests=n,policy=policy,match_rate=matched/n,mean_saving=statistics.mean(savings),
          mean_wait=statistics.mean(waits),anxiety_rate=sum(size for g,size in zip(rr,sizes) if int(g['departure'])-int(g['confirm'])<600)/matched,
          mean_delay=statistics.mean(delays),groups_2_rate=sizes.count(2)/count,groups_3_rate=sizes.count(3)/count,groups_4_rate=sizes.count(4)/count,
          budget_warnings=int(d['budget_warnings']),parity_groups=int(d['production_parity_groups']))
        daily.append(row)
    output=[]
    keys=('match_rate','mean_saving','mean_wait','anxiety_rate','mean_delay','groups_2_rate','groups_3_rate','groups_4_rate')
    for n in LEVELS:
        prod={r['day']:r for r in daily if r['requests']==n and r['policy']=='PROD'}
        h=np.asarray([hindsight_by[f'{scenario(n)}_day{d:02}']['match_rate'] for d in range(DAYS)])
        base=np.asarray([prod[d]['match_rate'] for d in range(DAYS)])
        for policy in POLICIES+tuple('MARKUP'+str(m) for m in MARGINS):
            rr=sorted([r for r in daily if (r['requests'],r['policy'])==(n,policy)],key=lambda r:r['day'])
            if len(rr)!=DAYS:raise ValueError('Missing timing policy days')
            differences={k:c10.ci([r[k]-prod[r['day']][k] for r in rr]) for k in keys}
            rate=np.asarray([r['match_rate'] for r in rr]);den=np.mean(h-base)
            draws=c10.weights(DAYS);bootstrap_den=draws@(h-base);bootstrap_num=draws@(rate-base)
            recovery=bootstrap_num[bootstrap_den>0]/bootstrap_den[bootstrap_den>0]
            output.append(dict(requests=n,policy=policy,metrics={k:c10.ci([r[k] for r in rr]) for k in keys},paired_difference_vs_prod=differences,
              hindsight=dict(match_rate=c10.ci(h),gap_vs_prod=c10.ci(h-base),recovery_fraction=float(np.mean(rate-base)/den) if den>0 else None,
                recovery_ci95=[float(x) for x in np.quantile(recovery,[.025,.975])] if len(recovery) else None),
              production_feasibility_parity_groups=sum(r['parity_groups'] for r in rr),budget_warnings=sum(r['budget_warnings'] for r in rr)))
    run.json_write(CACHE/'timing-daily.json',daily)
    return output

def ensure_highs():
    """Install only the pinned, SHA-verified wheel inside this experiment cache."""
    import hashlib,urllib.request,zipfile
    target=CACHE/'python-deps'
    sys.path.insert(0,str(target))
    try:
        import highspy
        return highspy
    except ImportError:pass
    if sys.version_info[:2]!=(3,12) or sys.platform!='win32':raise RuntimeError('Install highspy==1.16.0 in the experiment environment')
    metadata=json.load(urllib.request.urlopen('https://pypi.org/pypi/highspy/1.16.0/json',timeout=30))
    wheel=next(f for f in metadata['urls'] if 'cp312-cp312-win_amd64' in f['filename'])
    content=urllib.request.urlopen(wheel['url'],timeout=30).read()
    if hashlib.sha256(content).hexdigest()!=wheel['digests']['sha256']:raise ValueError('HiGHS wheel checksum')
    dest=CACHE/'wheels'/wheel['filename'];dest.parent.mkdir(exist_ok=True);dest.write_bytes(content)
    target.mkdir(exist_ok=True);zipfile.ZipFile(dest).extractall(target)
    run.json_write(CACHE/'highspy-download.json',wheel)
    import highspy
    return highspy

def exact_cardinality_mip(day):
    """Binary set packing; accept only optimal status, zero gap, and validated primal."""
    import time
    highspy=ensure_highs();started=time.perf_counter()
    groups=[day['groups'][k] for k in day['hindsight']]
    incident=defaultdict(list)
    for j,g in enumerate(groups):
        for i in g['ids']:incident[i].append(j)
    lp=['Maximize',' obj: '+' + '.join(f"{len(g['ids'])} x{j}" for j,g in enumerate(groups)),'Subject To']
    lp += [f" r{i}: "+' + '.join(f'x{j}' for j in js)+' <= 1' for i,js in incident.items()]
    lp+=['Binary',' '.join(f'x{j}' for j in range(len(groups))),'End']
    model_path=CACHE/(day['case_id']+'-hindsight.lp');model_path.write_text('\n'.join(lp)+'\n',encoding='utf-8')
    h=highspy.Highs();h.setOptionValue('output_flag',False);h.setOptionValue('threads',1);h.setOptionValue('random_seed',SEED)
    h.setOptionValue('mip_rel_gap',0.);h.setOptionValue('mip_abs_gap',0.)
    if h.readModel(str(model_path))!=highspy.HighsStatus.kOk:raise ValueError('HiGHS model read')
    if h.run()!=highspy.HighsStatus.kOk or h.getModelStatus()!=highspy.HighsModelStatus.kOptimal:raise ValueError('Hindsight optimum not proved')
    info=h.getInfo();solution=h.getSolution().col_value
    if info.mip_gap>1e-9:raise ValueError('Nonzero integer MIP gap')
    if any(min(abs(v),abs(v-1))>1e-6 for v in solution):raise ValueError('Nonintegral packing')
    selected=[g for v,g in zip(solution,groups) if v>.5];riders=[i for g in selected for i in g['ids']]
    if len(riders)!=len(set(riders)) or abs(len(riders)-h.getObjectiveValue())>1e-6:raise ValueError('Invalid hindsight primal')
    if math.floor(info.mip_dual_bound+1e-6)!=len(riders):raise ValueError('Integer dual bound differs from primal')
    return dict(case_id=day['case_id'],matched=len(riders),match_rate=len(riders)/day['requests'],status='EXACT',
      seconds=time.perf_counter()-started,groups=len(groups),solver='HiGHS '+h.version(),mip_gap=info.mip_gap,
      dual_bound=info.mip_dual_bound,selected=[g['key'] for g in selected],model_sha256=run.sha(model_path),
      objective='Maximum matched riders, same C8 primary objective; savings tie omitted')

def solve_hindsight_cached():
    old=json.loads((HERE/'.cache/c8/daily.json').read_text(encoding='utf-8'))
    result=[dict(case_id=r['case_id'].replace('n50_d15','n50'),matched=r['matched_riders'],match_rate=r['match_rate'],status='EXACT',source='C8 fixed cohort') for r in old if r['policy']=='HINDSIGHT' and r['scenario'] in ('base','n50_d15')]
    for day in c8.read_export(CACHE/'hindsight-300.tsv'):
        r=exact_cardinality_mip(day);result.append(r);run.json_write(CACHE/'hindsight.json',result);print('HINDSIGHT MIP',r['case_id'],r['matched'],r['seconds'],flush=True)
    return result


ATTRIBUTION='출처: 서울특별시·KT, 서울시 통신데이터(생활이동), 한국교통안전공단 택시운행정보(TIMS) 2025, OpenStreetMap 기여자'
LIMITS=['요금 오차는 TIMS 격자·월 평균 잔차를 견적-미터 오차의 대리값으로 쓴다. 개별 운행 오차와 다를 수 있어 0.5·1.5배 민감도를 함께 둔다.',
  '견적 보정 b는 C10의 평가와 겹치지 않는 잔차 분할에서 구한 평균이다. 혼자 탈 때의 기대 요금에도 같은 b를 적용해 절약 기준을 비교한다(여유분 m만 기준을 바꾼다).',
  '적립금은 서비스 운영 주체가 관리한다고 가정한다. 실제 송금·보관 절차와 법적 요건은 다루지 않는다.',
  '이의제기(미터 > 견적×1.5)는 적립금이 아닌 별도 절차로 분리해 건수와 금액만 센다.',
  '예측 기반 정책은 평가일과 겹치지 않는 모의 이력 30일로 학습한다. 실제 앱 이용 이력이 아니다.',
  '하루 상한(HINDSIGHT)은 그날 요청을 모두 미리 안다고 가정한 정확해로, 어떤 실시간 정책도 도달할 수 없는 기준이다.']


def pct(x):
    return f"{100*x:.2f}%"


def cell(d):
    if d.get('mean') is None:
        return '-'
    return f"{100*d['mean']:.2f} [{100*d['ci95_low']:.2f}, {100*d['ci95_high']:.2f}]"


def write_report(b, summaries, timing_out, parity, model):
    def get(src, n, m, sp):
        return next(s for s in summaries if (s['source'], s['requests'], s['margin'], s['spread']) == (src, n, m, sp))
    tm = {(t['requests'], t['policy']): t for t in timing_out}
    L = ['# C11 확정 분담금·정산 적립금과 확정 시점 정책', '', ATTRIBUTION, '',
         f'견적 보정 b = {pct(b)} (C10 분할 평균). 한 학기 = {SEMESTER}일, 학기 경로 {PATHS:,}개(평가 30일 재표본), 일자 부트스트랩 95% CI.', '',
         '## 1. 확정 분담금 + 적립금', '',
         '규칙: 확정 때 각자 몫 = 견적×(1+b+m)의 비율 정산 몫. 이것이 선결제이자 최종 금액이다. 결제자는 미터 요금을 내고 앱이 (미터 − 결제자 몫)을 돌려준다. 차액(몫 합계 − 미터)은 적립금에 쌓이거나 빠진다.', '',
         'PRECHECK = 보정 가격으로 매칭 단계 절약 검사를 통과한 일행(권장). 혼자 탈 때 기대 요금도 견적×(1+b)로 비교한다.', '',
         '| 요청/일 | m | 매칭률 | 1인당 절약(실제 단독 대비) | 추가 결제 | 환불 | 학기 말 적립금 평균 | P(음수 경험, 기금 0) | 기금(99%) | 기금(95%) |',
         '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for n in LEVELS:
        for m in MARGINS:
            s = get('PRECHECK', n, m, 1.0); r = s['reserve']; mt = s['metrics']
            match = tm[(n, 'MARKUP' + str(m))]['metrics']['match_rate']['mean']
            L.append(f"| {n} | {pct(m)} | {pct(match)} | {pct(mt['fixed_saving_actual']['mean'])} | 0 | 0 | {r['end_mean']:,.0f}원 | {pct(r['probability_ever_negative'])} | {r['seed99']:,}원 | {r['seed95']:,}원 |")
    L += ['', '같은 일행에서 현재 규칙(115% 선결제 후 차액 정산)과 비교(C10 일행에 가격만 사후 적용, 오차 1배):', '',
          '| 요청/일 | m | 확정 분담금 절약 | 현재 규칙 절약 | 절약 기준 위반 일행 |', '|---:|---:|---:|---:|---:|']
    for n in LEVELS:
        for m in (0., .01, .02):
            s = get('C10', n, m, 1.0); mt = s['metrics']
            L.append(f"| {n} | {pct(m)} | {pct(mt['fixed_saving_actual']['mean'])} | {pct(mt['current_saving_actual']['mean'])} | {s['observed_violating_groups']}/{s['observed_groups']} |")
    L += ['', '오차 크기 민감도(PRECHECK, 100건/일):', '', '| 오차 배수 | m | 학기 말 평균 | 기금(99%) | 이의제기 비율 |', '|---:|---:|---:|---:|---:|']
    for sp in SPREADS:
        for m in (0., .01, .02):
            s = get('PRECHECK', 100, m, sp)
            L.append(f"| {sp} | {pct(m)} | {s['reserve']['end_mean']:,.0f}원 | {s['reserve']['seed99']:,}원 | {pct(s['metrics']['exception_rate']['mean'])} |")
    L += ['', '잉여 정책(적립금 > 기금99%×2이면 이후 확정 가격을 m=0으로), 100건/일·오차 1배:', '',
          '| m | 학기 말 평균 | P(음수) | m=0 적용 운행 비율 | 1인당 평균 인하액 |', '|---:|---:|---:|---:|---:|']
    for m in (.01, .02, .03):
        sp = get('PRECHECK', 100, m, 1.0)['surplus_policy']
        L.append(f"| {pct(m)} | {sp['end_mean']:,.0f}원 | {pct(sp['probability_ever_negative'])} | {pct(sp['zero_margin_ride_share'])} | {sp['mean_rider_price_reduction_krw']:,.0f}원 |")
    L += ['', '## 2. 확정 시점 정책', '',
          '| 요청/일 | 정책 | 매칭률 [95% CI] | PROD 대비 | 1인당 절약 | 확정까지 대기(분) | 4인 일행 비율 | 하루 상한 차이 회복 |',
          '|---:|---|---:|---:|---:|---:|---:|---:|']
    for n in LEVELS:
        for p in POLICIES:
            t = tm[(n, p)]; m = t['metrics']; d = t['paired_difference_vs_prod']['match_rate']; rec = t['hindsight']['recovery_fraction']
            L.append(f"| {n} | {p} | {cell(m['match_rate'])} | {100*d['mean']:+.2f}%p | {pct(m['mean_saving']['mean'])} | {m['mean_wait']['mean']:.2f} | {pct(m['groups_4_rate']['mean'])} | {'-' if rec is None else pct(rec)} |")
    L += ['', '하루 상한(정확해) 매칭률: ' + ', '.join(f"{n}건/일 {pct(tm[(n, 'PROD')]['hindsight']['match_rate']['mean'])}" for n in LEVELS), '']
    p100 = get('PRECHECK', 100, .01, 1.0); w = tm[(100, 'WAIT-TO-DEADLINE')]; mk = tm[(100, 'MARKUP0.01')]
    L += ['## 추천', '',
          f"- 결제: 확정 분담금 + 적립금, 여유분 m=1%. 100건/일 매칭률 {pct(mk['metrics']['match_rate']['mean'])}, 추가 결제·환불 0, 1인당 절약 {pct(p100['metrics']['fixed_saving_actual']['mean'])}, 학기 말 적립금 평균 {p100['reserve']['end_mean']:,.0f}원, 99% 기금 {p100['reserve']['seed99']:,}원.",
          f"- 매칭: 마감까지 기다렸다 확정(WAIT-TO-DEADLINE). 100건/일 매칭률 {pct(w['metrics']['match_rate']['mean'])}({100*w['paired_difference_vs_prod']['match_rate']['mean']:+.2f}%p), 확정까지 대기 {w['metrics']['mean_wait']['mean']:.2f}분.",
          '- 예측 기반 정책은 단순한 마감 대기보다 낫지 않아 채택하지 않는다.', '', '## 가정과 한계', ''] + ['- ' + x for x in LIMITS]
    L += ['', f'운영 검증기 재실행 일치 그룹: {sum(t["production_feasibility_parity_groups"] for t in timing_out):,}개. 기존 C10 일행·청구 추출 일치: {parity}개 일행.', '']
    (REPORTS / 'c11-fixed-share-and-timing.md').write_text('\n'.join(L), encoding='utf-8')
    slim = [{k: v for k, v in s.items() if k != 'reserve'} | {'reserve': {k: v for k, v in s['reserve'].items() if k != 'trajectory'}} for s in summaries]
    artifacts = {p.relative_to(ROOT).as_posix(): run.sha(p) for p in sorted(CACHE.glob('*'))
                 if p.is_file() and p.suffix in ('.tsv', '.csv', '.json') and p.stat().st_size < 60_000_000}
    run.json_write(REPORTS / 'c11-fixed-share-and-timing.json', dict(
        generated_at=datetime.now(timezone.utc).isoformat(), bias=b, semester_days=SEMESTER, paths=PATHS,
        reserve=slim, timing=timing_out, forecast=dict(method=model['method'], compatibility=model['compatibility']),
        limitations=LIMITS, attribution=ATTRIBUTION, artifacts=artifacts))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--reuse-hindsight', action='store_true', help='reuse exact hindsight solves in .cache/c11/hindsight.json')
    a = ap.parse_args()
    java = compile_java(); b, data, demand = inputs()
    simulate(java, CACHE / 'train-cases.tsv', 'train', b)
    model = fit_forecast(data, demand, read_tsv(CACHE / 'train-groups.tsv'),
                         json.loads((CACHE / 'train-meta.json').read_text(encoding='utf-8')), {SEED + d * 104729 for d in range(DAYS)})
    write_forecast(model)
    simulate(java, CACHE / 'eval-cases.tsv', 'eval', b, CACHE / 'forecast.tsv')
    groups = read_tsv(CACHE / 'eval-groups.tsv'); days = c7.read_csv(CACHE / 'eval-days.csv')
    if a.reuse_hindsight and (CACHE / 'hindsight.json').exists():
        hindsight_rows = json.loads((CACHE / 'hindsight.json').read_text(encoding='utf-8'))
    else:
        hindsight_rows = hindsight(java)
    if len(hindsight_rows) != len(LEVELS) * DAYS:
        raise ValueError('Missing hindsight days')
    summaries, parity = finance(java, b, groups)
    timing_out = timing(groups, days, hindsight_rows); run.json_write(CACHE / 'timing-results.json', timing_out)
    write_report(b, summaries, timing_out, parity, model)
    print('C11 done', flush=True)


if __name__ == '__main__':
    main()
