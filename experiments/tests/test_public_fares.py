from datetime import datetime, timedelta
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fares
import run


class PublicMetricTests(unittest.TestCase):
    def test_denominators_boundaries_and_percentiles(self):
        m = fares.public_metrics([100,100,100,100],[85,115,116,100])
        self.assertEqual(m['n'],4)
        self.assertEqual(m['within_15pct_rate'],.75)
        self.assertEqual(m['additional_payment_rate'],.25)
        self.assertEqual(m['refund_rate'],.25)
        self.assertEqual(m['mae'],11.5)
        self.assertAlmostEqual(m['mape'],(15/85+15/115+16/116)/4)
        self.assertAlmostEqual(m['signed_relative_percentiles']['50'],.075)

    def test_empty_is_unmeasured_and_invalid_is_rejected(self):
        self.assertIsNone(fares.public_metrics([],[])['mae'])
        for p,a in (([0],[10]),([10],[-1]),([10],[float('nan')]),([1],[])):
            with self.assertRaises(ValueError): fares.public_metrics(p,a)

    def test_exclusions_order_and_edges(self):
        start=datetime(2025,1,1); end=start+timedelta(minutes=10)
        self.assertIsNone(fares.trip_exclusion(4800,2,10,start,end))
        for values,reason in [({'fare':0},'invalid_fare_or_missing_numeric'),
          ({'km':0},'distance_outside_0.1_160km'),({'minutes':200},'duration_outside_1_180min'),
          ({'km':30,'minutes':1},'speed_outside_1_130kmh'),({'end':start},'invalid_time_order'),
          ({'od_valid':False},'missing_or_unknown_od'),({'standard':False},'non_standard_rate')]:
            with self.subTest(reason=reason):
                kwargs=dict(fare=4800,km=2,minutes=10,start=start,end=end);kwargs.update(values)
                self.assertEqual(fares.trip_exclusion(**kwargs),reason)

    def test_korean_tariff_regions_and_night_rates(self):
        self.assertEqual(fares.korea_tariff(1.6,6.4),4800)
        self.assertEqual(fares.korea_tariff(1.731,6),4900)
        self.assertEqual(fares.korea_tariff(1.731,6,23),6860)
        self.assertEqual(fares.korea_tariff(2.,8,kind='pocheon'),4800)
        self.assertEqual(fares.korea_tariff(2.083,8,kind='pocheon'),4900)

    def test_special_tariff_floor_and_distance_brackets(self):
        for km,expected in [(0,1500),(5,1500),(5.35,1500),(6,1700),(10,2900),(11,2900),(12,3000)]:
            self.assertEqual(fares.special_taxi_tariff(km),expected)


class PublicSqlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try: import duckdb
        except ImportError:
            cls.db=None
            if not (run.CACHE/'venv'/'python.exe').is_file():
                raise unittest.SkipTest('Optional public mode: install requirements-public.txt')
            return
        cls.db=duckdb.connect();fares.prepare_public_sql(cls.db)

    def external_public_python(self):
        if self.db is not None: return False
        # MSYS Python cannot import a Windows .pyd. Execute this same substantive
        # SQL check in the repository's isolated CPython instead of skipping it.
        script=("import sys,unittest;sys.path.insert(0,sys.argv[1]);"
                "r=unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromName(sys.argv[2]));"
                "sys.exit(not r.wasSuccessful())")
        completed=subprocess.run([str(run.CACHE/'venv'/'python.exe'),'-c',script,
            str(Path(__file__).resolve().parent),self.id()],capture_output=True,text=True)
        self.assertEqual(completed.returncode,0,completed.stdout+completed.stderr)
        return True

    def test_sql_metrics_match_independent_reference(self):
        if self.external_public_python(): return
        query='SELECT * FROM (VALUES (100.,85.),(100.,115.),(100.,116.),(100.,100.)) t(expected,actual)'
        actual=fares.sql_metrics(self.db,query); expected=fares.public_metrics([100]*4,[85,115,116,100])
        for k in expected:
            if isinstance(expected[k],dict):
                for p,v in expected[k].items():self.assertAlmostEqual(actual[k][p],v)
            else:self.assertAlmostEqual(actual[k],expected[k])

    def test_cleaning_counts_cover_every_row(self):
        if self.external_public_python(): return
        source="""SELECT * FROM (VALUES
          (10.,2.,10.,TIMESTAMP '2025-01-01',TIMESTAMP '2025-01-01 00:10:00','a','b',true,false),
          (0.,2.,10.,TIMESTAMP '2025-01-01',TIMESTAMP '2025-01-01 00:10:00','a','b',true,false),
          (10.,2.,10.,TIMESTAMP '2024-01-01',TIMESTAMP '2024-01-01 00:10:00','a','b',true,false))
          t(fare,km,mins,pickup,dropoff,pu,dest,standard,canceled)"""
        m=fares.clean_public(self.db,source,'test_clean','2025-01-01','2026-01-01')
        self.assertEqual((m['rows'],m['accepted'],m['excluded']),(3,1,2))

    def test_sql_tariff_matches_python(self):
        if self.external_public_python(): return
        for km,mins,h in [(1.6,6.4,12),(1.731,6,23),(10,50,22),(3,15,2)]:
            v=self.db.execute('SELECT seoulfare(?,?,?)',[km,mins,h]).fetchone()[0]
            self.assertEqual(v,fares.korea_tariff(km,mins,h))

    def test_training_fallback_does_not_use_future_fares(self):
        if self.external_public_python(): return
        self.db.execute("""CREATE OR REPLACE TABLE tiny_train AS SELECT 'a' AS pu,'b' AS dest,
          (i%2)::INTEGER AS band,2. AS km,10. AS mins,1500. AS fare FROM range(20) t(i)""")
        self.db.execute("""CREATE OR REPLACE TABLE tiny_test AS SELECT 'a' AS pu,'b' AS dest,
          7 AS band,4. AS km,15. AS mins,9999. AS fare""")
        with tempfile.TemporaryDirectory(dir=run.CACHE) as d:
            original=fares.PUBLIC_CACHE
            try:
                fares.PUBLIC_CACHE=Path(d)
                out=fares.od_evaluation(self.db,'tiny_train','tiny_test','pred_fare','fare','tiny')
            finally: fares.PUBLIC_CACHE=original
        self.assertEqual(out['prediction_coverage'],{'od_only':1})
        predicted=self.db.execute('SELECT pred_fare FROM tiny_eval').fetchone()[0]
        self.assertEqual(predicted,1500)


class ProductionBootstrapTests(unittest.TestCase):
    def test_fixed_groups_production_conservation_seed_and_prepay(self):
        cache=run.CACHE
        if not (cache/'runtime-classpath.txt').is_file():
            self.skipTest('Run C6 once to compile current production sources')
        java=[(cache/'runtime-java.txt').read_text(),'-cp',(cache/'runtime-classpath.txt').read_text(),
              'com.campustaximate.server.service.ExperimentRunner']
        with tempfile.TemporaryDirectory(dir=cache) as directory:
            folder=Path(directory); groups=folder/'groups.tsv'; errors=folder/'errors.csv'
            groups.write_text('TEST\tfixed\t1\t101\t100,101,102\n',encoding='utf-8')
            errors.write_text('0\n.15\n.16\n-.8\n',encoding='utf-8')
            first=run.bootstrap_sensitivity(java,groups,errors,folder/'a.csv',123,1000)
            second=run.bootstrap_sensitivity(java,groups,errors,folder/'b.csv',123,1000)
            self.assertEqual(first,second)
            for methods in first.values():
                for m in methods.values():
                    self.assertEqual(m['conservation_errors'],0)
                    self.assertEqual(m['riders'],m['group_settlements']*3)
                    self.assertEqual(m['nonpayers'],m['group_settlements']*2)
                    self.assertEqual(m['group_settlements']+m['invalid_scaled_groups'],1000)
            self.assertEqual(first['epsilon_0']['ratio']['additional_payment_rate'],0)
            self.assertGreater(first['error_x2']['ratio']['nonpositive_multiplier_draws'],0)


if __name__=='__main__': unittest.main()
