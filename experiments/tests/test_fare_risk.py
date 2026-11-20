import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fare_risk as fr

REPORT = fr.REPORTS / 'c14-fare-risk.json'
C12 = fr.REPORTS / 'c12-current-service.json'


@unittest.skipUnless(REPORT.exists() and C12.exists(), 'C14 fare risk not generated')
class FareRiskTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))
        cls.c12 = {str(l['requests']): l for l in json.loads(C12.read_text(encoding='utf-8'))['levels']}

    def test_assumed_bias_reproduces_c12_reserve(self):
        for n in ('35', '70'):
            mine = self.r['levels'][n][f'{fr.ASSUMED:.4f}']; theirs = self.c12[n]['money']['1.0']['reserve']
            self.assertAlmostEqual(mine['end_mean'], theirs['end_mean'], delta=1)
            self.assertEqual(mine['seed99'], theirs['seed99'])

    def test_more_underquoting_costs_the_operator_and_never_the_rider(self):
        for n, lv in self.r['levels'].items():
            ends = [lv[f'{x:.4f}']['end_mean'] for x in fr.SCENARIOS]
            self.assertEqual(ends, sorted(ends, reverse=True), n)
            paid = [lv[f'{x:.4f}']['mean_paid_over_meter'] for x in fr.SCENARIOS]
            self.assertEqual(paid, sorted(paid, reverse=True), n)


if __name__ == '__main__':
    unittest.main()
