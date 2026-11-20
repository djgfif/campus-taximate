"""C9 known p-median fixtures and tests against the actual Java policy code."""
import itertools,json,subprocess,sys
from pathlib import Path
import numpy as np
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from dropoff_hubs import pmedian,walk_km
from dropoff_designs import measure
HERE=Path(__file__).resolve().parents[1]

def test_pmedian_known_line_optimum():
    points=np.array([0,1,2,8,9,10]);a=np.abs(points[:,None]-points[None,:]);w=np.array([1,1,1,1,1,1])/6
    selected,cost=pmedian(a,w,2)
    optimum=min(float(np.min(a[:,s],axis=1)@w) for s in itertools.combinations(range(6),2))
    assert cost==pytest.approx(optimum)
    assert selected==[1,4]

def test_pmedian_is_demand_weighted_and_directed():
    a=[[0,9,2],[1,0,3]]
    assert pmedian(a,[.01,.99],1)[0]==[1]
    assert pmedian(a,[.99,.01],1)[0]==[0]
    with pytest.raises(ValueError):pmedian(a,[1,0],4)

def java(*args):
    cmd=HERE/'.cache/c9/java-command.json'
    assert cmd.exists(),'Compile C9 adapter with dropoff_designs.py first'
    return subprocess.check_output(json.loads(cmd.read_text(encoding='utf-8'))+list(map(str,args)),text=True).strip()

def test_last_mile_walk_boundary_and_taxi_tariff():
    cost,seconds,walking=map(float,java('--last',1.2).split(','))
    assert (cost,seconds,walking)==pytest.approx((0,960,16))
    cost,seconds,walking=map(float,java('--last',1.20001).split(','))
    assert (cost,seconds,walking)==(7000,300,0)
    assert walk_km(dict(lat=37,lon=127),dict(lat=37,lon=127))==0

def test_individual_rationality_strict_no_saving_is_solo():
    assert java('--accept',9000,1000,10000,0,'false','true')=='false'
    assert java('--accept',8999,1000,10000,100,'false','true')=='true'
    assert java('--accept',10000,1000,10000,0,'false','true')=='false'

def test_hybrid_min_saving_max_delay_and_walk_only():
    assert java('--accept',8000,1000,10000,15,'true','true')=='true'
    assert java('--accept',8001,1000,10000,15,'true','true')=='false'
    assert java('--accept',8000,1000,10000,15.001,'true','true')=='false'
    assert java('--accept',8000,1000,10000,15,'true','false')=='false'
    assert java('--accept',9000,0,10000,15,'true','false')=='true'

def test_production_settlement_conservation():
    assert java('--self-test')=='C9 self-test PASS'

def fixture():
    return [dict(case_id='base_day00',policy='A',id=str(i),group='[1;2]',size='2',solo='10000',shared_share=str(share),last_cost='0',total_cost=str(share),expected_share=str(share),expected_bill='15001',actual_bill='15001',walking='0',delay='0',suggestion='0') for i,share in ((1,7500),(2,7501))]

def test_measure_catches_money_and_duplicate_rider_errors():
    assert measure(fixture(),'base_day00','A',3)['total_saving_krw']==4999
    bad=fixture();bad[0]['actual_bill']='15000'
    with pytest.raises(AssertionError):measure(bad,'base_day00','A',3)
    bad=fixture();bad[1]['id']='1'
    with pytest.raises(AssertionError):measure(bad,'base_day00','A',3)

def test_completed_report_and_ninety_days():
    report=HERE/'reports/c9-dropoff-designs.json'
    if not report.exists():pytest.skip('Full experiment not executed yet')
    r=json.loads(report.read_text(encoding='utf-8'))
    assert r['status']=='COMPLETED'
    assert r['verification']['policy_days']==810
    assert r['verification']['conservation_errors']==0
    assert not r['verification']['c7_matched_count_mismatches']
    for pols in r['results'].values():
        for metrics in pols.values():
            assert metrics['match_rate']['valid_days']==30


def test_full_run_expected_ir_and_hybrid_acceptance():
    import csv
    path=HERE/'.cache/c9/riders.csv'
    if not path.exists():pytest.skip('No full simulation output')
    fares={};cid=None
    for line in (HERE/'.cache/c9/cases.tsv').read_text(encoding='utf-8').splitlines():
        p=line.split('\t')
        if p[0]=='CASE':cid=p[2]
        elif p[0]=='R':fares[cid,p[1]]=int(p[7])
    with path.open(encoding='utf-8',newline='') as f:rows=list(csv.DictReader(f))
    proposal_groups={(r['case_id'],r['policy'],r['group']) for r in rows if r['suggestion']=='1'}
    for r in rows:
        expected=int(r['expected_share'])+int(r['last_cost']);solo=fares[r['case_id'],r['id']]
        if r['policy'].startswith('B-'):assert expected<solo
        if (r['case_id'],r['policy'],r['group']) in proposal_groups:
            assert expected<=.9*solo+1e-9
            assert float(r['delay'])<=15+1e-9
        if r['policy']=='C-walk':assert int(r['last_cost'])==0
