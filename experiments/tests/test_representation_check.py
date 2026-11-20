import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import representation_check as rc

REPORT = rc.REPORTS / 'c13-representation-check.json'


@unittest.skipUnless(REPORT.exists(), 'C13 representation check not generated')
class RepresentationTests(unittest.TestCase):
    """2층의 줄인 목적지가 전체 격자 계산(C12)과 거의 같은 이동·매칭을 내는지(11/20 이전 방식은 +4~10%p였다)."""

    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))

    def test_reduced_destinations_keep_the_trip_length(self):
        self.assertAlmostEqual(self.r['reduced_km'], self.r['full_km'], delta=.5)

    def test_reduced_destinations_stay_close_to_the_full_grid(self):
        self.assertLess(abs(self.r['mean_diff']), .03)
        for row in self.r['rows']:
            self.assertLess(abs(row['diff']), .06, row['requests'])


if __name__ == '__main__':
    unittest.main()
