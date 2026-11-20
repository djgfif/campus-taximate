import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import settings_sweep as s

REPORT = s.REPORTS / 'c13-settings-sweep.json'


class SweepUnitTests(unittest.TestCase):
    def test_grid_contains_the_current_settings(self):
        self.assertEqual(len(s.POLICIES), 27)
        for w in s.WIDTHS:
            self.assertIn(f'CFG-D15-S10-W{w}', s.POLICIES)
        self.assertEqual(s.parse('CFG-D20-S5-W60'), dict(delay=20, saving=5, width=60))

    def test_frontier_drops_only_dominated_settings(self):
        pts = {'a': dict(requester_saving=.2, mean_delay=3, mean_shift=10), 'b': dict(requester_saving=.25, mean_delay=3, mean_shift=10),
               'c': dict(requester_saving=.3, mean_delay=5, mean_shift=10)}
        self.assertEqual(sorted(s.frontier(pts)), ['b', 'c'])


@unittest.skipUnless(REPORT.exists(), 'C13 settings sweep not generated')
class SweepReportTests(unittest.TestCase):
    def test_current_config_reproduces_layer_two(self):
        for c in json.loads(REPORT.read_text(encoding='utf-8'))['campuses']:
            for n, v in c['layer2'].items():
                self.assertAlmostEqual(c['results']['CFG-D15-S10-W20'][n]['match_rate'], v['match_rate'], places=9, msg=c['campus'])


if __name__ == '__main__':
    unittest.main()
