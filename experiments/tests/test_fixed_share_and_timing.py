import json
from pathlib import Path
import subprocess
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fixed_share_and_timing as c11

REPORT = c11.REPORTS / 'c11-fixed-share-and-timing.json'


class C11UnitTests(unittest.TestCase):
    def test_reserve_recursion_is_running_balance(self):
        traj = c11.reserve_recursion([500, -800, 200], initial=100)
        self.assertEqual(traj.tolist(), [100, 600, -200, 0])

    def test_seed_fund_is_empirical_quantile_of_required_cash(self):
        required = list(range(100))
        self.assertEqual(c11.seed_fund(required, .99), 98)
        self.assertEqual(c11.seed_fund(required, .95), 94)
        # A path that never dips needs no seed money.
        self.assertEqual(c11.seed_fund([-5, 0, -1], .99), 0)

    def test_path_statistics_seed_makes_paths_non_negative(self):
        totals = [100, -300, 50]
        minima = [0, -400, -10]
        paths = np.array([[0, 1, 2], [1, 1, 1], [2, 2, 2]])
        stats, required = c11.path_statistics(totals, minima, paths)
        self.assertEqual(stats['seed99'], int(max(required)))
        self.assertEqual(stats['probability_never_negative_seed99'], 1.0)
        self.assertGreater(stats['probability_ever_negative'], 0)

    def test_java_self_tests_cover_fixed_ledger_and_bias_baseline(self):
        cmd_path = c11.CACHE / 'java-command.json'
        if not cmd_path.exists():
            self.skipTest('C11 local cache not present: ' + str(cmd_path))
        java = json.loads(cmd_path.read_text(encoding='utf-8'))
        for cls, marker in (('C11Finance', 'operator conservation passed'), ('C11Simulation', 'minimum-saving checks passed')):
            out = subprocess.run(java[:-1] + ['com.campustaximate.server.service.' + cls, '--self-test'],
                                 cwd=c11.ROOT, capture_output=True, text=True, check=True).stdout
            self.assertIn(marker, out)


@unittest.skipUnless(REPORT.exists(), 'C11 report not generated')
class C11ReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads(REPORT.read_text(encoding='utf-8'))

    def test_fixed_share_has_no_extra_charge_refund_or_leak(self):
        for s in self.report['reserve']:
            m = s['metrics']
            self.assertEqual(m['extra']['mean'], 0, s['key'])
            self.assertEqual(m['refund']['mean'], 0, s['key'])
            self.assertEqual(m['conservation_errors']['mean'], 0, s['key'])
            self.assertEqual(s['surplus_policy']['conservation_errors'], 0, s['key'])

    def test_precheck_groups_never_break_min_saving_against_biased_solo(self):
        for s in self.report['reserve']:
            if s['source'] == 'PRECHECK':
                self.assertEqual(s['observed_violating_groups'], 0, s['key'])

    def test_bias_alone_does_not_cost_matches(self):
        # m=0 only re-centres both the shared quote and the solo baseline, so the
        # saving rule is unchanged up to integer rounding.
        timing = {(t['requests'], t['policy']): t for t in self.report['timing']}
        for n in c11.LEVELS:
            prod = timing[(n, 'PROD')]['metrics']['match_rate']['mean']
            zero = timing[(n, 'MARKUP0.0')]['metrics']['match_rate']['mean']
            self.assertLess(abs(prod - zero), .01, n)

    def test_forecast_trained_on_disjoint_days(self):
        forecast_path = c11.CACHE / 'forecast.json'
        if not forecast_path.exists():
            self.skipTest('C11 local cache not present: ' + str(forecast_path))
        model = json.loads(forecast_path.read_text(encoding='utf-8'))
        self.assertFalse(set(model['train_seeds']) & set(model['eval_seeds']))

    def test_every_selected_group_replays_through_production_verifier(self):
        for t in self.report['timing']:
            self.assertGreater(t['production_feasibility_parity_groups'], 0)


if __name__ == '__main__':
    unittest.main()
