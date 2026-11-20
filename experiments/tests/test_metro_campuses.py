import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import metro_campuses as m

REPORT = m.REPORTS / 'c13-metro-campuses.json'


class MetroUnitTests(unittest.TestCase):
    def test_tariff_fit_reproduces_linear_prices(self):
        pts = [dict(solo_km=k, seconds=s, fare=3000 + 900 * k + 50 * s / 60) for k, s in ((2, 300), (5, 700), (9, 1300), (15, 1900))]
        a, b, c = m.fit_tariff(pts, 'seoul')
        self.assertAlmostEqual(a, 3000, delta=1); self.assertAlmostEqual(b, 900, delta=1); self.assertAlmostEqual(c, 50, delta=1)

    def test_representation_keeps_every_region_flow(self):
        import math
        from unittest import mock
        pts = []
        for r, (n, w) in enumerate([(30, .6), (5, .3), (3, .1)]):
            for k in range(n):
                pts.append(dict(id=len(pts) + 1, region_code=f'R{r}', trips=n - k, lat=37 + r * .2 + k * .001, lon=127, weight=w * (n - k) / sum(range(1, n + 1))))
        fake = lambda client, src, dst: ([dict(km=5 + 10 * (p['lat'] - 37) * 10, seconds=600) for p in dst], None)
        with mock.patch.object(m.om, 'table_rows', fake):
            sel, cov = m.represent(pts, dict(lat=37, lon=127), None, {'x': 1}, {'R0': 6, 'R1': 3, 'R2': 1}, max_grids=6)
        self.assertEqual(len(sel), 6)
        self.assertEqual({p['region_code'] for p in sel}, {'R0', 'R1', 'R2'})  # 작은 먼 시군구도 대표 격자를 갖는다
        share = {r: sum(p['probability'] for p in sel if p['region_code'] == r) for r in ('R0', 'R1', 'R2')}
        for r, w in (('R0', .6), ('R1', .3), ('R2', .1)):
            self.assertAlmostEqual(share[r], w)
        self.assertAlmostEqual(cov['represented_flow_share'], 1)

    def test_campus_id_is_stable(self):
        self.assertEqual(m.campus_id(dict(name='대진대학교', branch='본교')), m.campus_id(dict(name='대진대학교', branch='본교')))


@unittest.skipUnless(REPORT.exists(), 'C13 metro report not generated')
class MetroReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))['campuses']

    def test_every_campus_matches_more_with_more_demand(self):
        for c in self.r:
            rates = [c['results'][str(n)]['match_rate'] for n in (35, 70, 140)]
            self.assertEqual(rates, sorted(rates), c['campus'])

    def test_seoul_campuses_use_dong_origins(self):
        for c in self.r:
            if c['region'] == '서울':
                self.assertEqual(c['origin']['level'], '행정동', c['campus'])
                self.assertEqual(len(c['origin']['origin']), 8)

    def test_daejin_starts_at_the_campus_not_downtown(self):
        dj = next(c for c in self.r if c['campus'] == '대진대학교')
        self.assertAlmostEqual(dj['lon'], 127.1561, delta=0.002)
        self.assertAlmostEqual(dj['lat'], 37.8707, delta=0.002)


if __name__ == '__main__':
    unittest.main()
