"""Grid based C7 routing; OSRM table chunks, no synthetic or fallback legs."""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import csv
import hashlib
import json
import math
from pathlib import Path
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
DEFAULT = HERE/'.cache'/'osrm'
HUB = dict(id=0,lat=37.874,lon=127.205,region_code='41650000')  # C7~C11 legacy hub: Pocheon downtown, about 4.4km east of campus
CAMPUS_HUB = dict(id=0,lat=37.870685,lon=127.156122,region_code='41650000')  # 대진대 정문(카카오 로컬, 호국로 1007)
OSM = dict(provider='Geofabrik South Korea / OpenStreetMap contributors',
    downloaded='2026-11-20',sha256='7fb2770e9886faab3aeda88567d9d2ce057cf548f7d01b924f1a24180058ab35',
    image='ghcr.io/project-osrm/osrm-backend:v5.27.1',algorithm='MLD',profile='car',
    attribution='OpenStreetMap contributors, ODbL; https://www.openstreetmap.org/copyright')

def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(4*1024*1024),b''): h.update(b)
    return h.hexdigest()

def artifact(path):
    return dict(path=str(Path(path).resolve()),sha256=digest(path),bytes=Path(path).stat().st_size)

def save(path,value):
    path=Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(path.suffix+'.part')
    tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    tmp.replace(path)

def read_csv(path,delimiter=','):
    with Path(path).open(encoding='utf-8-sig',newline='') as f:
        return list(csv.DictReader(f,delimiter=delimiter))

def write_csv(path,rows,fields=None,delimiter=','):
    with Path(path).open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields or list(rows[0]),delimiter=delimiter)
        w.writeheader(); w.writerows(rows)

class OSRM:
    def __init__(self,url='http://localhost:5000'):
        # Windows can spend ~2s trying IPv6 localhost before IPv4 fallback on
        # every request. Same local server, explicit IPv4 transport only.
        self.url=url.rstrip('/').replace('http://localhost:', 'http://127.0.0.1:')
    def table(self,sources,targets):
        points=sources+targets
        coords=';'.join(f"{p['lon']:.8f},{p['lat']:.8f}" for p in points)
        query=urllib.parse.urlencode(dict(sources=';'.join(map(str,range(len(sources)))),
            destinations=';'.join(map(str,range(len(sources),len(points)))),annotations='distance,duration'))
        try:
            with urllib.request.urlopen(self.url+'/table/v1/driving/'+coords+'?'+query,timeout=30) as f:
                result=json.load(f)
        except Exception:
            raise RuntimeError('OSRM unreachable or table request failed; start local MLD server and rerun') from None
        if result.get('code')!='Ok': raise ValueError('OSRM table error: '+str(result.get('code')))
        return result

def table_rows(client,sources,targets):
    data=client.table(sources,targets); snaps={}; rows=[]
    for pts,key in ((sources,'sources'),(targets,'destinations')):
        if len(data[key])!=len(pts): raise ValueError('OSRM waypoint shape mismatch')
        for p,w in zip(pts,data[key]):
            d=float(w['distance'])
            if not math.isfinite(d) or d<0: raise ValueError('Invalid snap distance')
            snaps[p['id']]=d
    for i,s in enumerate(sources):
        for j,t in enumerate(targets):
            d=data['distances'][i][j]; sec=data['durations'][i][j]
            if d is None or sec is None: raise ValueError(f"No route {s['id']}->{t['id']}; no imputation")
            if not all(math.isfinite(v) and v>=0 for v in (d,sec)): raise ValueError('Invalid route')
            rows.append(dict(i=s['id'],j=t['id'],km=d/1000,seconds=sec))
    return rows,snaps

def select_grids(points,flows,coverage=.99,max_km=60):
    """Minimal descending-weight prefix covering 99% of service eligible flow.

    Regions with no in-range center are excluded; in partially served regions,
    out-of-range grids are excluded without reallocating their TIMS mass.
    """
    if not 0<coverage<=1 or max_km<=0 or sum(flows.values())<=0: raise ValueError('Invalid selection')
    totals=Counter(); nearest={}; total=sum(flows.values())
    for p in points:
        totals[p['region_code']]+=p['trips']
        if p.get('solo_km') is not None:
            c=p['region_code']; nearest[c]=min(nearest.get(c,math.inf),p['solo_km'])
    eligible=[dict(p,weight=flows.get(p['region_code'],0)/total*p['trips']/totals[p['region_code']])
        for p in points if p['trips']>0 and p.get('solo_km') is not None and p['solo_km']<=max_km
        and flows.get(p['region_code'],0)>0]
    eligible.sort(key=lambda p:(-p['weight'],p['grid']))
    available=sum(p['weight'] for p in eligible); selected=[]; represented=0.
    for p in eligible:
        if represented>=coverage*available-1e-14: break
        selected.append(p); represented+=p['weight']
    if not selected: raise ValueError('No eligible grids')
    for p in selected: p['probability']=p['weight']/represented
    table=[]
    for c,flow in sorted(flows.items()):
        kept=[p for p in selected if p['region_code']==c]; trips=sum(p['trips'] for p in kept)
        share=flow/total; retained=sum(p['weight'] for p in kept)
        table.append(dict(code=c,flow_share=share,point_count=len(kept),represented_grid_trips=trips,
            tims_total_trips=totals[c],tims_coverage=trips/totals[c] if totals[c] else 0,
            represented_share=retained,lost_share=share-retained,simulated_share=retained/represented,
            nearest_solo_km=nearest.get(c),exclusion='beyond_service_range' if nearest.get(c,math.inf)>max_km else None))
    return selected,dict(candidate_points=len(selected),unique_grids=len(selected),
        represented_flow_share=represented,lost_destination_share=1-represented,
        eligible_flow_share=available,retained_eligible_share=represented/available,
        service_excluded_share=1-available,tail_excluded_share=available-represented,
        excluded_regions=[r for r in table if r['exclusion']],region_coverage=table,
        represented_regions=[r for r in table if r['point_count']],
        pocheon_within_city_share=flows.get('41650000',0)/total,
        pocheon_simulated_share=sum(p['probability'] for p in selected if p['region_code']=='41650000'),
        destination_weight_rule='home region share * grid TIMS trips / ALL region TIMS trips',
        coverage_rule='minimal prefix covers >=99% of service eligible original weights; also report national share',
        service_rule='60km hub road distance per grid; regions with no eligible grid excluded; regional day-trip scope assumption',
        resolution='TIMS 500m center; no jitter because matrix supports centers only',
        point_probabilities=[dict(id=p['id'],code=p['region_code'],probability=p['probability']) for p in selected])

def universe(cache,crosswalk=None):
    """Crosswalk CSV is full MOIS-to-SGIS relation, not legacy point coverage.

    Require every nonzero home-flow region to be explicitly resolved. A region
    may map to multiple SGIS polygons (code changes); a polygon to one region.
    """
    from pyproj import Transformer
    public=Path(cache)/'kr-public'
    manifest=json.loads((public/'tims-region-MANIFEST.json').read_text(encoding='utf-8'))
    if digest(public/'tims-regions.csv')!=manifest['output']['sha256']: raise ValueError('TIMS region hash mismatch')
    summary=json.loads((public/'living-move-summary.json').read_text(encoding='utf-8'))
    flows=Counter()
    for r in summary['destination_home']: flows[r['d_code'][:5]+'000']+=r['cnt']
    mapping=defaultdict(set); canonical={}
    if crosswalk is None and (public/'livingmove-sgis-crosswalk.csv').exists():
        crosswalk=public/'livingmove-sgis-crosswalk.csv'
    if crosswalk:
        for r in read_csv(crosswalk):
            # A reorganization is a union on BOTH sides. Never assign the
            # same old polygon's TIMS trips separately to each new district.
            c=r.get('group') or r.get('canonical') or r['mois']
            if r['mois'] in canonical and canonical[r['mois']]!=c:
                raise ValueError('Inconsistent canonical MOIS region')
            canonical[r['mois']]=c; mapping[r['mois']].add(r['sgis'])
    else:
        # Existing relation is incomplete: never silently keep only its 8 regions.
        for c,s in manifest['mois_to_sgis'].items(): mapping[c].add(s)
    missing=sorted(c for c,v in flows.items() if v>0 and c not in mapping)
    if missing:
        raise ValueError('Full official MOIS/SGIS crosswalk required (--crosswalk CSV: mois,sgis); unresolved '+','.join(missing))
    reverse={}; merged=Counter()
    for c,v in flows.items(): merged[canonical.get(c,c)]+=v
    for c,ss in mapping.items():
        for s in ss:
            if s not in manifest['sgis_region_names']: raise ValueError('Unknown SGIS code: '+s)
            target=canonical.get(c,c)
            if s in reverse and reverse[s]!=target: raise ValueError('Ambiguous boundary; aggregate changed MOIS regions explicitly via canonical column')
            reverse[s]=target
    transform=Transformer.from_crs(5179,4326,always_xy=True); points=[]
    for r in read_csv(public/'tims-regions.csv'):
        if r['status']!='MATCHED' or r['sgis_sigungu'] not in reverse or int(r['trips'])<=0: continue
        lon,lat=transform.transform(float(r['center_e']),float(r['center_n']))
        points.append(dict(grid=r['grid'],trips=int(r['trips']),region_code=reverse[r['sgis_sigungu']],
            region_name=r['sigungu_name'],lat=lat,lon=lon))
    points.sort(key=lambda p:p['grid'])
    for i,p in enumerate(points,1): p['id']=i
    missing_grids=set(merged)-{p['region_code'] for p in points}
    if missing_grids: raise ValueError('No positive TIMS grids for flow regions: '+','.join(sorted(missing_grids)))
    return points,dict(merged),summary

def build(cache=HERE/'.cache',output=DEFAULT,url='http://localhost:5000',chunk=40,crosswalk=None,workers=4,hub=None):
    hub=dict(HUB if hub is None else hub)
    if not 1<=chunk<=50: raise ValueError('chunk must be 1..50: <=100 coordinates per table')
    if not 1<=workers<=8: raise ValueError('workers must be 1..8')
    output=Path(output); output.mkdir(parents=True,exist_ok=True)
    if crosswalk is None:
        crosswalk=Path(cache)/'kr-public'/'livingmove-sgis-crosswalk.csv'
    points,flows,summary=universe(cache,crosswalk); client=OSRM(url)
    # Legacy hub keeps its original chunk identity; any other hub gets its own chunk cache.
    identity=hashlib.sha256(json.dumps([points,OSM,url] if hub==HUB else [points,OSM,url,hub],sort_keys=True).encode()).hexdigest()
    chunks=output/'chunks'/identity; chunks.mkdir(parents=True,exist_ok=True)
    from pyproj import Geod
    geod=Geod(ellps='WGS84'); snaps={}
    for i in range(0,len(points),chunk):
        # A WGS84 shortest surface path >60km cannot be a <=60km road
        # route. No synthetic route is written for these pre-excluded cells.
        part=[]
        for p in points[i:i+chunk]:
            lower=geod.inv(hub['lon'],hub['lat'],p['lon'],p['lat'])[2]/1000
            p.update(geodesic_lower_bound_km=lower,solo_km=None,solo_seconds=None)
            if lower<=60: part.append(p)
        if not part: continue
        path=chunks/f'hub-{i}.json'
        if path.exists(): data=json.loads(path.read_text(encoding='utf-8'))
        else:
            rows,snap=table_rows(client,[hub],part); data=dict(rows=rows,snaps=snap); save(path,data)
        snaps.update({int(k):v for k,v in data['snaps'].items()})
        for p,r in zip(part,data['rows']): p.update(solo_km=r['km'],solo_seconds=r['seconds'])
    selected,coverage=select_grids(points,flows)
    save(output/'destinations.json',dict(hub=hub,points=selected,coverage=coverage,
        hours=summary['hours_home'],days=summary['days'],age_check=summary['age_check']))
    nodes=[hub]+selected; matrix=output/'raw_matrix.tsv'; tmp=matrix.with_suffix('.tsv.part')
    def fetch_pair(position):
        i,j=position; ss=nodes[i:i+chunk]; tt=nodes[j:j+chunk]
        key=hashlib.sha256(json.dumps([ss,tt],sort_keys=True).encode()).hexdigest()
        path=chunks/(key+'.json')
        if path.exists(): return json.loads(path.read_text(encoding='utf-8'))
        rows,snap=table_rows(client,ss,tt); data=dict(rows=rows,snaps=snap); save(path,data)
        return data
    with tmp.open('w',encoding='utf-8',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=['i','j','km','seconds'],delimiter='\t'); writer.writeheader()
        positions=((i,j) for i in range(0,len(nodes),chunk) for j in range(0,len(nodes),chunk))
        # Ordered writes keep sampling reproducible while at most a few local
        # OSRM requests execute concurrently. Files are unique per chunk.
        with ThreadPoolExecutor(max_workers=workers) as executor:
            for data in executor.map(fetch_pair,positions):
                writer.writerows(data['rows']); snaps.update({int(k):v for k,v in data['snaps'].items()})
    tmp.replace(matrix)
    write_csv(output/'snaps.csv',[dict(id=p['id'],grid=p.get('grid','hub'),snap_m=snaps[p['id']],
        over_300m=snaps[p['id']]>300) for p in nodes])
    inputs=[artifact(Path(cache)/p) for p in ('kr-public/tims-regions.csv',
        'kr-public/tims-region-MANIFEST.json','kr-public/living-move-summary.json')]
    if crosswalk and Path(crosswalk).exists(): inputs.append(artifact(crosswalk))
    crosswalk_tool=HERE/'sgis_mois_crosswalk.py'
    if crosswalk_tool.exists(): inputs.append(artifact(crosswalk_tool))
    save(output/'MANIFEST.json',dict(status='RAW_MATRIX_COMPLETE',osm=OSM,coverage=coverage,inputs=inputs,
        files=[artifact(output/p) for p in ('destinations.json','raw_matrix.tsv','snaps.csv')],
        snap_over_300m=sum(snaps[p['id']]>300 for p in selected),all_ordered_pairs=len(nodes)**2,
        tool=artifact(Path(__file__))))
    print(json.dumps(coverage,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache',type=Path,default=HERE/'.cache'); p.add_argument('--output',type=Path,default=DEFAULT)
    p.add_argument('--url',default='http://localhost:5000'); p.add_argument('--chunk',type=int,default=40)
    p.add_argument('--crosswalk',type=Path)
    p.add_argument('--workers',type=int,default=4)
    a=p.parse_args(); build(a.cache,a.output,a.url,a.chunk,a.crosswalk,a.workers)
