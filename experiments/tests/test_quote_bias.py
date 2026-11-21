import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quote_bias as qb
from fares import korea_tariff

REPORT = qb.REPORTS / 'c16-quote-bias.json'


class ChainTests(unittest.TestCase):
    def test_same_ratio_on_both_sides_means_kakao_equals_actual(self):
        kakao_vs_actual, app_vs_actual = qb.chain(0.10, 0.10, bias=0.0657)
        self.assertAlmostEqual(kakao_vs_actual, 0.0)
        self.assertAlmostEqual(app_vs_actual, 0.0657)

    def test_ratios_multiply_not_subtract(self):
        # 카카오가 공식보다 15%, 실제가 공식보다 5% 높으면 카카오/실제 = 1.15/1.05 - 1
        kakao_vs_actual, app_vs_actual = qb.chain(0.15, 0.05, bias=0.0657)
        self.assertAlmostEqual(kakao_vs_actual, 1.15 / 1.05 - 1)
        self.assertAlmostEqual(app_vs_actual, 1.0657 * 1.15 / 1.05 - 1)

    def test_band_edges(self):
        self.assertEqual([qb.band(x) for x in (0, 4.99, 5, 14.99, 15, 29.99, 30, 80)],
                         ['0-5', '0-5', '5-15', '5-15', '15-30', '15-30', '30+', '30+'])

    def test_formula_matches_c5_sql_macro_constants(self):
        # 기본 2km 4,800원, 이후 83m 또는 저속 20초마다 100원(포천 낮). C5 SQL krfare와 같은 값이어야 한다.
        self.assertEqual(korea_tariff(2.0, 4, 14, 'pocheon'), 4800)
        # 10km 50분: 거리 8km/83m = 97틱, 저속 50분 - 10km/15km/h(40분) = 600초/20초 = 30틱
        self.assertEqual(korea_tariff(10.0, 50, 14, 'pocheon'), 4800 + 100 * (97 + 30))

    def test_python_formula_equals_tims_sql_formula(self):
        import duckdb
        db = duckdb.connect()
        db.execute("""CREATE MACRO krfare(km,mins) AS
          round(4800+100*(ceil(greatest(0,km-2.)*1000/83.-1e-10)+
            CASE WHEN km>2. THEN ceil(greatest(0,mins*60-km/15*3600)/20.-1e-10) ELSE 0 END))""")
        for km, mins in ((1.5, 3), (2.0, 10), (7.3, 18.2), (25.9857, 2793 / 60), (48.0, 55)):
            self.assertEqual(db.execute('SELECT krfare(?, ?)', [km, mins]).fetchone()[0], korea_tariff(km, mins, 14, 'pocheon'))


@unittest.skipUnless(REPORT.exists(), 'C16 report not generated')
class ReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = json.loads(REPORT.read_text(encoding='utf-8'))

    def test_now_bias_matches_service_config(self):
        yml = (Path(__file__).resolve().parents[2] / 'server' / 'src' / 'main' / 'resources' / 'application.yml').read_text(encoding='utf-8')
        self.assertEqual(self.r['bias_now'], 0.0)
        self.assertIn('quote-bias: ${FARE_QUOTE_BIAS:0}', yml)
        self.assertAlmostEqual(self.r['bias_before'], 0.0657)

    def test_now_is_kakao_as_is_and_lower_than_before(self):
        w = self.r['service_weighted']
        self.assertLess(w['kakao_vs_actual'][1], w['app_vs_actual'][0])
        self.assertLessEqual(w['kakao_vs_actual_ci95'][0], w['kakao_vs_actual_ci95'][1])

    def test_service_weights_sum_to_one(self):
        self.assertAlmostEqual(sum(b['service_share'] for b in self.r['per_band'].values()), 1.0)

    def test_weighted_range_inside_band_ranges(self):
        w = self.r['service_weighted']
        lows = [self.r['per_band'][b]['app_vs_actual'][0] for b in w['bands']]
        highs = [self.r['per_band'][b]['app_vs_actual'][1] for b in w['bands']]
        self.assertLessEqual(min(lows), w['app_vs_actual'][0])
        self.assertGreaterEqual(max(highs), w['app_vs_actual'][1])
        self.assertLessEqual(w['app_vs_actual_ci95'][0], w['app_vs_actual_ci95'][1])

    def test_all_quotes_are_split_by_destination(self):
        self.assertEqual(self.r['kakao_quotes'],
                         self.r['kakao_split']['destination_in_pocheon']['n'] + self.r['kakao_split']['destination_outside_pocheon']['n'])
        self.assertEqual(self.r['kakao_quotes'], self.r['kakao_hub_quotes'] + (self.r['campus']['n'] if self.r['campus'] else 0))

    def test_campus_supplement_fills_5_to_15(self):
        c = self.r['campus']
        if not c:
            self.skipTest('C16 보강 표본 없음')
        self.assertEqual(c['n'], 3 * qb.PER_SUB_BAND)
        self.assertEqual({k: v['n'] for k, v in c['by_sub_band'].items()}, {'5-8': 24, '8-11': 24, '11-15': 24})
        self.assertGreaterEqual(self.r['per_band']['5-15']['kakao_over_formula']['n'], 60)
        # 미리 고정한 표본 파일이 그대로인지(결과를 본 뒤 다시 뽑지 않았는지)
        self.assertEqual(c['sample_sha256'], qb._sha(qb.CAMPUS_SAMPLE))

    def test_border_assumption_fits_best_and_none_supports_old_bias(self):
        sc = self.r['scenarios']
        gap = {k: abs(v['inside']['median'] - v['outside']['median']) for k, v in sc.items()}
        self.assertEqual(min(gap, key=gap.get), 'border')
        for v in sc.values():
            # 어느 가정에서도 예전 앱 견적(카카오 x 1.0657)이 실제 평균에 맞는다고 볼 수 없다(카카오/실제 하한이 -6.57%보다 높다).
            self.assertGreater(v['service_weighted']['kakao_vs_actual'][0], -0.0657 / 1.0657)

    def test_original_200_kept_for_comparison(self):
        o = self.r['original_200']['service_weighted']
        self.assertLess(o['kakao_vs_actual'][0], o['kakao_vs_actual'][1])

    def test_border_surcharge_closes_the_outside_gap(self):
        # 포천 밖 운행은 경로 대부분이 경계 밖이고, 할증을 넣으면 포천 안 운행과 비슷한 수준이 돼야 한다.
        s = self.r['kakao_split']; inside, outside = s['destination_in_pocheon'], s['destination_outside_pocheon']
        self.assertGreater(outside['outside_fraction_median'], 0.5)
        self.assertLess(inside['outside_fraction_median'], 0.05)
        self.assertGreater(outside['median'] - inside['median'], 0.05)
        self.assertLess(abs(outside['border']['median'] - inside['border']['median']), 0.05)

    def test_no_border_sensitivity_is_kept(self):
        self.assertIn('service_weighted', self.r['no_border'])
        self.assertGreater(self.r['no_border']['service_weighted']['kakao_vs_actual'][0], self.r['service_weighted']['kakao_vs_actual'][0])


class BorderSurchargeTests(unittest.TestCase):
    def test_only_fare_above_base_and_outside_share_is_surcharged(self):
        self.assertEqual(qb.with_border_surcharge(4800, 1.0), 4800)
        self.assertEqual(qb.with_border_surcharge(20000, 0.0), 20000)
        self.assertAlmostEqual(qb.with_border_surcharge(20000, 1.0), 4800 + 15200 * 1.2)
        self.assertAlmostEqual(qb.with_border_surcharge(20000, 0.5), 20000 + 0.2 * 15200 * 0.5)


if __name__ == '__main__':
    unittest.main()
