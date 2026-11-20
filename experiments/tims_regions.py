"""TIMS 500m 중심점을 공식 SGIS 2025Q2 시군구 경계에 공간 결합한다."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parent))
from fares import file_manifest, sql_path

HERE=Path(__file__).resolve().parent
BOUNDARY_URL='https://www.data.go.kr/data/15129688/fileData.do'
GRID_SOURCE_URL='https://www.ktdb.go.kr/DATA/pblcte/20230613101540605.pdf'
LETTERS='가나다라마바사아자차카타파하'


def grid_center(code):
    if not re.fullmatch('['+LETTERS+']{2}[0-9]{6}',code):raise ValueError('Invalid TIMS grid: '+code)
    col,row=int(code[2:5]),int(code[5:8])
    if col>=200 or row>=200:raise ValueError('TIMS 500m index outside 0..199: '+code)
    return (700000+LETTERS.index(code[0])*100000+col*500+250,
            1300000+LETTERS.index(code[1])*100000+row*500+250)


def spatial_assign(centers, polygons):
    import numpy as np
    import shapely
    coords=np.asarray(centers,dtype=float)
    matches=[[] for _ in centers]
    for code,geometry in polygons:
        shapely.prepare(geometry)
        left,bottom,right,top=geometry.bounds
        candidates=np.flatnonzero((coords[:,0]>=left)&(coords[:,0]<=right)&(coords[:,1]>=bottom)&(coords[:,1]<=top))
        inside=shapely.intersects_xy(geometry,coords[candidates,0],coords[candidates,1])
        for idx in candidates[inside]:matches[int(idx)].append(code)
    return [(m[0],'MATCHED') if len(m)==1 else ('','UNMATCHED' if not m else 'AMBIGUOUS') for m in matches]


def acquire(cache):
    """Anonymous official portal download; existing valid ZIP is never redownloaded."""
    import shutil
    import urllib.request
    import urllib.parse
    from http.cookiejar import CookieJar
    target=cache/'kr-public'/'sgis-admin-2025.zip'
    if target.exists():
        with zipfile.ZipFile(target) as z:
            if not any('bnd_sigungu' in n and n.endswith('.shp') for n in z.namelist()):raise ValueError('Wrong boundary ZIP')
        return target
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
    def request(url,fields=None):
        return opener.open(urllib.request.Request(url,data=urllib.parse.urlencode(fields).encode() if fields else None,
            headers={'User-Agent':'Mozilla/5.0','Referer':BOUNDARY_URL,'X-Requested-With':'XMLHttpRequest'}),timeout=180)
    page=request(BOUNDARY_URL).read().decode()
    detail=re.search(r"fn_fileDataDown\('15129688',\s*'([^']+)'",page)
    if not detail:raise ValueError('No public boundary download button')
    info=json.load(request('https://www.data.go.kr/tcs/dss/selectFileDataDownload.do',dict(publicDataDetailPk=detail[1],publicDataPk='15129688',atchFileId='',fileDetailSn='1',publicDataTyCode='PR0051')))
    if info.get('status') is not True:raise ValueError('Boundary download not authorized')
    check=json.load(request('https://www.data.go.kr/cmm/cmm/check-limit.json',dict(atchFileId=info['atchFileId'],fileDetailSn=info['fileDetailSn'])))
    if check.get('needCaptcha') is not False:raise ValueError('CAPTCHA required; stop')
    url='https://www.data.go.kr/cmm/cmm/fileDownload.do?'+urllib.parse.urlencode(dict(atchFileId=info['atchFileId'],fileDetailSn=info['fileDetailSn']))
    target.parent.mkdir(parents=True,exist_ok=True); temp=target.with_suffix('.zip.part')
    try:
        with request(url) as response,temp.open('wb') as out:shutil.copyfileobj(response,out)
        with zipfile.ZipFile(temp) as z:
            if z.testzip():raise ValueError('Boundary ZIP CRC failed')
        temp.replace(target)
    finally:temp.unlink(missing_ok=True)
    return target


def build(cache=HERE/'.cache'):
    import duckdb
    import shapefile
    from pyproj import CRS, Transformer
    from shapely.geometry import shape
    import shapely
    cache=Path(cache); archive=cache/'kr-public'/'sgis-admin-2025.zip'
    normalized=cache/'tims-monthly-safe.csv'
    members=[]; polygons=[]; names={}; repaired=[]
    with zipfile.ZipFile(archive) as z:
        stem=next(n[:-4] for n in z.namelist() if '/bnd_sigungu_' in n and n.endswith('.shp'))
        raw={ext:z.read(stem+ext) for ext in ('.shp','.dbf','.prj')}
        crs=CRS.from_wkt(raw['.prj'].decode('utf-8'))
        # Official .prj uses ESRI:102080 naming (Korea_2000). Compare the
        # actual GRS80 UTM-K projection, not authority aliases, and transform explicitly.
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            if crs.to_dict()!=CRS.from_epsg(5179).to_dict():raise ValueError('Boundary projection differs from GRS80 UTM-K; stop')
        grid_to_boundary=Transformer.from_crs(5179,crs,always_xy=True)
        for ext,data in raw.items():members.append(dict(name=stem+ext,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
        reader=shapefile.Reader(shp=io.BytesIO(raw['.shp']),dbf=io.BytesIO(raw['.dbf']),encoding='utf8')
        for rec in reader.iterShapeRecords():
            prop=rec.record.as_dict(); code=prop['SIGUNGU_CD']; names[code]=prop['SIGUNGU_NM']
            if prop['BASE_DATE']!='20250630':raise ValueError('Unexpected SGIS boundary date')
            geom=shape(rec.shape.__geo_interface__)
            if not geom.is_valid:geom=shapely.make_valid(geom);repaired.append(code)
            polygons.append((code,geom))
        sido_stem=next(n[:-4] for n in z.namelist() if '/bnd_sido_' in n and n.endswith('.dbf'))
        sido_dbf=z.read(sido_stem+'.dbf')
        members.append(dict(name=sido_stem+'.dbf',bytes=len(sido_dbf),sha256=hashlib.sha256(sido_dbf).hexdigest()))
        sido_reader=shapefile.Reader(dbf=io.BytesIO(sido_dbf),encoding='utf8')
        sido_names={r.as_dict()['SIDO_CD']:r.as_dict()['SIDO_NM'] for r in sido_reader.records()}
    db=duckdb.connect()
    counts=db.execute(f'SELECT grid,sum(trips)::BIGINT FROM read_csv_auto({sql_path(normalized)}) GROUP BY grid ORDER BY grid').fetchall()
    if any(n<0 for _,n in counts):raise ValueError('Negative TIMS trip count')
    centers=[grid_center(g) for g,_ in counts]
    boundary_centers=[grid_to_boundary.transform(x,y) for x,y in centers]
    assigned=spatial_assign(boundary_centers,polygons)
    rows=[dict(grid=g,center_e=x,center_n=y,sgis_sigungu=code,sigungu_name=names.get(code,''),status=status,trips=n)
          for (g,n),(x,y),(code,status) in zip(counts,centers,assigned)]
    totals=Counter(); unmapped=Counter(); statuses=Counter()
    for r in rows:
        statuses[r['status']]+=1
        if r['status']=='MATCHED':totals[r['sgis_sigungu']]+=r['trips']
        else:unmapped[r['status']]+=r['trips']
    # SGIS codes and MOIS codes are different systems. Join the required target
    # regions by unique (official province name, official district name), never proximity.
    mapping_path=cache/'kr-public'/'point-regions.json'
    points=json.loads(mapping_path.read_text(encoding='utf-8'))['points']
    crosswalk={}
    for p in points:
        mois=p['h_code'][:5]+'000'
        matches=[code for code,name in names.items() if name==p['sigungu'] and sido_names.get(code[:2])==p['sido']]
        if len(matches)!=1:raise ValueError('Nonunique official province/district name crosswalk: '+str(p))
        if mois in crosswalk and crosswalk[mois]!=matches[0]:raise ValueError('Inconsistent point region mapping')
        crosswalk[mois]=matches[0]
    legacy=HERE.parent.parent/'taximate-matching-sim'/'realdata'/'data'/'korea_points.json'
    cached=json.loads(legacy.read_text(encoding='utf-8'))
    row_by_grid={r['grid']:r for r in rows}
    nominal={p['id']:p for p in points}
    transformer=Transformer.from_crs(4326,5179,always_xy=True)
    deviations=[]; disagreements=[]
    for p in cached:
        if p['id']==0:continue
        r=row_by_grid[p['grid']]
        if r['status']!='MATCHED':raise ValueError('Cached grid not uniquely in boundary: '+p['grid'])
        if r['sgis_sigungu'] not in crosswalk.values():raise ValueError('Cached grid center outside known point regions; stop: '+p['grid'])
        x,y=transformer.transform(p['lon'],p['lat'])
        deviation=((x-r['center_e'])**2+(y-r['center_n'])**2)**.5
        deviations.append(deviation)
        if abs(x-r['center_e'])>250.01 or abs(y-r['center_n'])>250.01:
            raise ValueError('Cached point lies outside its decoded 500m grid')
        expected=crosswalk[nominal[p['id']]['h_code'][:5]+'000']
        if expected!=r['sgis_sigungu']:disagreements.append(dict(id=p['id'],point_sgis=expected,grid_center_sgis=r['sgis_sigungu']))
    target=cache/'kr-public'/'tims-regions.csv'
    with target.open('w',encoding='utf-8',newline='') as out:
        writer=csv.DictWriter(out,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    # Inspect every original member header, rather than assuming the first month's schema.
    with zipfile.ZipFile(cache/'kr-public'/'tims_2025.zip') as z:
        columns={n:next(csv.reader(io.StringIO(z.open(n).readline().decode('cp949').strip())))
                 for n in z.namelist() if n.endswith('.csv')}
    result=dict(generated_at=datetime.now(timezone.utc).isoformat(),boundary_date='2025-06-30',
        attribution='출처: 국가데이터처(통계청) SGIS, 2025년 2분기 센서스용 행정구역 경계',
        original_tims_columns=columns,original_has_admin_region=False,
        coordinate_rule='EPSG:5179 (UTM-K, GRS80), origin E=700000,N=1300000; 가나다... 100km prefix; TIMS three-digit column/row are 500m indices 000..199, center adds 250m. All cached point coordinates round-trip inside their decoded 500m cells; points were sampled inside grids, not at centers.',
        coordinate_sources=['https://www.data.go.kr/data/15165682/fileData.do',GRID_SOURCE_URL],
        assignment_rule='Unique point-in-polygon/intersects of decoded grid center with official SGIS SIGUNGU polygons in EPSG:5179. No nearest-point or nearest-polygon fallback. Unmatched/ambiguous centers remain unassigned; cached unmatched/ambiguous grids stop execution.',
        boundary_crs_wkt=crs.to_wkt(),boundary_crs_authority=crs.to_authority(),
        projection_note='Official PRJ ESRI:102080 has the same GRS80 UTM-K projection parameters as EPSG:5179; pyproj transforms explicitly. Authority names are not silently equated.',
        boundary_members=members,geometry_repairs=repaired,grid_counts=dict(statuses),unassigned_trips=dict(unmapped),
        national_trips=sum(r['trips'] for r in rows),sgis_region_trips=dict(sorted(totals.items())),
        sgis_region_names=names,mois_to_sgis=crosswalk,
        crosswalk_rule='Unique (SIDO_NM,SIGUNGU_NM) in official boundary DBFs equals cached Kakao (sido,sigungu); administrative MOIS prefix from h_code. No inference from numerical code similarity.',
        cached_point_max_distance_from_center_m=max(deviations),cached_point_vs_grid_region_disagreements=disagreements,
        limitations=['격자 중심점 한 곳의 소속으로 격자 전체를 배정하므로 경계 걸친 격자의 실제 승차 위치를 알 수 없다.',
            'SGIS 센서스 경계와 행정안전부 실제 행정 경계·TIMS GPS의 차이가 남는다. 미일치 격자를 가까운 지역으로 보충하지 않았다.',
            '2025Q2 한 시점의 경계를 연간 TIMS에 적용했다. 전국의 경계 밖·다중 소속 격자는 상태와 영업건수를 공개한다.'],
        sources=[file_manifest(archive,BOUNDARY_URL),file_manifest(normalized),file_manifest(mapping_path),file_manifest(legacy)],
        output=file_manifest(target),tool_sha256=file_manifest(Path(__file__))['sha256'])
    for path in (cache/'kr-public'/'tims-region-MANIFEST.json',HERE/'reports'/'tims-region-MANIFEST.json'):
        path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(grid_counts=result['grid_counts'],unassigned_trips=result['unassigned_trips'],mois_to_sgis=crosswalk,
                          cached_point_max_distance_from_center_m=max(deviations),disagreements=disagreements),ensure_ascii=False),flush=True)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--cache',type=Path,default=HERE/'.cache');p.add_argument('--download',action='store_true')
    args=p.parse_args()
    if args.download:acquire(args.cache)
    build(args.cache)
