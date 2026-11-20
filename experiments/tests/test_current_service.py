import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import current_service as c12

REPORT = c12.REPORTS / 'c12-current-service.json'


class C12UnitTests(unittest.TestCase):
    def test_fare_config_matches_server_defaults(self):
        bias, margin = c12.fare_config()
        self.assertAlmostEqual(bias, 0.0657)
        self.assertAlmostEqual(margin, 0.01)

    def test_campus_grid_and_neighbourhood(self):
        center, near = c12.tims_grid(*c12.CAMPUS)
        self.assertEqual(center, '다사139171')
        self.assertEqual(len(near), 9)
        self.assertIn(center, near)

    def test_observed_demand_follows_the_academic_calendar(self):
        o = c12.observed_campus_demand()
        self.assertGreater(o['semester_per_day'], 2 * o['vacation_per_day'])
        self.assertEqual(len(o['per_day_by_month']), 12)


@unittest.skipUnless(REPORT.exists(), 'C12 report not generated')
class C12ReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))

    def test_fixed_shares_never_charge_extra_or_refund_and_conserve_money(self):
        for lv in self.r['levels']:
            for sp, m in lv['money'].items():
                self.assertEqual(m['extra_total'], 0, (lv['requests'], sp))
                self.assertEqual(m['refund_total'], 0, (lv['requests'], sp))
                self.assertEqual(m['conservation_total'], 0, (lv['requests'], sp))

    def test_more_demand_matches_more_people(self):
        rates = [lv['matching']['match_rate']['mean'] for lv in self.r['levels']]
        self.assertEqual(rates, sorted(rates))

    def test_every_confirmed_group_replays_through_production_verifier(self):
        for lv in self.r['levels']:
            self.assertGreater(lv['production_parity_groups'], 0)

    def test_uses_server_fare_configuration(self):
        bias, margin = c12.fare_config()
        self.assertAlmostEqual(self.r['bias'], bias)
        self.assertAlmostEqual(self.r['margin'], margin)


if __name__ == '__main__':
    unittest.main()
