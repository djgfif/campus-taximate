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
