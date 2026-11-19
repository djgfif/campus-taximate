import csv
import io
import json
import random
import statistics
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import detailed as c7


class DesignTests(unittest.TestCase):
    def test_duplicate_points_do_not_double_grid_demand(self):
        points=[dict(id=1,grid='a'),dict(id=2,grid='a'),dict(id=3,grid='b')]
        c7.weight_destinations(points,{'a':100,'b':100})
        self.assertEqual([p['probability'] for p in points],[.25,.25,.5])
        with self.assertRaises(ValueError):
            c7.weight_destinations([dict(id=1,grid='missing')],{'a':100})

    def test_stand_csv_does_not_invent_coordinates(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'stands.csv'
            source.write_text('택시승강장명,택시승차장 주소\n대진대 입구,경기도 포천시 선단동\n다른 곳,경기도 포천시 이동면\n',encoding='cp949')
            result=c7.stands_summary(source)
            self.assertEqual(result['rows'],2)
            self.assertFalse(result['has_coordinates'])
            self.assertEqual(len(result['candidates']),1)

    def test_required_levels_without_factorial_explosion(self):
        scenarios = c7.scenarios()
        self.assertEqual(len(scenarios), 20)
        self.assertEqual(len({s.name for s in scenarios}), len(scenarios))
        grid = [s for s in scenarios if s.axis in ('기본', '요청수×지연')]
        self.assertEqual({(s.requests, s.delay) for s in grid},
                         {(n,d) for n in (50,100,200,400) for d in (10,15,20)})
        self.assertEqual({s.saving for s in scenarios}, {.05,.10,.15})
        self.assertEqual({s.instant for s in scenarios}, {0.,.30,.60})
        self.assertEqual({s.noshow for s in scenarios}, {0.,.05})
        self.assertEqual({s.empirical for s in scenarios}, {False,True})

    def test_seeded_prefix_and_independent_days(self):
        points=[dict(id=1,weight=1),dict(id=2,weight=3)]
        hours=[dict(hour=16,probability=.4),dict(hour=18,probability=.6)]
        a=c7.generate_day(points,hours,10,50)
        self.assertEqual(a,c7.generate_day(points,hours,10,100)[:50])
        self.assertNotEqual(a,c7.generate_day(points,hours,11,50))
        self.assertTrue(all(4*3600 <= r['arrival'] < 7*3600 for r in a))

    def test_generator_uses_weight_not_uniform_points(self):
        points=[dict(id=1,weight=1),dict(id=2,weight=9)]
        rows=c7.generate_day(points,[dict(hour=16,probability=1)],456,2000)
        rate=sum(r['point']['id']==2 for r in rows)/len(rows)
        self.assertGreater(rate,.85)
        self.assertLess(rate,.95)

    def test_zero_instant_noshow_unpaid_preserves_all_reservations(self):
        point=dict(id=1,weight=1,lat=37,lon=127,km=5,seconds=600,fare=9000)
        header=['CASE','x','x','timeline','FROM_HUB','0','37','127','1.3','24','KAKAO','0','1','0','100','4800','-','KRW']
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'cases.tsv'
            sc=c7.replace(c7.Scenario(),instant=0.,noshow=0.,unpaid=0.)
            meta=c7.write_cases(target,[sc],2,12,[point],[dict(hour=17,probability=1)],header,[0.])
            self.assertEqual(len(meta),2)
            rows=[l.split('\t') for l in target.read_text().splitlines() if l.startswith('R\t')]
            self.assertTrue(all(r[10:13]==['0','1','0'] for r in rows))
            self.assertTrue(all(int(r[9])-int(r[8])==1200 for r in rows))


class MeasurementTests(unittest.TestCase):
    def test_daily_uncertainty_does_not_use_rider_pseudoreplicates(self):
        m=c7.ci([0.,1.]*15)
        self.assertEqual(m['valid_days'],30)
        self.assertEqual(m['mean'],.5)
        rng=random.Random(c7.BOOTSTRAP_SEED)
        reference=[statistics.mean(rng.choices([0.,1.]*15,k=30)) for _ in range(10000)]
        self.assertEqual(c7.BOOTSTRAP_REPETITIONS,10000)
        self.assertAlmostEqual(m['ci95_low'],c7.quantile(reference,.025))
        self.assertAlmostEqual(m['ci95_high'],c7.quantile(reference,.975))
        self.assertIsNone(c7.ci([None,None])['mean'])
        self.assertEqual(c7.ci([1.])['ci95_low'],1.)

    def test_conditional_days_bootstrap_only_valid_days(self):
        m=c7.ci([None,0.,1.])
        self.assertEqual(m['valid_days'],2)
        self.assertEqual((m['ci95_low'],m['ci95_high']),(0.,1.))

    def test_probability_cis_are_bounded_without_clipping(self):
        for values in ([0.]*30,[1.]*30,[0.]*29+[.033333],[0.,1.]*15,[.2,.8]*15):
            m=c7.ci(values)
            for key in ('mean','ci95_low','ci95_high'):
                self.assertGreaterEqual(m[key],0.)
                self.assertLessEqual(m[key],1.)
            self.assertEqual(m,c7.ci(values))
        # Signed continuous changes remain signed, rather than being clipped as probabilities.
        self.assertLess(c7.ci([-3.,-2.,-1.])['ci95_high'],0.)
        self.assertNotEqual(c7.bootstrap_day_weights(3,1,10),c7.bootstrap_day_weights(3,2,10))

    def test_noshow_and_unpaid_denominators_are_distinct(self):
        day=dict(requests='5',matched='3',expired='1',unpaid='1',budget_warning_rounds='0',
                 max_round_ms='1',verification_unavailable='0',conservation_errors='0')
        default=dict(hour='17',instant='0',group_size='3',remaining_size='2',absent_count='1',
            wait_minutes='10',delay_minutes='2',expected_ratio_saving='.6',expected_equal_saving='.6',
            ratio_saving='.4',equal_saving='.3',ratio_harmed='0',equal_harmed='0',
            ratio_additional='1',equal_additional='1',ratio_burden_increase='.5',equal_burden_increase='.5',current_nonpayer='0')
        for method in ('ratio','equal'):
            for metric in ('saving','harmed','additional','burden_increase'):
                default[f'raw_{method}_{metric}']=default[f'{method}_{metric}']
            default.update({f'{method}_expected_burden_change':'.5',f'{method}_compensation_credit_krw':'0',
                            f'{method}_noshow_charge_krw':'0',f'{method}_expected_share_krw':'3000'})
        riders=[dict(default,id='1',status='boarded',original_nonpayer='1',current_nonpayer='1'),
                dict(default,id='2',status='boarded',original_nonpayer='1',current_nonpayer='0'),
                dict(default,id='3',status='noshow',original_nonpayer='0'),
                dict(default,id='4',status='expired',original_nonpayer=''),
                dict(default,id='5',status='unpaid',original_nonpayer='')]
        groups=[dict(size='3',remaining='2',conservation_errors='0',cancelled_all_absent='0',
                     ratio_credit_total_krw='0',ratio_charge_total_krw='0',equal_credit_total_krw='0',equal_charge_total_krw='0')]
        m=c7.daily_metrics(day,riders,groups)
        self.assertEqual(m['matching_rate'],3/5)
        self.assertEqual(m['boarded_rate'],2/5)
        self.assertEqual(m['ratio_additional_rate'],1.)
        self.assertAlmostEqual(m['mean_ratio_saving_all'],.16)
        self.assertEqual(m['noshow_affected_survivors'],2)
        self.assertEqual(m['mean_ratio_noshow_burden_increase'],.5)
        self.assertIsNone(m['hour_12_matching_rate'])
        self.assertEqual(m['groups_3_rate'],1.)
        with self.assertRaises(ValueError):
            c7.daily_metrics(day,riders+[riders[0]],groups)
        with self.assertRaises(ValueError):
            c7.daily_metrics(day,riders,[dict(groups[0],conservation_errors='1')])

    def test_no_matches_returns_null_conditional_metrics(self):
        day=dict(requests='1',matched='0',expired='1',unpaid='0',budget_warning_rounds='0',
                 max_round_ms='1',verification_unavailable='0',conservation_errors='0')
        m=c7.daily_metrics(day,[dict(id='1',hour='12',status='expired')],[])
        self.assertEqual(m['matching_rate'],0.)
        self.assertIsNone(m['mean_ratio_saving'])
        self.assertIsNone(m['ratio_additional_rate'])
        self.assertIsNone(m['mean_ratio_noshow_burden_increase'])


class ProductionIntegrationTests(unittest.TestCase):
    def test_real_verifier_thresholds_settlement_and_prepayment(self):
        command=c7.CACHE/'java-command.json'
        if not command.is_file():
            self.skipTest('Compile C7 with detailed.py to run actual Java integration checks')
        java=json.loads(command.read_text(encoding='utf-8'))
        r=subprocess.run(java+['--self-test'],cwd=c7.REPO,check=True,capture_output=True,text=True)
        self.assertIn('checks passed',r.stdout)
        self.assertIn('actual CompensationService.apply checks passed',r.stdout)

    def test_completed_report_design_and_frozen_matching(self):
        report_path=c7.REPORTS/'c7-detailed-sim.json'
        if not report_path.is_file():
            self.skipTest('Run full C7 to validate exported results')
        report=json.loads(report_path.read_text(encoding='utf-8'))
        self.assertEqual(report['status'],'COMPLETED')
        self.assertEqual(report['conditions'],20)
        self.assertEqual(report['new_kakao_api_calls'],0)
        self.assertEqual(report['verification']['conservation_errors'],0)
        self.assertEqual(report['confidence_interval']['repetitions'],10000)
        self.assertEqual(report['confidence_interval']['method'],'day-cluster bootstrap percentile')
        self.assertTrue(report['compensation']['original_payer_exempt'])
        by={s['parameters']['name']:s for s in report['scenarios']}
        self.assertTrue(all(s['days']>=30 for s in report['scenarios']))
        for name in ('no_error','noshow5','noshow5_no_error'):
            for key in ('matching_rate','mean_delay_minutes','mean_wait_minutes'):
                self.assertEqual(by['base']['metrics'][key],by[name]['metrics'][key])
        self.assertAlmostEqual(sum(h['probability'] for h in report['demand']['hourly_distribution']),1.)
        self.assertEqual(report['demand']['candidate_points'],118)
        for s in report['scenarios']:
            self.assertLessEqual(s['metrics']['mean_delay_minutes']['mean'],s['parameters']['delay'])
            for key,m in s['metrics'].items():
                if c7.probability_metric(key):
                    for stat in ('mean','ci95_low','ci95_high'):
                        if m[stat] is not None:
                            self.assertGreaterEqual(m[stat],0.,(s['parameters']['name'],key,stat))
                            self.assertLessEqual(m[stat],1.,(s['parameters']['name'],key,stat))
        for name in ('noshow5','noshow5_no_error'):
            m=by[name]['metrics']
            for method in ('ratio','equal'):
                self.assertLessEqual(m[f'{method}_harmed_rate']['mean'],m[f'raw_{method}_harmed_rate']['mean'])
                self.assertLessEqual(m[f'{method}_additional_rate']['mean'],m[f'raw_{method}_additional_rate']['mean'])
                self.assertGreater(m[f'mean_{method}_noshow_charge_krw']['mean'],0.)


if __name__ == '__main__':
    unittest.main()
