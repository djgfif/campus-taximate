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


    def test_korean_tariff_regions_and_night_rates(self):
        self.assertEqual(fares.korea_tariff(1.6,6.4),4800)
        self.assertEqual(fares.korea_tariff(1.731,6),4900)
        self.assertEqual(fares.korea_tariff(1.731,6,23),6860)
        self.assertEqual(fares.korea_tariff(2.,8,kind='pocheon'),4800)
        self.assertEqual(fares.korea_tariff(2.083,8,kind='pocheon'),4900)



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


    def test_sql_tariff_matches_python(self):
        if self.external_public_python(): return
        for km,mins,h in [(1.6,6.4,12),(1.731,6,23),(10,50,22),(3,15,2)]:
            v=self.db.execute('SELECT seoulfare(?,?,?)',[km,mins,h]).fetchone()[0]
            self.assertEqual(v,fares.korea_tariff(km,mins,h))



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
