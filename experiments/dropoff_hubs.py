"""C9 real hub acquisition, weighted network p-median and reproducible inputs."""
from pathlib import Path
import csv, hashlib, json, math, os, sys, urllib.parse, urllib.request
import numpy as np
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0,str(Path(__file__).resolve().parent))
from osrm_matrix import OSRM, save, digest, table_rows, write_csv
HERE=Path(__file__).resolve().parent
CACHE=HERE/'.cache'/'hubs'

def pmedian(distances, weights, k):
    """Deterministic greedy BUILD plus best improving one-swap (local p-median)."""
    a=np.asarray(distances,float);w=np.asarray(weights,float)
    if a.ndim!=2 or len(w)!=a.shape[0] or not 0<k<=a.shape[1] or np.any(a<0) or not np.isfinite(a).all() or np.any(w<0) or sum(w)<=0:raise ValueError('Invalid p-median input')
    selected=[];best=np.full(len(w),np.inf)
    for _ in range(k):
        scores=(np.minimum(a,best[:,None])*w[:,None]).sum(axis=0)
        scores[selected]=np.inf;j=int(np.argmin(scores));selected.append(j);best=np.minimum(best,a[:,j])
    while True:
        current=float(best@w);winner=None;score=current
        for old in selected:
            others=[j for j in selected if j!=old]
            base=np.min(a[:,others],axis=1) if others else np.full(len(w),np.inf)
            scores=(np.minimum(a,base[:,None])*w[:,None]).sum(axis=0);scores[selected]=np.inf
            new=int(np.argmin(scores))
            if scores[new]<score-1e-9:score=float(scores[new]);winner=(old,new)
        if winner is None:break
        selected[selected.index(winner[0])]=winner[1];best=np.min(a[:,selected],axis=1)
    selected.sort();return selected,float(np.min(a[:,selected],axis=1)@w)

def walk_km(a,b):
    lat1,lat2=map(math.radians,(a['lat'],b['lat']));dl=math.radians(b['lon']-a['lon']);dt=lat2-lat1
    return 1.3*6371.0088*2*math.asin(min(1,math.sqrt(math.sin(dt/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin(dl/2)**2)))

def acquire():
    CACHE.mkdir(parents=True,exist_ok=True)
    dest=json.loads((HERE/'.cache/osrm/destinations.json').read_text(encoding='utf-8'));points=dest['points']
    env={}
    for line in (HERE.parent/'.env').read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.startswith('#'):
            k,v=line.split('=',1);env[k]=v.strip().strip('\"').strip("'")
    key=env.get('KAKAO_REST_KEY') or env.get('KAKAO_REST_API_KEY')
    if not key:raise RuntimeError('Kakao REST key missing')
    receipts=[];docs={}
    def query(endpoint,params):
        url='https://dapi.kakao.com/v2/local/search/'+endpoint+'.json?'+urllib.parse.urlencode(params)
        name=hashlib.sha256(url.encode()).hexdigest()+'.json';path=CACHE/name
        if not path.exists():
            request=urllib.request.Request(url,headers={'Authorization':'KakaoAK '+key})
            with urllib.request.urlopen(request,timeout=30) as f:raw=f.read()
            path.write_bytes(raw)
        data=json.loads(path.read_text(encoding='utf-8'));receipts.append(dict(url=url,path=str(path),sha256=digest(path)))
        for d in data['documents']:docs[d['id']]=d
        return data
    bounds=[min(p['lon'] for p in points),min(p['lat'] for p in points),max(p['lon'] for p in points),max(p['lat'] for p in points)]
    def rect_search(endpoint,base,box,depth=0):
        rect=','.join(map(str,box));data=query(endpoint,dict(base,rect=rect,size=15,page=1))
        if data['meta']['total_count']>45:
            if depth>=10:raise RuntimeError('Kakao coverage truncated')
            x,y,X,Y=box
            if X-x>Y-y:
                m=(x+X)/2;children=[(x,y,m,Y),(m,y,X,Y)]
            else:
                m=(y+Y)/2;children=[(x,y,X,m),(x,m,X,Y)]
            for child in children:rect_search(endpoint,base,child,depth+1)
        else:
            page=1
            while not data['meta']['is_end']:
                page+=1;data=query(endpoint,dict(base,rect=rect,size=15,page=page))
    rect_search('category',dict(category_group_code='SW8'),bounds)
    rect_search('keyword',dict(query='버스터미널'),bounds)
    for keyword in ('포천 버스터미널','소흘 버스터미널','포천 시내 버스','소흘 송우리 버스'):
        data=query('keyword',dict(query=keyword,size=15,page=1));page=1
        while not data['meta']['is_end'] and page<3:
            page+=1;data=query('keyword',dict(query=keyword,size=15,page=page))
    # Restrict to returned transit nodes, including actual bus stops in Pocheon/Soheul downtown.
    candidates=[]
    for d in sorted(docs.values(),key=lambda d:int(d['id'])):
        if not (d['category_group_code']=='SW8' or any(s in d['category_name'] for s in ('터미널','버스정류장'))):continue
        lon,lat=float(d['x']),float(d['y'])
        if not bounds[0]<=lon<=bounds[2] or not bounds[1]<=lat<=bounds[3]:continue
        candidates.append(dict(id=2000000+len(candidates),kakao_id=d['id'],name=d['place_name'],lon=lon,lat=lat,category=d['category_name']))
    if len(candidates)<20:raise RuntimeError('Fewer than 20 real candidates')
    save(CACHE/'search-manifest.json',dict(bounds=bounds,queries=receipts,candidates=candidates,coverage='recursive rectangles, split if >45, pages until is_end; keyword downtown limited to API 45 results'))
    print('candidates',len(candidates),flush=True)
    nodes=[dest['hub']]+points;client=OSRM('http://127.0.0.1:5000');chunks=CACHE/'tables';chunks.mkdir(exist_ok=True)
    def table(ss,tt):
        sig=hashlib.sha256(json.dumps([ss,tt],sort_keys=True).encode()).hexdigest();path=chunks/(sig+'.json')
        if path.exists():return json.loads(path.read_text(encoding='utf-8'))['rows']
        rows,snaps=table_rows(client,ss,tt);save(path,dict(rows=rows,snaps=snaps));return rows
    # p-median demand grid -> candidate, directed network distance (as requested).
    positions=[(points[i:i+40],candidates[j:j+40]) for i in range(0,len(points),40) for j in range(0,len(candidates),40)]
    distance=np.empty((len(points),len(candidates)));pi={p['id']:i for i,p in enumerate(points)};hi={p['id']:i for i,p in enumerate(candidates)}
    with ThreadPoolExecutor(max_workers=4) as ex:
        for rows in ex.map(lambda z:table(*z),positions):
            for r in rows:distance[pi[r['i']],hi[r['j']]]=r['km']
    weights=np.array([p['probability'] for p in points]);sets={};union=set()
    for k in (5,10,20):
        chosen,obj=pmedian(distance,weights,k);union.update(chosen);assignment=np.argmin(distance[:,chosen],axis=1)
        hubs=[]
        for q,j in enumerate(chosen):
            assigned=[p for i,p in enumerate(points) if assignment[i]==q]
            hubs.append(dict(candidates[j],demand_covered=sum(p['probability'] for p in assigned),assigned_grids=len(assigned)))
        sets[str(k)]=dict(hubs=hubs,weighted_network_km=obj,grid_assignments={str(p['id']):candidates[chosen[int(assignment[i])]]['id'] for i,p in enumerate(points)})
    # Default smallest K whose objective is <=1.25 times K20; fixed before outcome simulation.
    default=next(k for k in (5,10,20) if sets[str(k)]['weighted_network_km']<=1.25*sets['20']['weighted_network_km'])
    selected=[candidates[i] for i in sorted(union)]
    # Both directed hub-grid tables and hub-hub; no synthetic car legs.
    positions=[(selected[i:i+40],nodes[j:j+40]) for i in range(0,len(selected),40) for j in range(0,len(nodes),40)]
    positions +=[(nodes[i:i+40],selected[j:j+40]) for i in range(0,len(nodes),40) for j in range(0,len(selected),40)]
    positions +=[(selected[i:i+40],selected[j:j+40]) for i in range(0,len(selected),40) for j in range(0,len(selected),40)]
    calibration=json.loads((HERE/'.cache/osrm/MANIFEST.json').read_text(encoding='utf-8'))['calibration']
    factor=calibration['duration_ratio']
    rows_by_key={}
    with ThreadPoolExecutor(max_workers=4) as ex:
        for rows in ex.map(lambda z:table(*z),positions):
            for r in rows:rows_by_key[(r['i'],r['j'])]=dict(r,seconds=int(math.floor(r['seconds']*factor+.5)))
    write_csv(CACHE/'matrix.tsv',[rows_by_key[key] for key in sorted(rows_by_key)],delimiter='\t')
    lookup={(r['i'],r['j']):r for r in rows_by_key.values()};allh={h['id']:h for h in selected}
    assignments=[]
    for k in (5,10,20):
        for p in points:
            h=allh[sets[str(k)]['grid_assignments'][str(p['id'])]];last=lookup[h['id'],p['id']];leg=lookup[0,h['id']]
            assignments.append(dict(k=k,grid=p['id'],hub=h['id'],lat=h['lat'],lon=h['lon'],km=leg['km'],seconds=leg['seconds'],walk_km=walk_km(h,p),last_km=last['km'],last_seconds=last['seconds']))
    write_csv(CACHE/'assignments.tsv',assignments,delimiter='\t')
    save(CACHE/'selection.json',dict(status='COMPLETED',algorithm='greedy BUILD + exhaustive improving 1-swap local p-median; no global optimum claim',default_k=default,default_rule='smallest K with weighted network distance <= 1.25 * K20',sets=sets,duration_multiplier=factor,inputs=[dict(path=str(p),sha256=digest(p)) for p in (CACHE/'matrix.tsv',CACHE/'assignments.tsv',CACHE/'search-manifest.json',HERE/'.cache/osrm/destinations.json')]))
    print('hubs ready',default,flush=True)
if __name__=='__main__':acquire()
