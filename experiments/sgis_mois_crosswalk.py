"""SGIS 2025Q2 시군구 경계 ↔ 행정안전부 시군구 코드(생활이동 d_admdong_cd 앞 5자리) 대응표.

각 SGIS 시군구 다각형 안쪽 대표점(shapely representative_point)을 카카오 로컬 coord2regioncode에 넣어
행정동 코드(H, 행정안전부 10자리)를 받고 앞 5자리+'000'을 시군구 코드로 쓴다.
이름(SGIS SIGUNGU_NM vs 카카오 region_2depth_name)을 함께 남겨 대응을 사람이 검증할 수 있게 한다.
키는 campus-taximate/.env의 KAKAO_REST_KEY이며 출력하지 않는다. 이미 받은 행은 건너뛴다.
"""
import argparse, csv, io, json, subprocess, time, zipfile
from pathlib import Path

import shapefile
from pyproj import Transformer
from shapely.geometry import shape

HERE = Path(__file__).resolve().parent
CACHE = HERE / '.cache' / 'kr-public'
OUT = CACHE / 'livingmove-sgis-crosswalk.csv'
URL = 'https://dapi.kakao.com/v2/local/geo/coord2regioncode.json'
REORG = {
    'INCHEON_2026': ({'23010', '23020', '23080'}, ['28125000', '28155000', '28275000', '28290000']),
    'HWASEONG_2026': ({'31240'}, ['41591000', '41593000', '41595000', '41597000']),
}


def kakao_key():
    for line in (HERE.parent / '.env').read_text(encoding='utf-8').splitlines():
        if line.startswith('KAKAO_REST_KEY='):
            return line.split('=', 1)[1].strip().strip('"')
    raise SystemExit('KAKAO_REST_KEY missing in .env')


def region(lon, lat, key):
    r = subprocess.run(['curl', '-s', '-m', '10', '-G', URL, '--data-urlencode', f'x={lon}',
                        '--data-urlencode', f'y={lat}', '-H', 'Authorization: KakaoAK ' + key],
                       capture_output=True, text=True, encoding='utf-8')
    docs = json.loads(r.stdout)['documents']
    return next(d for d in docs if d['region_type'] == 'H')


def polygons(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        stem = next(n[:-4] for n in z.namelist() if '/bnd_sigungu_' in n and n.endswith('.shp'))
        raw = {ext: z.read(stem + ext) for ext in ('.shp', '.dbf')}
    reader = shapefile.Reader(shp=io.BytesIO(raw['.shp']), dbf=io.BytesIO(raw['.dbf']), encoding='utf8')
    fields = [f[0] for f in reader.fields[1:]]
    for sr in reader.iterShapeRecords():
        rec = dict(zip(fields, sr.record))
        yield rec, shape(sr.shape.__geo_interface__)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--boundary', type=Path, default=CACHE / 'sgis-admin-2025.zip')
    p.add_argument('--output', type=Path, default=OUT)
    a = p.parse_args()
    done = {}
    if a.output.exists():
        with a.output.open(encoding='utf-8') as f:
            done = {r['sgis']: r for r in csv.DictReader(f)}
    key = kakao_key()
    to_wgs = Transformer.from_crs(5179, 4326, always_xy=True)
    rows = []
    for rec, geom in polygons(a.boundary):
        code = str(rec['SIGUNGU_CD'])
        if code in done:
            rows.append(done[code]); continue
        pt = geom.representative_point()
        lon, lat = to_wgs.transform(pt.x, pt.y)
        h = region(lon, lat, key)
        rows.append(dict(sgis=code, sgis_name=rec['SIGUNGU_NM'], mois=h['code'][:5] + '000',
                         kakao_sido=h['region_1depth_name'], kakao_sigungu=h['region_2depth_name'],
                         lon=f'{lon:.6f}', lat=f'{lat:.6f}'))
        time.sleep(0.05)
    # 2025Q2 SGIS 경계 이후 개편된 곳은 1:1이 아니므로 옛 경계 묶음 ↔ 새 코드 묶음으로 합친다.
    # 인천: 중구·동구·서구 → 제물포구·영종구·서해구·검단구(2026-07), 화성시 → 만세·효행·병점·동탄구(2026-02).
    for r in rows:
        r['group'] = ''
    out = []
    for r in rows:
        g = next((name for name, (sg, _) in REORG.items() if r['sgis'] in sg), None)
        if g is None:
            out.append(r); continue
        if any(o['group'] == g and o['sgis'] == r['sgis'] for o in out):
            continue
        for m in REORG[g][1]:
            out.append(dict(r, mois=m, group=g))
    rows = out
    with a.output.open('w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader(); w.writerows(rows)
    names_differ = [r for r in rows if r['sgis_name'].replace(' ', '') != r['kakao_sigungu'].replace(' ', '')]
    dup = {m for m in (r['mois'] for r in rows) if sum(r['mois'] == m for r in rows) > 1}
    print(json.dumps(dict(rows=len(rows), names_differ=len(names_differ), duplicate_mois=sorted(dup),
                          examples=names_differ[:10]), ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
