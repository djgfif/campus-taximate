import json
from collections import Counter
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import campus_survey as c13

REPORT = c13.REPORTS / 'c13-campus-survey.json'


class C13UnitTests(unittest.TestCase):
    def test_query_names_branch_campuses(self):
        self.assertEqual(c13.query(dict(name='고려대학교(세종)', branch='분교', region='세종')), '고려대학교 세종캠퍼스')
        self.assertEqual(c13.query(dict(name='한양대학교(ERICA)', branch='분교', region='경기')), '한양대학교 ERICA캠퍼스')
        self.assertEqual(c13.query(dict(name='단국대학교', branch='제2캠퍼스', region='충남')), '단국대학교 충남')
        self.assertEqual(c13.query(dict(name='대진대학교', branch='본교', region='경기')), '대진대학교')

    def test_student_excess_removes_regional_season(self):
        grids = {'g': Counter({m: 10 * c13.DAYS[m] for m in c13.DAYS})}
        for m in ('2025-03', '2025-04', '2025-05', '2025-09', '2025-10', '2025-11'):
            grids['g'][m] = 30 * c13.DAYS[m]
        center, _ = c13.c12.tims_grid(127.1561, 37.8707)
        grids = {center: grids['g']}
        t = c13.campus_taxi(grids, 127.1561, 37.8707, region={center: 'R'}, ratio={'R': 1.5})
        self.assertAlmostEqual(t['semester_per_day'], 30)
        self.assertAlmostEqual(t['vacation_per_day'], 10)
        self.assertAlmostEqual(t['student_excess_per_day'], 30 - 10 * 1.5)


@unittest.skipUnless(REPORT.exists(), 'C13 survey not generated')
class C13ReportTests(unittest.TestCase):
    def test_daejin_matches_c12_observation(self):
        r = json.loads(REPORT.read_text(encoding='utf-8'))
        c12 = json.loads((c13.REPORTS / 'c12-current-service.json').read_text(encoding='utf-8'))['observed_campus_demand']
        self.assertAlmostEqual(r['daejin']['semester_per_day'], c12['semester_per_day'], delta=0.5)
        self.assertGreater(r['campuses'], 200)


if __name__ == '__main__':
    unittest.main()
