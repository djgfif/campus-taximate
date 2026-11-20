"""C13 1층: 전국 대학 캠퍼스 주변의 실제 택시 이용량 (자료만, 시뮬레이션 없음).

- 대학 목록과 재적·재학생 수: 교육부 대학알리미 재적 학생 현황(대학·학과별), 공공데이터포털 15164558.
- 캠퍼스 위치: 학교 이름(본분교 구분 포함)을 카카오 로컬 키워드 검색(대학교 분류)으로 좌표화. 응답은 캐시해 다시 묻지 않는다.
- 택시 이용량: TIMS 2025 영업건수, 캠퍼스 좌표가 든 500m 격자와 둘레 8칸(약 1.5km), 학기 6개월·방학 3개월 하루 평균.
학기/방학 비율은 그 주변 택시 이용에 학생 수요가 얼마나 섞였는지 보는 신호다.
"""
from __future__ import annotations
import csv, io, json, re, shutil, statistics, subprocess, sys, time, urllib.request, zipfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import current_service as c12
import run

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = HERE / '.cache' / 'c13'
REPORTS = HERE / 'reports'
ENROLL_PK = '15164558'
ENROLL_URL = f'https://www.data.go.kr/data/{ENROLL_PK}/fileData.do'


def portal_download(pk, target):
    """data.go.kr file download via the portal's own button flow. Stops on CAPTCHA."""
    from http.cookiejar import CookieJar
    from urllib.parse import urlencode
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
    page_url = f'https://www.data.go.kr/data/{pk}/fileData.do'
    def req(url, fields=None):
        return opener.open(urllib.request.Request(url, data=urlencode(fields).encode() if fields else None,
                           headers={'User-Agent': 'Mozilla/5.0', 'Referer': page_url, 'X-Requested-With': 'XMLHttpRequest'}), timeout=120)
    page = req(page_url).read().decode('utf-8', 'replace')
    m = re.search(r"fn_fileDataDown\('" + pk + r"',\s*'([^']+)'", page)
    if not m:
        raise ValueError('No public download button for ' + pk)
    info = json.loads(req('https://www.data.go.kr/tcs/dss/selectFileDataDownload.do',
                          dict(publicDataDetailPk=m[1], publicDataPk=pk, atchFileId='', fileDetailSn='1', publicDataTyCode='PR0051')).read().decode())
    if info.get('status') is not True:
        raise ValueError('Download not authorized for ' + pk)
    chk = json.loads(req('https://www.data.go.kr/cmm/cmm/check-limit.json', dict(atchFileId=info['atchFileId'], fileDetailSn=info['fileDetailSn'])).read().decode())
    if chk.get('needCaptcha') is not False:
        raise ValueError('CAPTCHA required; stop')
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(target.suffix + '.part')
    with req('https://www.data.go.kr/cmm/cmm/fileDownload.do?' + urlencode(dict(atchFileId=info['atchFileId'], fileDetailSn=info['fileDetailSn']))) as r, part.open('wb') as out:
        shutil.copyfileobj(r, out)
    part.replace(target)
    return dict(file=target.name, sha256=run.sha(target), url=page_url, retrieved=datetime.now(timezone.utc).isoformat())


def read_table(path):
    raw = path.read_bytes()
    if raw[:2] == b'PK':
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.lower().endswith('.csv'))
            raw = z.read(name)
    for enc in ('utf-8-sig', 'cp949'):
        try:
            text = raw.decode(enc); break
        except UnicodeDecodeError:
            continue
    return list(csv.DictReader(io.StringIO(text)))


def kakao_key():
    for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines():
        if line.startswith('KAKAO_REST_KEY='):
            return line.split('=', 1)[1].strip().strip('"')
    raise SystemExit('KAKAO_REST_KEY missing')


def geocode(names, cache_path):
    cache = json.loads(cache_path.read_text(encoding='utf-8')) if cache_path.exists() else {}
    key = None
    for q in names:
        if q in cache:
            continue
        key = key or kakao_key()
        r = subprocess.run(['curl', '-s', '-m', '10', '-G', 'https://dapi.kakao.com/v2/local/search/keyword.json',
                            '--data-urlencode', f'query={q}', '--data-urlencode', 'size=5',
                            '-H', 'Authorization: KakaoAK ' + key], capture_output=True, text=True, encoding='utf-8')
        docs = json.loads(r.stdout).get('documents', [])
        pick = next((d for d in docs if d.get('category_name', '').startswith('교육,학문 > 학교 > 대학교')), None)
        cache[q] = None if pick is None else dict(name=pick['place_name'], x=float(pick['x']), y=float(pick['y']),
                                                   address=pick.get('road_address_name') or pick.get('address_name'),
                                                   category=pick['category_name'])
        time.sleep(0.05)
        cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=1), encoding='utf-8')
    return cache


def tims_by_grid(path=HERE / '.cache/tims-monthly-safe.csv'):
    g = defaultdict(Counter)
    with path.open(encoding='utf-8') as f:
        for r in csv.DictReader(f):
            g[r['grid']][r['month']] += int(r['trips'])
    return g


DAYS = {f'2025-{m:02d}': d for m, d in zip(range(1, 13), (31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31))}


def campus_taxi(grids, lon, lat, region=None, ratio=None):
    center, near = c12.tims_grid(lon, lat)
    month = Counter()
    for code in near:
        month.update(grids.get(code, {}))
    per_day = {m: month[m] / DAYS[m] for m in DAYS}
    sem = statistics.mean(per_day[m] for m in c12.SEMESTER_MONTHS)
    vac = statistics.mean(per_day[m] for m in c12.VACATION_MONTHS)
    r = (ratio or {}).get((region or {}).get(center))
    # 학기에만 늘어난 승차: 같은 시군구 전체의 계절 변화(r)를 빼고 남는 증가분 = 학사일정(학생) 효과
    excess = sem - vac * r if r else None
    return dict(grid=center, semester_per_day=sem, vacation_per_day=vac, ratio=(sem / vac) if vac > 0 else None,
                region_ratio=r, student_excess_per_day=excess)


KEEP_TYPES = ('대학교', '산업대학', '교육대학', '기술대학')


def schools(rows, year='2026'):
    agg = {}
    for r in rows:
        if r['공시연도'] != year or r['학교종류'] not in KEEP_TYPES or r['학교상태'] not in ('기존', '신설'):
            continue
        k = (r['학교명'], r['본분교명'])
        a = agg.setdefault(k, dict(name=r['학교명'], branch=r['본분교명'], kind=r['학교종류'], region=r['지역'],
                                   metro=r['소재지유형'], enrolled=0))
        a['enrolled'] += int(r['재학생_계_정원내'] or 0) + int(r['재학생_계_정원외'] or 0)
    return sorted(agg.values(), key=lambda a: -a['enrolled'])


def query(s):
    def camp(m):
        t = m.group(1)
        return ' ' + (t if t.endswith('캠퍼스') else t + '캠퍼스')
    q = re.sub(r'\s*\((.*?)\)', camp, s['name'])
    if s['branch'] != '본교' and '(' not in s['name']:
        q = f"{q} {s['region']}"
    return q


def region_ratio(grids, path=HERE / '.cache/kr-public/tims-regions.csv'):
    """Semester/vacation trips ratio of each 시군구 (all grids), and grid -> 시군구."""
    region = {}
    month = defaultdict(Counter)
    with path.open(encoding='utf-8') as f:
        for r in csv.DictReader(f):
            if r['status'] == 'MATCHED':
                region[r['grid']] = r['sgis_sigungu']
    for g, m in grids.items():
        if g in region:
            month[region[g]].update(m)
    ratio = {}
    for k, m in month.items():
        sem = statistics.mean(m[x] / DAYS[x] for x in c12.SEMESTER_MONTHS)
        vac = statistics.mean(m[x] / DAYS[x] for x in c12.VACATION_MONTHS)
        ratio[k] = sem / vac if vac else None
    return region, ratio


def main():
    raw = CACHE / 'enrollment.raw'
    if not raw.exists():
        portal_download(ENROLL_PK, raw)
    sc = schools(read_table(raw))
    geo = geocode([query(x) for x in sc], CACHE / 'geocode.json')
    grids = tims_by_grid()
    region, ratio = region_ratio(grids)
    out = []; seen = {}; dropped = []
    for x in sc:  # 재학생이 많은 순서라 같은 좌표면 본교가 먼저 남는다
        g = geo.get(query(x))
        if g is None:
            continue
        key = (round(g['x'], 3), round(g['y'], 3))
        if key in seen:
            dropped.append(f"{x['name']} {x['branch']} (좌표가 {seen[key]}와 같음)"); continue
        seen[key] = x['name']
        t = campus_taxi(grids, g['x'], g['y'], region, ratio)
        out.append(dict(x, place=g['name'], lon=g['x'], lat=g['y'], address=g['address'], **t))
    out.sort(key=lambda r: -(r['student_excess_per_day'] if r['student_excess_per_day'] is not None else -1e9))
    for i, r in enumerate(out, 1):
        r['rank'] = i
    n = len(out)
    dj = next(r for r in out if r['name'] == '대진대학교')
    sem = [r['student_excess_per_day'] for r in out if r['student_excess_per_day'] is not None]
    q = lambda p: sorted(sem)[int(p * (len(sem) - 1))]
    run.json_write(CACHE / 'campuses.json', out)
    summary = dict(generated_at=datetime.now(timezone.utc).isoformat(), campuses=n, missing=[query(x) for x in sc if geo.get(query(x)) is None],
                   dropped_same_location=dropped,
                   quantiles={k: q(v) for k, v in (('p10', .1), ('p25', .25), ('median', .5), ('p75', .75), ('p90', .9))},
                   daejin=dj, enrollment_sha256=run.sha(raw), tims_sha256=run.sha(HERE / '.cache/tims-monthly-safe.csv'),
                   sources=[ENROLL_URL, 'https://www.data.go.kr/data/15165682/fileData.do', 'Kakao Local keyword search (대학교 분류)'])
    def row(r):
        ex = r['student_excess_per_day']; rr = r['region_ratio']
        return (f"| {r['rank']} | {r['name']}{'' if r['branch'] == '본교' else ' ' + r['branch']} | {r['region']} | {r['enrolled']:,} | "
                f"{r['semester_per_day']:.0f} | {r['vacation_per_day']:.0f} | {r['ratio']:.2f} | {rr:.2f} | {ex:.0f} |" if ex is not None and r['ratio'] else
                f"| {r['rank']} | {r['name']} | {r['region']} | {r['enrolled']:,} | {r['semester_per_day']:.0f} | {r['vacation_per_day']:.0f} | - | - | - |")
    head = '| 순위 | 학교 | 지역 | 재학생 | 학기 하루 | 방학 하루 | 학기/방학 | 같은 시군구 학기/방학 | 학기에만 늘어난 승차(하루) |'
    sep = '|---:|---|---|---:|---:|---:|---:|---:|---:|'
    L = ['# C13 1층: 전국 대학 캠퍼스 주변 실제 택시 이용량', '',
         '출처: 교육부 대학알리미 재적 학생 현황 2026 (공공데이터포털 15164558), 한국교통안전공단 택시운행정보 TIMS 2025 (15165682), 캠퍼스 좌표는 카카오 로컬 검색.', '',
         f'4년제 대학 캠퍼스 {n}곳. 캠퍼스 좌표 격자와 둘레 8칸(약 1.5km) 안의 택시 승차 건수, 학기(3~5·9~11월)와 방학(1·7·8월) 하루 평균.', '',
         '도심 캠퍼스의 택시 승차는 대부분 역·상권 이용이라 그대로는 학생 수요가 아니다. 그래서 같은 시군구 전체의 계절 변화를 빼고 '
         '"학기에만 늘어난 승차" = 학기 하루 − 방학 하루 × (같은 시군구 학기/방학)을 학생(학사일정) 효과로 본다.', '',
         f"학기에만 늘어난 승차 분포(하루): 하위 10% {summary['quantiles']['p10']:.0f}건, 25% {summary['quantiles']['p25']:.0f}건, 중앙값 {summary['quantiles']['median']:.0f}건, "
         f"75% {summary['quantiles']['p75']:.0f}건, 상위 10% {summary['quantiles']['p90']:.0f}건.", '',
         f"대진대: 학기 하루 {dj['semester_per_day']:.1f}건, 방학 {dj['vacation_per_day']:.1f}건, 포천 전체 학기/방학 {dj['region_ratio']:.2f}배 → "
         f"학기에만 늘어난 승차 하루 {dj['student_excess_per_day']:.1f}건, {n}곳 중 {dj['rank']}위.", '',
         '## 학기에만 늘어난 승차 상위 30곳', '', head, sep] + [row(r) for r in out[:30]]
    L += ['', '## 대진대와 비슷한 규모 (재학생 8,000~12,000명)', '', head, sep] + [row(r) for r in out if 8000 <= r['enrolled'] <= 12000][:20]
    L += ['', '## 해석과 한계', '',
          '- 학기에만 늘어난 승차는 학사일정에 따라 움직이는 택시 이용의 하한 추정이다. 학기와 방학 모두 타는 학생 이용은 빠지고, 계절 관광·행사 차이가 섞일 수 있다.',
          '- 본교 안에 캠퍼스가 여럿인 학교(예: 경희대 서울·국제)는 재학생 수가 합쳐져 있고 좌표는 검색된 한 곳이다. 같은 좌표로 검색된 다른 캠퍼스는 뺐다.',
          '- 1.5km 안에 여러 대학이 있으면 같은 택시 승차가 각 학교에 겹쳐 잡힌다.', '']
    (REPORTS / 'c13-campus-survey.md').write_text('\n'.join(L), encoding='utf-8')
    run.json_write(REPORTS / 'c13-campus-survey.json', dict(summary, campuses_top=out[:60]))
    print('C13 layer1 done', n, dj['rank'])


if __name__ == '__main__':
    main()
