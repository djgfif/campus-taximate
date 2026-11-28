import importlib.util
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import current_service as c12

REPORT = c12.REPORTS / 'c12-current-service.json'
HAS_PYPROJ = importlib.util.find_spec('pyproj') is not None


class C12UnitTests(unittest.TestCase):
    def test_fare_config_uses_sim_bias_and_server_margin(self):
        # 실험 견적(요금표 공식)의 보정은 C5 값 그대로, 실효 여유분은 RouteConfig 규칙상 flow-version >= 3이면 0.0이다.
        bias, margin = c12.fare_config()
        self.assertAlmostEqual(bias, 0.0657)
        self.assertAlmostEqual(margin, 0.0)
        yml = (c12.ROOT / 'server/src/main/resources/application.yml').read_text(encoding='utf-8')
        self.assertIn('quote-bias: ${FARE_QUOTE_BIAS:0}', yml)
        self.assertIn('flow-version: ${PAYMENT_FLOW_VERSION:3}', yml)

    @unittest.skipUnless(HAS_PYPROJ, 'pyproj is not installed in this Python environment')
    def test_campus_grid_and_neighbourhood(self):
        center, near = c12.tims_grid(*c12.CAMPUS)
        self.assertEqual(center, '다사139171')
        self.assertEqual(len(near), 9)
        self.assertIn(center, near)

    def test_routes_start_at_the_campus_gate(self):
        import osrm_matrix as om
        self.assertAlmostEqual(om.CAMPUS_HUB['lon'], c12.CAMPUS[0], delta=0.001)
        self.assertAlmostEqual(om.CAMPUS_HUB['lat'], c12.CAMPUS[1], delta=0.001)
        self.assertNotAlmostEqual(om.HUB['lon'], c12.CAMPUS[0], delta=0.01)  # legacy C7 hub stays for frozen experiments
        dest = c12.ROUTING / 'destinations.json'
        if dest.exists():
            hub = json.loads(dest.read_text(encoding='utf-8'))['hub']
            self.assertAlmostEqual(hub['lon'], c12.CAMPUS[0], delta=0.001)

    @unittest.skipUnless(HAS_PYPROJ and (c12.HERE / '.cache/kr-public/tims_2025.zip').exists(), 'pyproj or local TIMS cache not available')
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
                self.assertEqual(m['normal_payments_per_rider'], 1, (lv['requests'], sp))
                self.assertEqual(m['operator_reserve'], 0, (lv['requests'], sp))
                self.assertGreater(m['postpaid_saving_krw']['mean'], 0.0, (lv['requests'], sp))
                if sp != '2.0':
                    self.assertGreater(m['postpaid_saving']['mean'], 0.0, (lv['requests'], sp))

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
