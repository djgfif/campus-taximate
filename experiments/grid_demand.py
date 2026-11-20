"""Strict C7 grid input adapter; independent of C6's fixed historical cohort."""
import csv
import json
import math
from pathlib import Path
from osrm_matrix import digest, read_csv

def load_inputs(cache):
    cache=Path(cache)
    manifest=json.loads((cache/'MANIFEST.json').read_text(encoding='utf-8'))
    if manifest['status']!='CALIBRATED': raise ValueError('Fit Kakao calibration before C7')
    for source in manifest['inputs']+manifest['files']:
        if digest(source['path'])!=source['sha256']: raise ValueError('Routing input hash mismatch: '+source['path'])
    data=json.loads((cache/'destinations.json').read_text(encoding='utf-8'))
    hub=data['hub']; points=data['points']; lookup={int(r['point']):r for r in read_csv(cache/'solo.csv')}
    for p in points:
        r=lookup[p['id']]
        p.update(km=float(r['km']),seconds=int(r['seconds']),fare=int(r['fare']))
        if not all(math.isfinite(p[k]) and p[k]>0 for k in ('km','seconds','fare','weight')):
            raise ValueError('Invalid grid solo route/weight')
    ids={p['id'] for p in points}|{0}
    if len(ids)!=len(points)+1: raise ValueError('Duplicate grid IDs')
    # Compact N-squared bitmap avoids millions of Python tuple objects.
    order={v:i for i,v in enumerate(sorted(ids))}; n=len(ids); pairs=bytearray(n*n); count=0
    with (cache/'matrix.tsv').open(encoding='utf-8',newline='') as f:
        for r in csv.DictReader(f,delimiter='\t'):
            pair=(int(r['i']),int(r['j']))
            if pair[0] not in order or pair[1] not in order: raise ValueError('Unknown matrix ID')
            index=order[pair[0]]*n+order[pair[1]]
            if pairs[index]: raise ValueError('Duplicate matrix pair')
            km=float(r['km']); seconds=int(r['seconds'])
            if not math.isfinite(km) or km<0 or seconds<0: raise ValueError('Invalid matrix leg')
            pairs[index]=1; count+=1
    if count!=n*n: raise ValueError('Matrix incomplete for destination universe')
    counts={int(r['hour']):r['cnt'] for r in data['hours']}; denom=sum(counts.get(h,0) for h in range(12,22))
    if denom<=0: raise ValueError('No service-window home flow')
    hours=[dict(hour=h,estimated_home_moves=counts.get(h,0),probability=counts.get(h,0)/denom if 12<=h<=21 else 0) for h in range(24)]
    # Solo fares are Pocheon daytime tariff for FROM_HUB requests. All service
    # hours 12..21 have the same surcharge, so per-request hours do not alter it.
    header=['CASE','C7_PUBLIC_PROXY','grid','timeline','FROM_HUB','0',str(hub['lat']),str(hub['lon']),
        '1.3','24','KAKAO','5093.680061150312','1151.8582180060432','0','100','4800','CACHE_MATRIX','KRW']
    meta=dict(data['coverage'],hourly_distribution=hours,livingmove_days=data['days'],
        age_check_share_2030=data['age_check']['share_2030'],weekday_home_estimated_moves=sum(counts.values()),
        time_rule='2026-10 Pocheon-origin living-move purpose 3 start hours; 12..21 KST; uniform within hour',
        matrix_complete_for_selected_points=True,tims_input_is_student_od=False,
        calibration=manifest['calibration'],calibration_rows=manifest['calibration']['train_n']+manifest['calibration']['test_n'],
        snap_over_300m=manifest['snap_over_300m'],osm=manifest['osm'])
    return hub,points,hours,header,meta
