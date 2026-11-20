"""Known optima, brute-force cross-checks and live production feasibility parity."""
import copy
import itertools
import json
from pathlib import Path
import random
import sys
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import optimality_gap as o
import calibration_refinement as c


def group(ids,saving=100,meters=100):
    return dict(key=','.join(map(str,sorted(ids))),ids=tuple(sorted(ids)),expected_saving_krw=saving,saved_meters=meters)


def test_greedy_is_suboptimal_but_exact_finds_six_riders():
    # Greedy picks the size-four edge first and blocks both size-three edges.
    groups=[group([1,2,3,4]),group([1,2,5]),group([3,4,6])]
    chosen,info=o.solve_exact(groups)
    assert set(chosen)=={'1,2,5','3,4,6'}
    assert info['score']==(0,6,0,200)
    assert info['status']=='EXACT'


def test_last_chance_precedes_matched_count():
    groups=[group([1,2]),group([2,3,4,5])]
    chosen,_=o.solve_exact(groups,last=[1])
    assert chosen==['1,2']


def test_wait_then_distance_then_flattened_ids():
    groups=[group([1,2],meters=1),group([2,3],meters=999)]
    assert o.solve_exact(groups,arrivals={1:0,2:50,3:100},second=100)[0]==['1,2']
    assert o.solve_exact(groups)[0]==['2,3']
    assert o.solve_exact([group([1,2]),group([2,3])])[0]==['1,2']


def test_saving_objective_trades_riders_for_money():
    groups=[group([1,2,3,4],saving=10),group([1,2],saving=20)]
    assert o.solve_exact(groups)[0]==['1,2,3,4']
    assert o.solve_exact(groups,'saving')[0]==['1,2']
    assert o.solve_exact(groups,'hindsight')[0]==['1,2,3,4']


def test_hindsight_secondary_objective_is_money():
    groups=[group([1,2],saving=1),group([2,3],saving=2)]
    assert o.solve_exact(groups,'hindsight')[0]==['2,3']


@pytest.mark.parametrize('objective',['lex','saving','hindsight'])
def test_subset_dp_matches_independent_exhaustive_packings(objective):
    rng=random.Random(901)
    for trial in range(25):
        candidates=list(itertools.combinations(range(1,8),2))+list(itertools.combinations(range(1,8),3))
        groups=[group(ids,rng.randrange(1,100),rng.randrange(1,100)) for ids in rng.sample(candidates,8)]
        arrivals={i:rng.randrange(100) for i in range(1,8)};last={1,4}
        def score(indices):
            gg=[groups[i] for i in indices];ids=[i for g in gg for i in g['ids']]
            if len(ids)!=len(set(ids)):return None
            if objective=='lex':s=(sum(i in last for i in ids),len(ids),sum(100-arrivals[i] for i in ids),sum(g['saved_meters'] for g in gg))
            elif objective=='saving':s=(sum(g['expected_saving_krw'] for g in gg),)
            else:s=(len(ids),sum(g['expected_saving_krw'] for g in gg))
            return s,tuple(sorted(ids))
        feasible=[score([i for i in range(8) if bits>>i&1]) for bits in range(1<<8)]
        feasible=[v for v in feasible if v is not None]
        target=max(v[0] for v in feasible);ids=min(v[1] for v in feasible if v[0]==target)
        keys,info=o.solve_exact(groups,objective,last,arrivals,100,order={i:8-i for i in range(1,8)})
        picked=[g for g in groups if g['key'] in keys]
        assert info['score']==target
        assert tuple(sorted(i for g in picked for i in g['ids']))==ids


def calibration_rows():
    return [dict(pair_id=str(i),split='train',osrm_seconds=str(10+i*20),osrm_km=str(.5+i),kakao_seconds=str(20+(10+i*20)*1.3)) for i in range(1,101)]


def test_cv_only_accepts_training_od_and_is_reproducible():
    rows=calibration_rows()
    assert c.select(rows)==c.select(copy.deepcopy(rows))
    changed=copy.deepcopy(rows);changed[0]['split']='test'
    with pytest.raises(ValueError,match='training'):c.select(changed)


def test_affine_and_band_prediction_and_zero_diagonal():
    rows=calibration_rows();model=c.train(rows,'affine')
    assert model['a']==pytest.approx(20)
    assert model['b']==pytest.approx(1.3)
    assert c.predict(model,0,0)==0
    assert c.predict(model,100,1)==pytest.approx(150)
    band=c.train(rows,'distance_bands')
    assert c.predict(band,100,1)>100


def test_production_selected_groups_reenter_real_verifier():
    daily=o.CACHE/'daily.json'
    if not daily.exists():pytest.skip('C8 public routing cache not available; run optimality_gap.py first')
    rows=json.loads(daily.read_text(encoding='utf-8'))
    replay=o.verify_selection(rows)
    expected=sum(len(r['selected']) for r in rows)
    assert replay['status']=='PASS'
    assert replay['groups']==expected
    assert replay['conservation_errors']==0
    assert set(replay['by_policy'])==set(o.POLICIES)


def test_c8_prod_matches_existing_c7_day_results():
    daily=o.CACHE/'daily.json';prior=o.HERE/'.cache'/'c7'/'days.csv'
    if not daily.exists() or not prior.exists():pytest.skip('C7/C8 public experiment caches unavailable')
    previous={r['case_id']:r for r in o.d.read_csv(prior)}
    for row in json.loads(daily.read_text(encoding='utf-8')):
        if row['policy']=='PROD':
            assert row['matched_riders']==int(previous[row['case_id']]['matched'])


def test_no_held_out_reselection_or_matrix_change_on_rejection():
    report=o.HERE/'reports'/'c8-calibration-selection.json'
    if not report.exists():pytest.skip('calibration artifact unavailable')
    result=json.loads(report.read_text(encoding='utf-8'))
    assert result['selected']['form']==min(result['cv'],key=lambda f:result['cv'][f]['mape'])
    if result['status']=='KEEP_EXISTING':
        manifest=json.loads((o.HERE/'.cache'/'osrm'/'MANIFEST.json').read_text(encoding='utf-8'))
        assert manifest['calibration']['duration_ratio']==result['baseline']['ratio']
