import json
from pathlib import Path
import statistics
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import window_curve as w

REPORT = w.REPORTS / 'c13-window-curve.json'


class WindowUnitTests(unittest.TestCase):
    def test_twenty_minutes_is_the_current_policy(self):
        self.assertEqual(dict(zip(w.WIDTHS, w.POLICIES))[20], 'CURRENT')


@unittest.skipUnless(REPORT.exists(), 'C13 window curve not generated')
class WindowReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))['campuses']

    def test_current_reproduces_layer_two(self):
        for c in self.r:
            for n, v in c['layer2'].items():
                self.assertAlmostEqual(c['results']['CURRENT'][n]['match_rate'], v['match_rate'], places=9, msg=c['campus'])

    def test_wider_windows_match_more_and_depart_later(self):
        for key in ('match_rate', 'mean_shift'):
            meds = [statistics.median(c['results'][p]['35'][key] for c in self.r) for p in w.POLICIES]
            self.assertEqual(meds, sorted(meds), key)


if __name__ == '__main__':
    unittest.main()
