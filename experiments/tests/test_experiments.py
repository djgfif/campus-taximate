import csv
from datetime import datetime
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fares
import run


def record(**changes):
    row = dict(record_id="private-trip-1", cohort="prospective", origin_label="private-origin",
               destination_label="private-destination", ride_at="2026-11-19T12:00:00+09:00",
               quote_at="2026-11-19T11:55:00+09:00", expected_krw="10000", actual_krw="11500", currency="KRW",
               expected_toll_scope="included", actual_toll_scope="included", expected_surcharge_scope="included",
               actual_surcharge_scope="included", quote_source="kakao_quote", actual_source="meter_receipt")
    row.update(changes)
    return row


class FareTests(unittest.TestCase):
    def analyze(self, rows):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fares.csv"
            with path.open("w", encoding="utf-8-sig", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=fares.FIELDS)
                writer.writeheader(); writer.writerows(rows)
            return fares.analyze(path)

    def test_empty_template_is_unmeasured_not_zero_error(self):
        report, details = self.analyze([])
        self.assertEqual(report["measurement_status"], "NO_OBSERVED_FARES")
        self.assertIsNone(report["cohorts"]["prospective"]["mape"])
        self.assertEqual(details, [])

    def test_exact_15pct_is_inside_and_mape_uses_actual(self):
        report, _ = self.analyze([record()])
        cohort = report["cohorts"]["prospective"]
        self.assertEqual(cohort["within_15pct_count"], 1)
        self.assertEqual(cohort["above_15pct_count"], 0)
        self.assertAlmostEqual(cohort["mape"], 1500/11500)
        self.assertAlmostEqual(cohort["signed_relative"]["mean"], .15)

    def test_one_won_over_boundary_is_above(self):
        report, _ = self.analyze([record(actual_krw="11501")])
        self.assertEqual(report["cohorts"]["prospective"]["above_15pct_count"], 1)

    def test_historical_requery_never_pooled_into_prospective(self):
        report, _ = self.analyze([record(cohort="historical_requery", quote_at="2026-11-20T09:00:00+09:00")])
        self.assertEqual(report["cohorts"]["prospective"]["n"], 0)
        self.assertEqual(report["cohorts"]["historical_requery"]["n"], 1)

    def test_reject_wrong_scope_currency_timezone_amount_or_quote_order(self):
        cases = [(dict(actual_toll_scope="excluded"), "mismatched_toll_scope"),
                 (dict(actual_surcharge_scope="unknown"), "unknown_surcharge_scope"),
                 (dict(currency="USD"), "currency_not_KRW"),
                 (dict(ride_at="2026-11-19T12:00:00"), "missing_or_invalid_timezone"),
                 (dict(quote_at="2026-11-19T03:01:00Z"), "prospective_quote_after_ride"),
                 (dict(actual_krw="NaN"), "invalid_amount"), (dict(actual_krw="10.1"), "invalid_amount"),
                 (dict(expected_krw="0"), "invalid_amount"),
                 (dict(actual_source="simulated"), "actual_payment_evidence_required")]
        for changes, reason in cases:
            with self.subTest(changes=changes):
                report, _ = self.analyze([record(**changes)])
                self.assertEqual(report["rows_accepted"], 0)
                self.assertEqual(report["exclusion_reasons"], {reason: 1})

    def test_timezone_equivalent_quote_is_allowed(self):
        report, _ = self.analyze([record(quote_at="2026-11-19T03:00:00Z")])
        self.assertEqual(report["rows_accepted"], 1)

    def test_duplicate_trip_and_ids_exclude_all_copies(self):
        for rows in ([record(), record(record_id="second")],
                     [record(), record(origin_label="other")]):
            report, _ = self.analyze(rows)
            self.assertEqual(report["rows_accepted"], 0)
            self.assertEqual(report["exclusion_reasons"], {"duplicate_record": 2})

    def test_duplicate_trip_timezone_normalized(self):
        report, _ = self.analyze([record(), record(record_id="second", ride_at="2026-11-19T03:00:00Z")])
        self.assertEqual(report["exclusion_reasons"], {"duplicate_record": 2})

    def test_public_report_contains_no_trip_fields(self):
        report, details = self.analyze([record()])
        public = json.dumps(report) + fares.markdown(report)
        self.assertNotIn("private-origin", public)
        self.assertNotIn("private-trip-1", public)
        self.assertEqual(details[0]["record_id"], "private-trip-1")

    def test_quantile_interpolation(self):
        self.assertIsNone(fares.quantile([], .95))
        self.assertEqual(fares.quantile([.2], .95), .2)
        self.assertAlmostEqual(fares.quantile([0, 1], .90), .9)

    def test_short_csv_row_is_excluded_without_crashing(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "short.csv"
            path.write_text(",".join(fares.FIELDS) + "\nonly-id,prospective\n", encoding="utf-8")
            report, _ = fares.analyze(path)
            self.assertEqual(report["exclusion_reasons"], {"missing_field": 1})


class PublicArtifactTests(unittest.TestCase):
    def test_korean_public_input_hash(self):
        path=run.HERE/'reports'/'public-fares.json'
        if not path.exists():self.skipTest('Run C5 first')
        report=json.loads(path.read_text(encoding='utf-8'))
        self.assertEqual(report['schema_version'],3)
        self.assertEqual(report['tool_sha256'],run.sha(run.HERE/'fares.py'))
        errors=report['tims']['errors']
        recorded=Path(errors['path'])
        local_cache=run.HERE/'.cache'/'kr-public'/recorded.name
        errors_path=recorded if recorded.exists() else local_cache
        if not errors_path.exists():
            self.skipTest('Local TIMS error cache not present: '+str(local_cache))
        self.assertEqual(errors['sha256'],run.sha(errors_path))
        self.assertEqual(errors['grain'],'grid_month_mean')
        self.assertEqual(set(report['tims']['monthly']) & {'national','commute','pocheon_cached_grids'},
                         {'national','commute','pocheon_cached_grids'})


class ReplayTests(unittest.TestCase):
    def test_input_generation_is_seeded(self):
        with tempfile.TemporaryDirectory() as folder:
            first, second, third = (Path(folder) / name for name in ("a", "b", "c"))
            run.synthetic(first, 100, 3); run.synthetic(second, 100, 3); run.synthetic(third, 101, 3)
            self.assertEqual(run.sha(first), run.sha(second))
            self.assertNotEqual(run.sha(first), run.sha(third))
            self.assertEqual(first.read_text().count("END"), 3)

    def test_summary_weights_matched_and_all_denominators(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "result.csv"
            keys = ("requests,matched,expired,mean_ratio_saving_matched,mean_equal_saving_matched,"
                    "mean_delay_minutes_matched,max_delay_minutes,saved_krw,equal_saving_below_10pct_count,"
                    "conservation_errors,offline_route_calls,engine_and_offline_route_ms").split(",")
            rows = [(4, 2, 2, .5, .4, 3, 5, 1000, 1, 0, 5, 10), (4, 4, 0, .2, .1, 6, 9, 2000, 0, 0, 6, 20)]
            with path.open("w", newline="") as stream:
                writer = csv.writer(stream); writer.writerow(keys); writer.writerows(rows)
            summary = run.summarize(path)
            self.assertAlmostEqual(summary["matching_rate"], .75)
            self.assertAlmostEqual(summary["mean_ratio_saving_matched"], .3)
            self.assertAlmostEqual(summary["mean_ratio_saving_all"], .225)
            self.assertEqual(summary["saved_krw"], 3000)
            self.assertEqual(summary["network_ms"], 0)


if __name__ == "__main__":
    unittest.main()
