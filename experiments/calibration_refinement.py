"""Train-only five-fold model selection; one frozen held-out acceptance check."""
from __future__ import annotations
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent))
import kakao_calibration as k
from osrm_matrix import DEFAULT, HERE, read_csv, save, artifact, write_csv

FORMS=('global','distance_bands','affine')
MAX_BAND_REGRESSION=.02  # predeclared absolute MAPE tolerance, vs existing multiplier

def train(rows, form):
    ratio=statistics.median(float(r['kakao_seconds'])/float(r['osrm_seconds']) for r in rows)
    model=dict(form=form,ratio=ratio)
    if form=='distance_bands':
        model['ratios']={b:statistics.median(float(r['kakao_seconds'])/float(r['osrm_seconds']) for r in rows if k.band(float(r['osrm_km']))==b)
                         for b in sorted({k.band(float(r['osrm_km'])) for r in rows})}
    if form=='affine':
        x=np.array([float(r['osrm_seconds']) for r in rows]); y=np.array([float(r['kakao_seconds']) for r in rows])
        # Nonnegative weighted least squares (relative error), 2-dimensional active sets.
        a=np.column_stack((np.ones(len(x)),x))/y[:,None]; target=np.ones(len(x))
        unconstrained=np.linalg.lstsq(a,target,rcond=None)[0]
        candidates=[np.array([0.,max(0.,float(np.dot(a[:,1],target)/np.dot(a[:,1],a[:,1])))]),
                    np.array([max(0.,float(np.dot(a[:,0],target)/np.dot(a[:,0],a[:,0]))),0.])]
        if min(unconstrained)>=0: candidates.append(unconstrained)
        best=min(candidates,key=lambda c:float(np.sum((a@c-target)**2)))
        model.update(a=float(best[0]),b=float(best[1]))
    return model

def predict(model, seconds, km):
    if seconds==0: return 0.
    if model['form']=='affine': return model['a']+model['b']*seconds
    return seconds*(model.get('ratios',{}).get(k.band(km),model['ratio']))

def metrics(rows, model):
    return dict(n=len(rows),duration_mape=statistics.mean(abs(predict(model,float(r['osrm_seconds']),float(r['osrm_km']))-float(r['kakao_seconds']))/float(r['kakao_seconds']) for r in rows))

def select(train_rows):
    # Stable OD folds; held-out observations are not accepted by this interface.
    if any(r['split']!='train' for r in train_rows): raise ValueError('Only training split allowed')
    fold=lambda r:int(hashlib.sha256(r['pair_id'].encode()).hexdigest()[8:16],16)%5
    scores={}
    for form in FORMS:
        errors=[]; folds=[]
        for j in range(5):
            tr=[r for r in train_rows if fold(r)!=j]; val=[r for r in train_rows if fold(r)==j]
            if not tr or not val: continue
            m=train(tr,form); score=metrics(val,m); folds.append(score)
            errors.extend([score['duration_mape']]*len(val))
        scores[form]=dict(mape=statistics.mean(errors),folds=folds)
    chosen=min(FORMS,key=lambda form:scores[form]['mape'])
    return train(train_rows,chosen),scores

def refine(output=DEFAULT):
    output=Path(output); k.verify_sample(output)
    rows=read_csv(output/'kakao-calibration.csv')
    tr=[r for r in rows if r['split']=='train']; held=[r for r in rows if r['split']=='test']
    chosen,cv=select(tr); baseline=train(tr,'global')
    bands=sorted({k.band(float(r['osrm_km'])) for r in held})
    by={b:dict(selected=metrics([r for r in held if k.band(float(r['osrm_km']))==b],chosen),
                    baseline=metrics([r for r in held if k.band(float(r['osrm_km']))==b],baseline),
                    raw=metrics([r for r in held if k.band(float(r['osrm_km']))==b],dict(form='global',ratio=1.))) for b in bands}
    old=metrics(held,baseline); new=metrics(held,chosen)
    accepted=new['duration_mape']<old['duration_mape'] and all(v['selected']['duration_mape']<=v['baseline']['duration_mape']+MAX_BAND_REGRESSION for v in by.values())
    report=dict(status='ACCEPTED' if accepted else 'KEEP_EXISTING',train_n=len(tr),test_n=len(held),
        rule='Select smallest five-fold train mean MAPE; accept if held-out overall improves and no band regresses over 2 absolute percentage points versus existing model. No reselection on test.',
        cv=cv,selected=chosen,baseline=baseline,held_out_selected=new,held_out_baseline=old,held_out_by_band=by,
        limitations=['Future Kakao estimates, not observed trip times.','No hourly traffic model.','Affine correction is per ordered leg and is not additive across segmentation; selected distance bands also depend on leg length.'])
    if accepted:
        previous=json.loads((output/'calibration-model.json').read_text(encoding='utf-8'))
        save(output/'step1-calibration-model.json',previous)
        model=dict(model='five-fold training-selected duration correction',duration_ratio=baseline['ratio'],correction=chosen,
                   train_n=len(tr),test_n=len(held),held_out=new,held_out_by_band=by,selection=report,
                   limitations=report['limitations'])
        temp=output/'matrix.tsv.part'
        with (output/'raw_matrix.tsv').open(encoding='utf-8',newline='') as src,temp.open('w',encoding='utf-8',newline='') as dst:
            reader=csv.DictReader(src,delimiter='	'); writer=csv.DictWriter(dst,fieldnames=reader.fieldnames,delimiter='	');writer.writeheader()
            for r in reader:
                r['seconds']=math.ceil(predict(chosen,float(r['seconds']),float(r['km'])));writer.writerow(r)
        temp.replace(output/'matrix.tsv')
        data=json.loads((output/'destinations.json').read_text(encoding='utf-8'))
        solos=[]
        for p in data['points']:
            sec=math.ceil(predict(chosen,p['solo_seconds'],p['solo_km']))
            solos.append(dict(point=p['id'],direction='FROM_HUB',km=p['solo_km'],seconds=sec,fare=k.korea_tariff(p['solo_km'],sec/60,12,'pocheon')))
        write_csv(output/'solo.csv',solos);save(output/'calibration-model.json',model)
        manifest=json.loads((output/'MANIFEST.json').read_text(encoding='utf-8'));manifest.update(status='CALIBRATED',calibration=model)
        manifest['files']=[artifact(output/p) for p in ('destinations.json','raw_matrix.tsv','snaps.csv','calibration-sample.csv','sample-MANIFEST.json','kakao-calibration.csv','calibration-model.json','matrix.tsv','solo.csv')]
        manifest['calibration_tool']=artifact(Path(__file__));save(output/'MANIFEST.json',manifest)
        save(HERE/'reports'/'c7-osrm-calibration.json',model)
    save(HERE/'reports'/'c8-calibration-selection.json',report)
    print(json.dumps(report,ensure_ascii=False))
    return report

if __name__=='__main__': refine()
