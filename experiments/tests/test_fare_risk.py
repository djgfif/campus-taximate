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

    def test_stress_without_shock_matches_the_main_table(self):
        # 스트레스 계산(공통 충격 0, 같은 2,000경로)은 본 표의 지금 앱 행과 같은 기금을 내야 한다(두 구현이 맞는지).
        for n, s in self.r.get('stress', {}).items():
            main = self.r['levels'][n][f"now_{s['x']:.4f}"]
            self.assertAlmostEqual(s['paths_2000']['seed99'], main['seed99'], delta=max(50, 0.01 * main['seed99']))
            self.assertAlmostEqual(s['paths_2000']['probability_ever_negative'], main['probability_ever_negative'], delta=0.01)

    def test_common_shock_raises_the_fund(self):
        for n, s in self.r.get('stress', {}).items():
            for sp in fr.STRESS_SPREADS:
                funds = [s['cells'][f'{sp}_{sd}']['seed99'] for sd in fr.STRESS_SHOCKS]
                self.assertEqual(funds, sorted(funds), (n, sp))


if __name__ == '__main__':
    unittest.main()
