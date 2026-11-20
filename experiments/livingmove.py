"""포천 출발 생활이동 집계. ZIP을 스트리밍하며 원본을 디스크에 풀지 않는다."""
from __future__ import annotations
import argparse
from collections import Counter
import csv
from datetime import date, datetime, timezone
import io
import json
import math
from pathlib import Path
import statistics
import zipfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fares import file_manifest, sql_path

HERE = Path(__file__).resolve().parent
CACHE = HERE/'.cache'/'kr-public'
URLS = {3:'https://data.seoul.go.kr/dataList/OA-22300/F/1/datasetView.do',
        4:'https://data.seoul.go.kr/dataList/OA-22299/F/1/datasetView.do'}
ATTRIBUTION = '출처: 서울특별시·KT, 서울시 통신데이터'
HOLIDAYS = {'20261005':'개천절 대체공휴일', '20261009':'한글날'}


def exclusion_reason(dt):
    return HOLIDAYS.get(dt.strftime('%Y%m%d')) or ('주말' if dt.weekday()>=5 else None)


def normalize_hour(value):
    s = str(value).strip()
    if not s.isdigit(): raise ValueError('Invalid time code: '+s)
    if len(s) <= 2:
        hour, minute = int(s), 0
    elif len(s) in (3,4):
        hour, minute = int(s[:-2]), int(s[-2:])
    else: raise ValueError('Invalid time code: '+s)
    if not 0 <= hour < 24 or minute not in (0,20,40):
        raise ValueError('Invalid time code: '+s)
    return hour


def parse_count(value):
    # Suppression is unknown, not an observed zero. Never impute masked cells.
    if str(value).strip() in ('*','', 'NULL'): return None
    count = float(value)
    if not math.isfinite(count) or count < 0: raise ValueError('Invalid count')
    return count


def point_code(point, available):
    code = str(point['h_code'])
    if len(code)!=10 or not code.isdigit(): raise ValueError('Invalid h_code')
    if code.startswith('41'): return code[:5]+'000', '시군구'
    if code.startswith('11'):
        if code[:8] in available: return code[:8], '행정동8'
        return code[:5]+'000', '서울 자치구 후퇴'
    raise ValueError('Unsupported point region')


def region_flow(flows, point, available):
    code, rule = point_code(point, available)
    if rule=='서울 자치구 후퇴':
        return code, sum(v for k,v in flows.items() if k[:5]==code[:5]), rule
    return code, flows.get(code,0.), rule


def aggregate(cache=CACHE, reports=None):
    import duckdb
    cache=Path(cache); folder=cache/'living-move'
    db=duckdb.connect(); db.execute('SET threads=4')
    purpose=[]; ages=[]; manifests=[]; inspections=[]
    files=sorted(folder.glob('seoul_purpose_admdong*.zip'))
    if not files: raise ValueError('No living-move archives')
    for path in files:
        kind=4 if 'admdong4_in_' in path.name else 3
        ds=path.stem[-8:]; dt=date.fromisoformat(ds[:4]+'-'+ds[4:6]+'-'+ds[6:])
        reason=exclusion_reason(dt); used=reason is None
        manifests.append(dict(date=dt.isoformat(),dataset='OA-22300' if kind==3 else 'OA-22299',
            included=used,exclusion_reason=reason,**file_manifest(path,URLS[kind])))
        if not used: continue
        with zipfile.ZipFile(path) as z:
            members=[n for n in z.namelist() if n.endswith('.csv')]
            if len(members)!=1: raise ValueError('Expected single CSV ZIP')
            # Iterating ZipExtFile verifies the member CRC when EOF is reached.
            with z.open(members[0]) as stream:
                header=stream.readline().decode('utf-8-sig')
                filtered=[line.decode('utf-8') for line in stream if line.startswith(b'41650000,')]
        rows=list(csv.DictReader(io.StringIO(header+''.join(filtered))))
        masked=Counter(); timecodes=Counter(); invalid=0
        for r in rows:
            if r['etl_ymd']!=ds: raise ValueError('ZIP date mismatch')
            hour=normalize_hour(r['st_time_cd']); timecodes[r['st_time_cd']]+=1
            if kind==3:
                count=parse_count(r['cnt'])
                if count is None: masked['cnt']+=1; continue
                purpose.append((dt.isoformat(),r['d_admdong_cd'],hour,int(r['move_purpose']),count))
            else:
                young=parse_count(r['2030_cnt']); total=parse_count(r['total_cnt'])
                if young is None: masked['2030_cnt']+=1
                if total is None: masked['total_cnt']+=1
                if young is None or total is None: invalid+=1; continue
                if young>total+1e-6: raise ValueError('2030 exceeds total')
                ages.append((dt.isoformat(),r['d_admdong_cd'],hour,young,total))
        inspections.append(dict(file=path.name,pocheon_origin_rows=len(rows),masked=dict(masked),
                                excluded_age_pairs=invalid,time_codes=dict(sorted(timecodes.items()))))
        print(path.name, len(rows), 'Pocheon rows', flush=True)
    dates={r[0] for r in purpose}
    if dates!={r[0] for r in ages}: raise ValueError('Purpose/age days differ')
    target=cache/'living-move-pocheon.csv'
    grouped=Counter()
    for dt,code,h,p,c in purpose: grouped[(dt,code,h,p)]+=c
    with target.open('w',encoding='utf-8',newline='') as out:
        writer=csv.writer(out);writer.writerow(['date','d_code','hour','purpose','cnt'])
        writer.writerows((*key,value) for key,value in sorted(grouped.items()))
    age_target=cache/'living-move-age.csv'
    grouped_age={}
    for dt,code,h,young,total in ages:
        pair=grouped_age.setdefault((dt,code,h),[0.,0.]);pair[0]+=young;pair[1]+=total
    with age_target.open('w',encoding='utf-8',newline='') as out:
        writer=csv.writer(out);writer.writerow(['date','d_code','hour','young','total'])
        writer.writerows((*key,*value) for key,value in sorted(grouped_age.items()))
    db.execute(f"CREATE TABLE p AS SELECT date::VARCHAR date,d_code::VARCHAR d_code,hour,purpose,cnt FROM read_csv_auto({sql_path(target)},types={{'date':'VARCHAR','d_code':'VARCHAR'}})")
    db.execute(f"CREATE TABLE a AS SELECT date::VARCHAR date,d_code::VARCHAR d_code,hour,young,total FROM read_csv_auto({sql_path(age_target)},types={{'date':'VARCHAR','d_code':'VARCHAR'}})")
    def records(q):
        result=db.execute(q); keys=[c[0] for c in result.description]
        return [dict(zip(keys,row)) for row in result.fetchall()]
    def shares(condition, dimension):
        return records(f'SELECT {dimension},sum(cnt) cnt,sum(cnt)/sum(sum(cnt)) OVER () AS "share" FROM p WHERE {condition} GROUP BY {dimension} ORDER BY cnt DESC')
    daily=records('SELECT date,sum(cnt) all_cnt,sum(cnt) FILTER (WHERE purpose=3) home_cnt FROM p GROUP BY date ORDER BY date')
    vals=[r['home_cnt'] for r in daily]
    age=records('SELECT d_code,hour,sum(young) young,sum(total) total,sum(young)/nullif(sum(total),0) share_2030 FROM a GROUP BY ALL ORDER BY d_code,hour')
    totals=db.execute('SELECT sum(young),sum(total) FROM a').fetchone()
    summary=dict(generated_at=datetime.now(timezone.utc).isoformat(),attribution=ATTRIBUTION,
        source_urls=URLS,dates=sorted(dates),days=len(dates),origin='41650000',
        time_rule='1~2자리 시간(예:16)은 그대로, 3~4자리 HHMM(예:0800,1720)은 HH; MM은 00/20/40만 허용. 출발시각 기준 KST.',
        masked_rule='cnt의 * 및 빈 값은 결측으로 제외하며 0으로 대체하지 않음. 비마스킹 공개 셀의 합계와 그 조건부 비율. 연령은 2030/total 모두 공개된 동일 셀만 사용.',
        calendar_rule='2026-10 월~금 중 10/5 개천절 대체공휴일과 10/9 한글날 제외. 20일 표본. 제외일 ZIP(10/5, 10/31)은 해시 manifest에 사유와 함께 유지.',
        destination_home=shares('purpose=3','d_code'),destination_all=shares('true','d_code'),
        hours_home=shares('purpose=3','hour'),hours_all=shares('true','hour'),
        daily=daily,daily_home_variation=dict(min=min(vals),max=max(vals),mean=statistics.mean(vals),
            stdev=statistics.stdev(vals) if len(vals)>1 else 0.,cv=(statistics.stdev(vals)/statistics.mean(vals)) if len(vals)>1 else 0.),
        age_check=dict(purpose_available=False,young=totals[0],total=totals[1],share_2030=totals[0]/totals[1],
                       by_destination_hour=age,
                       by_destination=records('SELECT d_code,sum(young) young,sum(total) total,sum(young)/nullif(sum(total),0) share_2030 FROM a GROUP BY d_code ORDER BY total DESC'),
                       by_hour=records('SELECT hour,sum(young) young,sum(total) total,sum(young)/nullif(sum(total),0) share_2030 FROM a GROUP BY hour ORDER BY hour')),
        inspections=inspections,files=manifests,aggregate=file_manifest(target))
    (cache/'living-move-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    reports=Path(reports) if reports else HERE/'reports'; reports.mkdir(exist_ok=True)
    (reports/'living-move-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    lines=['# 포천 출발 생활이동 집계','',ATTRIBUTION,'',summary['time_rule'],summary['masked_rule'],summary['calendar_rule'],
           f"집계 {len(dates)}일. 2030 비율 {summary['age_check']['share_2030']:.2%} (목적 구분 없는 기술통계).",'','## 귀가 목적지 및 전체 목적 비교','',
           '| 목적지 코드 | 귀가 추정 이동량 | 귀가 비중 | 전체 목적 비중 |','|---|---:|---:|---:|']
    allshares={r['d_code']:r['share'] for r in summary['destination_all']}
    for r in summary['destination_home']:
        lines.append(f"| {r['d_code']} | {r['cnt']:.2f} | {r['share']:.3%} | {allshares.get(r['d_code'],0):.3%} |")
    lines+=['','## 출발 시간 분포','','| 시 | 귀가 비중 | 전체 목적 비중 |','|---|---:|---:|']
    ah={r['hour']:r['share'] for r in summary['hours_all']}
    for r in sorted(summary['hours_home'],key=lambda r:r['hour']):
        lines.append(f"| {r['hour']} | {r['share']:.3%} | {ah.get(r['hour'],0):.3%} |")
    lines+=['','## 일별 변동','','| 일자 | 귀가 | 전체 목적 |','|---|---:|---:|']
    for r in daily: lines.append(f"| {r['date']} | {r['home_cnt']:.2f} | {r['all_cnt']:.2f} |")
    lines+=['','귀가 일별 변동: '+json.dumps(summary['daily_home_variation'],ensure_ascii=False),
            '', '2030 목적지×시간 비율과 파일별 SHA256·크기·출처 URL·마스킹 수는 동명 JSON. 원본 행은 공개하지 않는다.',
            '생활이동은 통신 기반 추정 이동량이며 실제 학생·캠퍼스 OD가 아니다. 귀가가 학생 하교를 대표한다는 가정의 타당성은 미검증이다.']
    (reports/'living-move-summary.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return summary

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--cache',type=Path,default=CACHE)
    aggregate(p.parse_args().cache)
