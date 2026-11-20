"""Resumable future-directions calibration; credentials never enter artifacts/logs.

The duration model is a robust global median multiplier, fitted on training OD
pairs only. Hour-specific held-out errors expose unmodelled traffic variation.
"""
from __future__ import annotations
import argparse
from collections import defaultdict
import csv
from datetime import datetime, timedelta
import hashlib
import json
import math
import os
from pathlib import Path
import random
import statistics
import sys
import time
import urllib.parse
import urllib.request
import urllib.error

sys.path.insert(0,str(Path(__file__).resolve().parent))
from fares import korea_tariff
from osrm_matrix import DEFAULT, HERE, artifact, digest, read_csv, save, write_csv

API='https://apis-navi.kakaomobility.com/v1/future/directions'

def verify_sample(output):
    manifest=json.loads((Path(output)/'sample-MANIFEST.json').read_text(encoding='utf-8'))
    for source in manifest['inputs']+[manifest['sample']]:
        if digest(source['path'])!=source['sha256']: raise ValueError('Calibration input hash mismatch')

def band(km):
    return '0-5' if km<5 else '5-15' if km<15 else '15-30' if km<30 else '30+'

def weekday(now=None):
    # Numeric UTC+09 avoids requiring tzdata on Windows.
    from datetime import timezone
    now=now or datetime.now(timezone(timedelta(hours=9)))
    day=now.date()+timedelta(days=2)
    while day.weekday()>4: day+=timedelta(days=1)
    return day

def sample_pairs(destinations,matrix,n=400,seed=20261120,day=None):
    """Half hub OD, half local <=15km directed grid OD; balanced strata.

    Each OD appears once; split is by OD so test observations never leak into
    training. Hour draws follow the service-window living-move marginal.
    """
    rng=random.Random(seed); points={p['id']:p for p in destinations['points']}
    if n<2: raise ValueError('Sample must include at least two OD pairs')
    nodes={0:destinations['hub'],**points}; strata=defaultdict(list); seen=defaultdict(int)
    for r in matrix:
        i,j=int(r['i']),int(r['j']); km=float(r['km']); sec=float(r['seconds'])
        if i==j or j==0 or j not in points or i not in nodes or km<=0 or sec<=0: continue
        if i!=0 and km>15: continue
        kind='hub' if i==0 else 'local'
        key=(kind,band(km),points[j]['region_code']); seen[key]+=1
        row=dict(i=i,j=j,km=km,seconds=sec)
        # Reservoir bounded per stratum; do not retain the N-squared matrix.
        bucket=strata[key]
        if len(bucket)<n: bucket.append(row)
        else:
            index=rng.randrange(seen[key])
            if index<n: bucket[index]=row
    selected=[]
    for kind,quota in (('hub',n//2),('local',n-n//2)):
        buckets=[rows[:] for key,rows in sorted(strata.items()) if key[0]==kind]
        for rows in buckets: rng.shuffle(rows)
        rng.shuffle(buckets)
        chosen=[]
        while buckets and len(chosen)<quota:
            remaining=[]
            for rows in buckets:
                if len(chosen)>=quota: break
                chosen.append(rows.pop())
                if rows: remaining.append(rows)
            buckets=remaining
        selected+=chosen
    hours=[r for r in destinations['hours'] if 12<=int(r['hour'])<=21 and r['cnt']>0]
    if not hours: raise ValueError('Missing living-move service-hour counts')
    day=day or weekday(); rows=[]
    for r in selected:
        i,j=r['i'],r['j']; hour=int(rng.choices(hours,weights=[h['cnt'] for h in hours])[0]['hour'])
        key=f'{i}:{j}'
        # Stable hash grouped OD split; no fitting uses the held-out subset.
        split='test' if int(hashlib.sha256(key.encode()).hexdigest()[:8],16)%5==0 else 'train'
        rows.append(dict(pair_id=key,split=split,kind='hub' if i==0 else 'local',i=i,j=j,
            origin_lon=nodes[i]['lon'],origin_lat=nodes[i]['lat'],
            destination_lon=nodes[j]['lon'],destination_lat=nodes[j]['lat'],
            origin_region=nodes[i]['region_code'],destination_region=nodes[j]['region_code'],
            distance_band=band(r['km']),hour=hour,departure_time=f'{day:%Y%m%d}{hour:02}00',
            osrm_km=r['km'],osrm_seconds=r['seconds']))
    return rows

def load_key(env_path):
    key=os.environ.get('KAKAO_REST_KEY','')
    if not key and Path(env_path).exists():
        for line in Path(env_path).read_text(encoding='utf-8-sig').splitlines():
            if line.strip().startswith('KAKAO_REST_KEY='):
                key=line.split('=',1)[1].strip().strip('"\'')
    if not key: raise ValueError('KAKAO_REST_KEY missing in environment or repository .env')
    return key

def parse_response(data):
    routes=data.get('routes',[])
    if not routes or routes[0].get('result_code')!=0: raise ValueError('Kakao returned no successful route')
    s=routes[0]['summary']
    result=dict(kakao_km=float(s['distance'])/1000,kakao_seconds=float(s['duration']),
        kakao_taxi=float(s['fare']['taxi']))
    if any(not math.isfinite(v) or v<=0 for v in result.values()): raise ValueError('Invalid Kakao summary')
    return result

def query(row,key):
    params=dict(origin=f"{row['origin_lon']},{row['origin_lat']}",
        destination=f"{row['destination_lon']},{row['destination_lat']}",
        departure_time=row['departure_time'],priority='RECOMMEND',summary='true')
    req=urllib.request.Request(API+'?'+urllib.parse.urlencode(params),headers={'Authorization':'KakaoAK '+key})
    try:
        with urllib.request.urlopen(req,timeout=30) as f: data=json.load(f)
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'Kakao future directions HTTP {e.code}; completed rows retained; credentials omitted') from None
    except urllib.error.URLError as e:
        raise RuntimeError('Kakao future directions transport failed: '+str(e.reason)) from None
    except Exception:
        # Do not propagate request objects, authorization or response bodies.
        raise RuntimeError('Kakao future directions request failed; completed rows retained; rerun to resume') from None
    return parse_response(data)

def acquire(output,env_path,pause=.15,query_fn=query):
    output=Path(output); verify_sample(output); samples=read_csv(output/'calibration-sample.csv')
    target=output/'kakao-calibration.csv'
    existing=read_csv(target) if target.exists() else []
    expected={r['pair_id']:r for r in samples}
    if len(expected)!=len(samples): raise ValueError('Duplicate sample OD')
    if len({r['pair_id'] for r in existing})!=len(existing): raise ValueError('Duplicate calibration rows')
    for row in existing:
        if row['pair_id'] not in expected or any(str(row[k])!=str(expected[row['pair_id']][k]) for k in samples[0]):
            raise ValueError('Calibration sample changed; use a fresh output directory')
    done={r['pair_id'] for r in existing}; key=None
    for row in samples:
        if row['pair_id'] in done: continue
        departure=datetime.strptime(row['departure_time'],'%Y%m%d%H%M')
        if departure.date()<=weekday()-timedelta(days=2): raise ValueError('Sample departure expired; archive artifacts and generate fresh sample')
        if key is None: key=load_key(env_path)
        values=query_fn(row,key); existing.append(dict(row,**values))
        tmp=target.with_suffix('.csv.part'); write_csv(tmp,existing); tmp.replace(target)
        time.sleep(pause)
    print(f'Calibration rows complete: {len(existing)}; key never logged')

def fare_kind(region):
    # The only specifically modelled nonstandard tariff is Pocheon. Other
    # origins use the existing Seoul proxy; its geographic limits are reported.
    return 'pocheon' if region=='41650000' else 'seoul'

def fit(rows):
    if not rows or any(r['split'] not in ('train','test') for r in rows): raise ValueError('Invalid split')
    train=[r for r in rows if r['split']=='train']; test=[r for r in rows if r['split']=='test']
    if not train or not test: raise ValueError('Need both training and held-out OD')
    for r in rows:
        for k in ('osrm_seconds','osrm_km','kakao_seconds','kakao_km','kakao_taxi'):
            if not math.isfinite(float(r[k])) or float(r[k])<=0: raise ValueError('Nonpositive calibration value')
    ratio=statistics.median(float(r['kakao_seconds'])/float(r['osrm_seconds']) for r in train)
    def metrics(part):
        duration=[]; raw=[]; fares=[]; ratios=[]; distance=[]
        for r in part:
            sec=float(r['osrm_seconds'])*ratio; km=float(r['osrm_km']); actual=float(r['kakao_seconds'])
            duration.append(abs(sec-actual)/actual); raw.append(abs(float(r['osrm_seconds'])-actual)/actual)
            predicted=korea_tariff(km,sec/60,int(r['hour']),fare_kind(r['origin_region']))
            fares.append(abs(predicted-float(r['kakao_taxi']))/float(r['kakao_taxi']))
            ratios.append(actual/float(r['osrm_seconds']))
            distance.append(abs(km-float(r['kakao_km']))/float(r['kakao_km']))
        return dict(n=len(part),duration_mape=statistics.mean(duration),raw_duration_mape=statistics.mean(raw),
            median_ratio=statistics.median(ratios),fare_mape=statistics.mean(fares),distance_mape=statistics.mean(distance))
    return dict(model='training OD median Kakao / OSRM duration multiplier',duration_ratio=ratio,
        train_n=len(train),test_n=len(test),held_out=metrics(test),
        held_out_by_hour={str(h):metrics([r for r in test if int(r['hour'])==h]) for h in range(12,22) if any(int(r['hour'])==h for r in test)},
        held_out_by_band={b:metrics([r for r in test if r['distance_band']==b]) for b in sorted({r['distance_band'] for r in test})},
        limitations=['Future API estimates are not observed meter receipts.',
            'Single robust global multiplier applied to every ordered leg; hourly validation is descriptive, no traffic clock in matcher.',
            'Distance unchanged. Toll/border/instantaneous slow-speed clocks not reconstructed.',
            'Origin Pocheon uses Pocheon proxy; all other sample origins use existing Seoul proxy, not a full national tariff catalogue.'])

def prepare(output,n=400):
    output=Path(output)
    # Freeze once for resumability; never redraw a completed sample.
    if (output/'calibration-sample.csv').exists(): raise ValueError('Sample already exists; use query to resume')
    data=json.loads((output/'destinations.json').read_text(encoding='utf-8'))
    with (output/'raw_matrix.tsv').open(encoding='utf-8',newline='') as f:
        rows=sample_pairs(data,csv.DictReader(f,delimiter='\t'),n)
    if len(rows)<n: raise ValueError(f'Only {len(rows)} eligible OD pairs; sample target {n}')
    write_csv(output/'calibration-sample.csv',rows)
    save(output/'sample-MANIFEST.json',dict(inputs=[artifact(output/p) for p in ('destinations.json','raw_matrix.tsv')],
        sample=artifact(output/'calibration-sample.csv'),n=n,seed=20261120))

def export(output):
    output=Path(output); verify_sample(output)
    sample=read_csv(output/'calibration-sample.csv'); rows=read_csv(output/'kakao-calibration.csv')
    if {r['pair_id'] for r in sample}!={r['pair_id'] for r in rows}: raise ValueError('Calibration incomplete')
    by={r['pair_id']:r for r in sample}
    if len(by)!=len(sample) or len({r['pair_id'] for r in rows})!=len(rows): raise ValueError('Duplicate calibration OD')
    if any(any(str(r[key])!=str(by[r['pair_id']][key]) for key in sample[0]) for r in rows):
        raise ValueError('Calibration OD metadata differs from sample')
    model=fit(rows); save(output/'calibration-model.json',model)
    temp=output/'matrix.tsv.part'
    with (output/'raw_matrix.tsv').open(encoding='utf-8',newline='') as src,temp.open('w',encoding='utf-8',newline='') as dst:
        writer=csv.DictWriter(dst,fieldnames=['i','j','km','seconds'],delimiter='\t'); writer.writeheader()
        for r in csv.DictReader(src,delimiter='\t'):
            r['seconds']=math.ceil(float(r['seconds'])*model['duration_ratio']); writer.writerow(r)
    temp.replace(output/'matrix.tsv')
    data=json.loads((output/'destinations.json').read_text(encoding='utf-8'))
    solos=[]
    for p in data['points']:
        sec=math.ceil(p['solo_seconds']*model['duration_ratio'])
        solos.append(dict(point=p['id'],direction='FROM_HUB',km=p['solo_km'],seconds=sec,
            fare=korea_tariff(p['solo_km'],sec/60,12,'pocheon')))
    write_csv(output/'solo.csv',solos)
    manifest=json.loads((output/'MANIFEST.json').read_text(encoding='utf-8'))
    manifest.update(status='CALIBRATED',calibration=model)
    manifest['files']=[artifact(output/p) for p in ('destinations.json','raw_matrix.tsv','snaps.csv',
        'calibration-sample.csv','sample-MANIFEST.json','kakao-calibration.csv','calibration-model.json','matrix.tsv','solo.csv')]
    manifest['calibration_tool']=artifact(Path(__file__))
    save(output/'MANIFEST.json',manifest); print(json.dumps(model,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('action',choices=['sample','query','fit'])
    p.add_argument('--output',type=Path,default=DEFAULT); p.add_argument('--n',type=int,default=400)
    p.add_argument('--env',type=Path,default=HERE.parent/'.env')
    a=p.parse_args()
    if a.action=='sample': prepare(a.output,a.n)
    elif a.action=='query': acquire(a.output,a.env)
    else: export(a.output)
