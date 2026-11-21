import json
from pathlib import Path
import statistics
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import to_campus as t

REPORT = t.REPORTS / 'c15-to-campus.json'


class BoardingPointTests(unittest.TestCase):
    pts = [dict(id=1, lat=37.870, lon=127.150, probability=.5, region_code='A'),
           dict(id=2, lat=37.871, lon=127.151, probability=.3, region_code='A'),
           dict(id=3, lat=37.700, lon=127.050, probability=.2, region_code='B')]

    def test_own_grid_keeps_every_grid(self):
        self.assertEqual({k: v['id'] for k, v in t.boarding_points(self.pts, 500).items()}, {1: 1, 2: 2, 3: 3})

    def test_walking_cell_and_region_merge_neighbours_into_the_busiest_grid(self):
        walk = {k: v['id'] for k, v in t.boarding_points(self.pts, 1000).items()}
        self.assertEqual(walk[2], 1)   # 150m 떨어진 이웃 격자는 사람이 더 많은 격자로 모인다
        self.assertEqual(walk[3], 3)   # 20km 떨어진 곳은 따로
        region = {k: v['id'] for k, v in t.boarding_points(self.pts, 'region').items()}
        self.assertEqual(region, {1: 1, 2: 1, 3: 3})


@unittest.skipUnless(REPORT.exists(), 'C15 to-campus report not generated')
class ToCampusReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = [c for c in json.loads(REPORT.read_text(encoding='utf-8'))['campuses'] if 'results' in c]

    def test_bigger_boarding_points_never_match_less(self):
        meds = [statistics.median(c['results'][s]['35']['match_rate'] for c in self.r) for s in ('grid', 'walk', 'region')]
        self.assertEqual(meds, sorted(meds))

    def test_matched_riders_save_because_they_share_one_route_to_campus(self):
        for c in self.r:
            x = c['results']['region']['35']
            if x['match_rate'] > 0:
                self.assertGreater(x['mean_saving_matched'], .3, c['campus'])


if __name__ == '__main__':
    unittest.main()
