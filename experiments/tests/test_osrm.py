import copy
import json
from datetime import date
from pathlib import Path
import sys
import tempfile
import subprocess
from unittest.mock import patch

import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import osrm_matrix as o
import kakao_calibration as k
import grid_demand
import detailed

@pytest.mark.parametrize('script',['osrm_matrix.py','kakao_calibration.py','compare_c7.py'])
def test_cli_entrypoints_under_isolated_python(script):
    result=subprocess.run([sys.executable,'-I',str(Path(__file__).resolve().parents[1]/script),'--help'],
        capture_output=True,text=True)
    assert result.returncode==0,result.stderr
    assert 'usage:' in result.stdout

def test_minimum_grid_prefix_and_original_denominator():
    points=[dict(id=1,grid='a',region_code='a',trips=60,solo_km=5),
        dict(id=2,grid='b',region_code='a',trips=39.5,solo_km=10),
        dict(id=3,grid='c',region_code='a',trips=.5,solo_km=20),
        dict(id=4,grid='d',region_code='b',trips=100,solo_km=70)]
    selected,meta=o.select_grids(points,{'a':80,'b':20})
    assert [p['id'] for p in selected]==[1,2]
    assert meta['represented_flow_share']==pytest.approx(.796)
    assert meta['retained_eligible_share']==pytest.approx(.995)
    assert meta['excluded_regions'][0]['code']=='b'
    assert meta['service_excluded_share']==pytest.approx(.2)
    assert selected[0]['weight']==pytest.approx(.48)
    assert sum(p['probability'] for p in selected)==pytest.approx(1)

def test_partial_region_range_does_not_redistribute_lost_weight():
    p=[dict(id=1,grid='a',region_code='r',trips=10,solo_km=2),
       dict(id=2,grid='b',region_code='r',trips=90,solo_km=None)]
    _,m=o.select_grids(p,{'r':100})
    assert m['represented_flow_share']==pytest.approx(.1)
    assert m['service_excluded_share']==pytest.approx(.9)

class FakeOSRM:
    def __init__(self,null=False): self.null=null
    def table(self,sources,targets):
        return dict(sources=[dict(distance=10) for _ in sources],
            destinations=[dict(distance=350) for _ in targets],
            distances=[[None if self.null else 1000+100*s['id']+t['id'] for t in targets] for s in sources],
            durations=[[60+s['id']+t['id'] for t in targets] for s in sources])

def test_directed_table_units_and_snap_flags():
    a,b=dict(id=1),dict(id=2)
    rows,snaps=o.table_rows(FakeOSRM(),[a,b],[a,b])
    assert rows[1]['km']==1.102
    assert rows[2]['km']==1.201
    assert rows[1]['seconds']==63
    assert snaps[2]==350
    with pytest.raises(ValueError,match='No route'): o.table_rows(FakeOSRM(True),[a],[b])

def data_fixture():
    pts=[dict(id=i,grid=str(i),lat=37.8+i*.001,lon=127.2,region_code='41650000',
         region_name='포천시',trips=100,solo_km=1+i,solo_seconds=100+i,weight=1/30,probability=1/30) for i in range(1,31)]
    data=dict(hub=o.HUB,points=pts,hours=[dict(hour=h,cnt=h) for h in range(12,22)],days=20,
        age_check=dict(share_2030=.4))
    ids=range(31)
    matrix=[dict(i=i,j=j,km=0 if i==j else 1+abs(i-j)*.1,seconds=0 if i==j else 100+abs(i-j)) for i in ids for j in ids]
    return data,matrix

def test_sampling_stratifies_and_freezes_od_split():
    data,matrix=data_fixture()
    rows=k.sample_pairs(data,matrix,n=40,day=date(2026,11,23))
    assert rows==k.sample_pairs(data,matrix,n=40,day=date(2026,11,23))
    assert len({r['pair_id'] for r in rows})==40
    assert sum(r['kind']=='hub' for r in rows)==20
    assert all(12<=r['hour']<=21 for r in rows)
    assert all(r['departure_time'].startswith('20261123') for r in rows)
    assert {r['split'] for r in rows}=={'train','test'}

def calibrated_fixture():
    data,matrix=data_fixture()
    rows=k.sample_pairs(data,matrix,n=40,day=date(2099,11,23))
    for r in rows:
        r.update(kakao_km=r['osrm_km'],kakao_seconds=r['osrm_seconds']*2,
            kakao_taxi=k.korea_tariff(r['osrm_km'],r['osrm_seconds']*2/60,r['hour'],'pocheon'))
    return data,matrix,rows

def test_held_out_does_not_fit_multiplier_and_fare_origin():
    _,_,rows=calibrated_fixture()
    model=k.fit(rows)
    assert model['duration_ratio']==2
    assert model['held_out']['duration_mape']==0
    assert model['held_out']['fare_mape']==0
    changed=copy.deepcopy(rows)
    for r in changed:
        if r['split']=='test': r['kakao_seconds']*=2
    second=k.fit(changed)
    assert second['duration_ratio']==2
    assert second['held_out']['duration_mape']==.5
    assert k.fare_kind('41650000')=='pocheon'
    assert k.fare_kind('11320000')=='seoul'

def test_kakao_response_rejects_missing_failed_and_invalid():
    assert k.parse_response(dict(routes=[dict(result_code=0,summary=dict(distance=1000,duration=100,fare=dict(taxi=4800)))]))['kakao_km']==1
    for response in ({},dict(routes=[dict(result_code=104)])):
        with pytest.raises(ValueError): k.parse_response(response)

def test_resume_skips_existing_and_preserves_success_on_failure(tmp_path):
    _,_,rows=calibrated_fixture(); samples=rows[:3]
    clean=[{a:b for a,b in r.items() if not a.startswith('kakao_')} for r in samples]
    o.write_csv(tmp_path/'calibration-sample.csv',clean)
    o.save(tmp_path/'sample-MANIFEST.json',dict(inputs=[],sample=o.artifact(tmp_path/'calibration-sample.csv')))
    o.write_csv(tmp_path/'kakao-calibration.csv',samples[:1])
    calls=[]
    def request(row,key):
        calls.append(row['pair_id'])
        if len(calls)==2: raise RuntimeError('offline')
        return dict(kakao_km=1,kakao_seconds=200,kakao_taxi=4800)
    with patch.object(k,'load_key',return_value='fixture-secret'):
        with pytest.raises(RuntimeError,match='offline'): k.acquire(tmp_path,tmp_path/'env',0,request)
    assert len(o.read_csv(tmp_path/'kakao-calibration.csv'))==2
    assert calls[0]==str(samples[1]['pair_id'])
    assert 'fixture-secret' not in (tmp_path/'kakao-calibration.csv').read_text()

def test_export_adapter_and_cases_end_to_end(tmp_path):
    data,matrix,rows=calibrated_fixture()
    data['coverage']=dict(candidate_points=30,unique_grids=30,represented_flow_share=.99,
        pocheon_simulated_share=1.,resolution='500m center')
    o.save(tmp_path/'destinations.json',data)
    o.write_csv(tmp_path/'raw_matrix.tsv',matrix,delimiter='\t')
    o.write_csv(tmp_path/'snaps.csv',[dict(id=0,snap_m=0)])
    samples=[{a:b for a,b in r.items() if not a.startswith('kakao_')} for r in rows]
    o.write_csv(tmp_path/'calibration-sample.csv',samples)
    o.save(tmp_path/'sample-MANIFEST.json',dict(inputs=[],sample=o.artifact(tmp_path/'calibration-sample.csv')))
    o.write_csv(tmp_path/'kakao-calibration.csv',rows)
    o.save(tmp_path/'MANIFEST.json',dict(status='RAW_MATRIX_COMPLETE',inputs=[],osm=o.OSM,snap_over_300m=0))
    k.export(tmp_path)
    hub,points,hours,header,meta=grid_demand.load_inputs(tmp_path)
    assert points[0]['fare']==k.korea_tariff(points[0]['km'],points[0]['seconds']/60,12,'pocheon')
    assert meta['calibration_rows']==40
    detailed.write_cases(tmp_path/'cases.tsv',[detailed.Scenario()],1,1,points,hours,header,[0])
    assert 'CACHE_MATRIX' in (tmp_path/'cases.tsv').read_text()
    with (tmp_path/'matrix.tsv').open('a') as f: f.write('1\t2\t1\t1\n')
    with pytest.raises(ValueError,match='hash mismatch'): grid_demand.load_inputs(tmp_path)

def test_incomplete_crosswalk_never_silently_limits_to_legacy(tmp_path):
    public=tmp_path/'kr-public'; public.mkdir()
    o.write_csv(public/'tims-regions.csv',[dict(grid='x')])
    o.save(public/'tims-region-MANIFEST.json',dict(output=o.artifact(public/'tims-regions.csv'),
        mois_to_sgis={'41650000':'31270'}))
    o.save(public/'living-move-summary.json',dict(destination_home=[dict(d_code='11350630',cnt=10)]))
    with pytest.raises(ValueError,match='11350000'): o.universe(tmp_path)

def test_crosswalk_groups_union_both_geographies_once(tmp_path):
    public=tmp_path/'kr-public'; public.mkdir()
    grids=[dict(grid='a',center_e=950000,center_n=1950000,sgis_sigungu='23010',sigungu_name='중구',status='MATCHED',trips=30),
           dict(grid='b',center_e=950500,center_n=1950000,sgis_sigungu='23020',sigungu_name='동구',status='MATCHED',trips=70),
           dict(grid='c',center_e=951000,center_n=1950000,sgis_sigungu='11110',sigungu_name='노원구',status='MATCHED',trips=50)]
    o.write_csv(public/'tims-regions.csv',grids)
    o.save(public/'tims-region-MANIFEST.json',dict(output=o.artifact(public/'tims-regions.csv'),mois_to_sgis={},
        sgis_region_names={'23010':'중구','23020':'동구','11110':'노원구'}))
    o.save(public/'living-move-summary.json',dict(destination_home=[dict(d_code='28125000',cnt=20),
        dict(d_code='28155000',cnt=40),dict(d_code='11350630',cnt=30),dict(d_code='11350611',cnt=10)]))
    rows=[dict(sgis=s,mois=m,group='INCHEON_2026') for s in ('23010','23020') for m in ('28125000','28155000')]
    rows.append(dict(sgis='11110',mois='11350000',group=''))
    o.write_csv(public/'livingmove-sgis-crosswalk.csv',rows)
    points,flows,_=o.universe(tmp_path)
    assert flows=={'INCHEON_2026':60,'11350000':40}
    assert len(points)==3
    assert sum(p['trips'] for p in points if p['region_code']=='INCHEON_2026')==100
    for p in points: p['solo_km']=10
    selected,meta=o.select_grids(points,flows,coverage=1)
    assert {p['grid']:p['weight'] for p in selected}==pytest.approx({'a':.18,'b':.42,'c':.4})

def test_weekday_skips_weekend():
    from datetime import datetime
    assert k.weekday(datetime(2026,11,19))==date(2026,11,23)

def test_chunked_build_resumes_and_reports_snaps(tmp_path):
    points=[dict(id=i,grid=str(i),region_code='41650000',region_name='포천시',
        lat=37.875+i*.001,lon=127.205,trips=100) for i in (1,2)]
    cache=tmp_path/'cache'; public=cache/'kr-public'; public.mkdir(parents=True)
    for name in ('tims-regions.csv','tims-region-MANIFEST.json','living-move-summary.json'):
        (public/name).write_text('fixture')
    summary=dict(hours_home=[dict(hour=12,cnt=1)],days=20,age_check={})
    out=tmp_path/'out'; fake=FakeOSRM()
    with patch.object(o,'universe',side_effect=lambda *args:(copy.deepcopy(points),{'41650000':100},summary)), patch.object(o,'OSRM',return_value=fake):
        o.build(cache,out,chunk=1)
        first=(out/'raw_matrix.tsv').read_bytes()
        with patch.object(fake,'table',side_effect=AssertionError('should reuse chunks')):
            o.build(cache,out,chunk=1)
    assert (out/'raw_matrix.tsv').read_bytes()==first
    manifest=json.loads((out/'MANIFEST.json').read_text())
    assert manifest['all_ordered_pairs']==9
    assert manifest['snap_over_300m']==2
    assert len(o.read_csv(out/'raw_matrix.tsv','\t'))==9
