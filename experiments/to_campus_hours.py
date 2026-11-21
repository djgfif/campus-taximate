"""C15 등교 도착 시각: 생활이동(OA-22300, 2026-10 평일 20일)에서 캠퍼스 지역에 도착하는 이동의 도착 시각(fns_time_cd) 분포.

목적 코드별로 따로 센다. 평일 20일로 보면 1 = 오전 8시에 뾰족한 출근, 2 = 대학가에서 1보다 많고 8~11시에 퍼진 등교,
3 = 귀가, 7 = 기타로 보인다(한국외대 이문1동: 2가 하루 8,865, 1이 6,271).
목적 코드의 정의는 저장소에 문서가 없어, 도착 시각 분포로 성격을 추정하고 보고서에 그 근거를 남긴다.
결과: .cache/c15/arrival-hours.json = {캠퍼스 도착 코드: {목적: {시: 이동량}}}
"""
from __future__ import annotations
import json, sys, zipfile
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import livingmove as lm
import metro_campuses as m

HERE = Path(__file__).resolve().parent
OUT = HERE / '.cache' / 'c15' / 'arrival-hours.json'
PURPOSES = ('1', '2', '3', '7')


def main():
    origins = json.loads((m.CACHE / 'origins.json').read_text(encoding='utf-8'))
    want = {v['origin'] for v in origins.values()}
    hours = defaultdict(lambda: defaultdict(Counter)); days = []
    for path in sorted((HERE / '.cache/kr-public/living-move').glob('seoul_purpose_admdong3_*.zip')):
        ds = path.stem[-8:]; dt = date.fromisoformat(f'{ds[:4]}-{ds[4:6]}-{ds[6:]}')
        if lm.exclusion_reason(dt):
            continue
        days.append(ds)
        with zipfile.ZipFile(path) as z, z.open(z.namelist()[0]) as stream:
            header = stream.readline().decode('utf-8-sig').strip().split(',')
            ix = {k: i for i, k in enumerate(header)}
            for line in stream:
                f = line.decode('utf-8').rstrip('\r\n').split(',')
                d, purpose = f[ix['d_admdong_cd']], f[ix['move_purpose']]
                if d not in want or purpose not in PURPOSES:
                    continue
                cnt = lm.parse_count(f[ix['cnt']])
                if cnt is None:
                    continue
                hours[d][purpose][lm.normalize_hour(f[ix['fns_time_cd']])] += cnt
        print('aggregated', path.name, flush=True)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(dict(days=days, hours={d: {p: {str(h): c for h, c in v.items()} for p, v in ps.items()} for d, ps in hours.items()}),
                              ensure_ascii=False), encoding='utf-8')
    print('done', len(hours), 'destinations,', len(days), 'days')


if __name__ == '__main__':
    main()
