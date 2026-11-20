import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import departure_slots as d

REPORT = d.REPORTS / 'c13-departure-slots.json'


class SlotUnitTests(unittest.TestCase):
    def test_slot_is_first_departure_at_or_after_earliest(self):
        self.assertEqual(d.slot_start(0, 20), 2400)        # 가장 이른 출발 25분 → 40분 편
        self.assertEqual(d.slot_start(900, 20), 2400)      # 가장 이른 출발 40분 → 그 편 그대로
        self.assertEqual(d.slot_start(301, 30), 3600)
        for a in range(0, 7200, 37):
            t = d.slot_start(a, 20)
            self.assertGreaterEqual(t, a + d.LEAD); self.assertLess(t, a + d.LEAD + 1200); self.assertEqual(t % 1200, 0)


@unittest.skipUnless(REPORT.exists(), 'C13 slot report not generated')
class SlotReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))['campuses']

    def test_current_policy_reproduces_layer_two(self):
        for c in self.r:
            for n, v in c['layer2'].items():
                self.assertAlmostEqual(c['results']['CURRENT'][n]['match_rate'], v['match_rate'], places=9, msg=c['campus'])

    def test_current_never_leaves_its_window_and_fixed_slots_do(self):
        for c in self.r:
            self.assertEqual(c['results']['CURRENT']['35']['beyond_window'], 0)
            self.assertEqual(c['results']['FLEX30']['35']['beyond_window'], 0)
            self.assertGreater(c['results']['SLOT30']['35']['beyond_window'], 0)


if __name__ == '__main__':
    unittest.main()
