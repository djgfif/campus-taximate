"""C14: 운영은 같은 성별끼리만 묶으므로 모의실험도 요청마다 성별을 넣는다."""
import json
from dataclasses import replace
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import detailed as c7
import campus_survey as c13

HERE = Path(__file__).resolve().parents[1]


class SexColumnTests(unittest.TestCase):
    def test_sex_draw_is_deterministic_and_follows_the_share(self):
        draws = [c7.sex(12345, i, .56) for i in range(1, 4001)]
        self.assertEqual(draws, [c7.sex(12345, i, .56) for i in range(1, 4001)])
        self.assertAlmostEqual(draws.count('M') / len(draws), .56, delta=.03)

    def test_old_experiments_keep_their_rows_and_new_ones_add_only_a_sex_column(self):
        points = [dict(id=1, lat=37.9, lon=127.2, km=5., seconds=600, fare=8000, weight=1.)]
        hours = [dict(hour=h, probability=1. if h == 18 else 0.) for h in range(24)]
        header = ['CASE', 'X', 'x', 'timeline', 'FROM_HUB', '0', '37.87', '127.15', '1.3', '24', 'KAKAO', '0', '0', '0', '100', '4800', 'CACHE_MATRIX', 'KRW']
        out = HERE / '.cache' / 'test-sex'; out.mkdir(parents=True, exist_ok=True)
        old, new = out / 'old.tsv', out / 'new.tsv'
        base = replace(c7.Scenario(), name='n20', requests=20)
        c7.write_cases(old, [base], 2, 7, points, hours, header, [0.])
        c7.write_cases(new, [replace(base, male_share=.5)], 2, 7, points, hours, header, [0.])
        o = [l.split('\t') for l in old.read_text().splitlines() if l.startswith('R')]
        n = [l.split('\t') for l in new.read_text().splitlines() if l.startswith('R')]
        self.assertTrue(all(len(r) == 14 for r in o))
        self.assertEqual([r[:14] for r in n], o)  # 성별 말고는 요청이 똑같다
        self.assertEqual({r[14] for r in n}, {'M', 'F'})

    def test_enrolled_male_share_of_daejin(self):
        self.assertAlmostEqual(c13.male_shares()[('대진대학교', '본교')], .562, delta=.002)


class SexReportTests(unittest.TestCase):
    def test_metro_campuses_carry_their_sex_ratio(self):
        p = HERE / 'reports' / 'c13-metro-campuses.json'
        if not p.exists():
            self.skipTest('no metro report')
        for c in json.loads(p.read_text(encoding='utf-8'))['campuses']:
            self.assertIsNotNone(c.get('male_share'), c['campus'])

    def test_splitting_by_sex_lowers_matching(self):
        p = HERE / 'reports' / 'c14-sex-sensitivity.json'
        if not p.exists():
            self.skipTest('no sensitivity report')
        r = json.loads(p.read_text(encoding='utf-8'))
        enrolled = f"{round(r['enrolled_male_share'], 3):.3f}"
        for n in ('20', '35', '70'):
            self.assertLess(r['results'][enrolled]['CURRENT'][n]['match_rate'], r['results']['1.000']['CURRENT'][n]['match_rate'])


if __name__ == '__main__':
    unittest.main()
