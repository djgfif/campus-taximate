from datetime import date
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import livingmove as lm
import acquire_public as acquire

class LivingMoveTests(unittest.TestCase):
    def test_mixed_time_codes(self):
        for value,hour in [('16',16),('1720',17),('0800',8),('020',0),('0',0),('2340',23)]:
            self.assertEqual(lm.normalize_hour(value),hour)
        for value in ('2400','1760','17:20','-1','*','1701'):
            with self.assertRaises(ValueError):lm.normalize_hour(value)

    def test_masked_count_is_missing_not_zero(self):
        self.assertIsNone(lm.parse_count('*'))
        self.assertIsNone(lm.parse_count(''))
        self.assertEqual(lm.parse_count('0'),0.)
        self.assertEqual(lm.parse_count('3.50'),3.5)
        for value in ('NaN','-1','unknown'):
            with self.assertRaises(ValueError):lm.parse_count(value)

    def test_h10_to_kt8_and_district_fallback(self):
        self.assertEqual(lm.point_code(dict(h_code='4165031000'),set()),('41650000','시군구'))
        self.assertEqual(lm.point_code(dict(h_code='1132052100'),{'11320521'}),('11320521','행정동8'))
        self.assertEqual(lm.point_code(dict(h_code='1132052100'),set()),('11320000','서울 자치구 후퇴'))
        self.assertEqual(lm.region_flow({'11320521':2,'11320522':3},dict(h_code='1132052300'),set())[1],5)

    @unittest.skipUnless(importlib.util.find_spec('duckdb') is not None, 'duckdb is not installed in this Python environment')
    def test_streaming_aggregate_and_mask_denominators(self):
        with tempfile.TemporaryDirectory() as folder:
            cache=Path(folder);(cache/'living-move').mkdir()
            for ds in ('20261001','20261002','20261005','20261009','20261031'):
                purpose='o_admdong_cd,d_admdong_cd,st_time_cd,move_purpose,cnt,etl_ymd\n'
                purpose+=f'41650000,41650000,1720,3,5,{ds}\n41650000,41650000,17,3,*,{ds}\n'
                age='o_admdong_cd,d_admdong_cd,st_time_cd,2030_cnt,total_cnt,etl_ymd\n'
                age+=f'41650000,41650000,17,3,5,{ds}\n'
                for suffix,text in (('3_',purpose),('4_in_',age)):
                    with zipfile.ZipFile(cache/'living-move'/('seoul_purpose_admdong'+suffix+ds+'.zip'),'w') as z:
                        z.writestr('data.csv',text)
            result=lm.aggregate(cache,cache/'reports')
            self.assertEqual(result['days'],2)
            self.assertEqual(result['destination_home'][0]['cnt'],10.)
            self.assertEqual(result['hours_home'][0]['hour'],17)
            self.assertAlmostEqual(result['age_check']['share_2030'],.6)
            self.assertEqual(sum(i['masked'].get('cnt',0) for i in result['inspections']),2)
            self.assertEqual(len(result['files']),10)
            self.assertEqual(sum(m['included'] for m in result['files']),4)
            excluded={m['date']:m['exclusion_reason'] for m in result['files'] if not m['included']}
            self.assertEqual(excluded['2026-10-05'],'개천절 대체공휴일')
            self.assertEqual(excluded['2026-10-09'],'한글날')
            self.assertEqual(excluded['2026-10-31'],'주말')

    def test_weekday_holiday_exclusions_are_explicit(self):
        self.assertEqual(lm.exclusion_reason(date(2026,10,5)),'개천절 대체공휴일')
        self.assertEqual(lm.exclusion_reason(date(2026,10,9)),'한글날')
        self.assertEqual(lm.exclusion_reason(date(2026,10,31)),'주말')
        self.assertIsNone(lm.exclusion_reason(date(2026,10,6)))
        self.assertEqual(lm.normalize_hour('0040'),0)
        self.assertEqual(lm.normalize_hour('1740'),17)

    def test_existing_zip_skips_network_and_crc_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'cache.zip'
            with zipfile.ZipFile(path,'w') as z:z.writestr('data.csv','a,b\n1,2\n')
            acquire.download('https://invalid.invalid',path,dict(infId='OA-22300'))
            acquire.validate_zip(path)
            path.write_text('not a zip')
            with self.assertRaises(zipfile.BadZipFile):acquire.validate_zip(path)

if __name__=='__main__':unittest.main()
