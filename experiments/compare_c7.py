"""Compare grid C7 with a preserved 118-point report, without rerunning C6."""
import argparse
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from osrm_matrix import artifact, save

HERE=Path(__file__).resolve().parent
HEADLINES=('base','n50_d15','participation150','error_x1_5','error_x2','exclude_intra_pocheon')
METRICS=('matching_rate','mean_ratio_saving','mean_wait_minutes','mean_delay_minutes','ratio_additional_rate')

def compare(old_path,new_path,output):
    old=json.loads(Path(old_path).read_text(encoding='utf-8'))
    new=json.loads(Path(new_path).read_text(encoding='utf-8'))
    if old.get('demand',{}).get('candidate_points',old.get('candidate_points'))!=118 or 'calibration' not in new['demand']:
        raise ValueError('Expected historical 118-point versus calibrated grid reports')
    by_old={s['parameters']['name']:s for s in old['scenarios']}
    by_new={s['parameters']['name']:s for s in new['scenarios']}
    rows=[]
    for name in HEADLINES:
        rows.append(dict(scenario=name,requests=by_new[name]['parameters']['requests'],
            metrics={key:dict(old=by_old[name]['metrics'][key],new=by_new[name]['metrics'][key]) for key in METRICS}))
    report=dict(inputs=[artifact(old_path),artifact(new_path)],
        old_points=118,new_grids=new['demand']['candidate_points'],coverage=new['demand'],
        calibration=new['demand']['calibration'],headlines=rows,
        limitation='Destination universe, routes, duration correction and solo tariffs change together; differences are not isolated causal effects. Both studies are simulated proxy demand, not measured student outcomes.')
    save(output,report)
    def fmt(value,key):
        if value is None: return 'NA'
        return f'{value:.2%}' if key in ('matching_rate','mean_ratio_saving','ratio_additional_rate') else f'{value:.2f}'
    lines=['# C7 grid vs historical 118-point results','',report['limitation'],'',
        '| Scenario | Requests/day | Matching old → grid | Ratio saving old → grid | Wait min old → grid | Delay min old → grid | Additional-payment rate old → grid |',
        '|---|---:|---:|---:|---:|---:|---:|']
    for row in rows:
        cells=[fmt(row['metrics'][key]['old']['mean'],key)+' → '+fmt(row['metrics'][key]['new']['mean'],key) for key in METRICS]
        lines.append('| '+row['scenario']+' | '+str(row['requests'])+' | '+' | '.join(cells)+' |')
    Path(output).with_suffix('.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('\n'.join(lines))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--old',type=Path,default=HERE/'reports/c7-old118-headlines.json')
    p.add_argument('--new',type=Path,default=HERE/'reports/c7-detailed-sim.json')
    p.add_argument('--output',type=Path,default=HERE/'reports/c7-grid-comparison.json')
    a=p.parse_args(); compare(a.old,a.new,a.output)
