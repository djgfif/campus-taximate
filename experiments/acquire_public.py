"""한국 공개자료 수집: TIMS와 서울시·KT 생활이동. 기존 캐시는 재다운로드하지 않는다."""
import argparse
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parent))
from fares import file_manifest
from livingmove import URLS
HERE=Path(__file__).resolve().parent
CACHE=HERE/'.cache'/'kr-public'


def download(url,target,fields=None):
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists(): return
    data=None; headers={'User-Agent':'Mozilla/5.0'}
    if fields is not None:
        boundary='----TaximatePublicDataBoundary'
        data=''.join('--'+boundary+'\r\nContent-Disposition: form-data; name="'+k+'"\r\n\r\n'+v+'\r\n' for k,v in fields.items())+'--'+boundary+'--\r\n'
        data=data.encode(); headers['Content-Type']='multipart/form-data; boundary='+boundary
    temp=target.with_suffix(target.suffix+'.part')
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers),timeout=180) as response,temp.open('wb') as out:
            shutil.copyfileobj(response,out)
        validate_zip(temp)
        temp.replace(target)
    finally: temp.unlink(missing_ok=True)


def validate_zip(path):
    with zipfile.ZipFile(path) as z:
        if not z.namelist() or z.testzip() is not None: raise ValueError('Invalid ZIP/CRC: '+str(path))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('selection',nargs='?',choices=['all','tims','living-move','c7'],default='all')
    p.add_argument('--cache',type=Path,default=CACHE)
    args=p.parse_args(); files=[]
    if args.selection in ('all','tims'):
        target=args.cache/'tims_2025.zip'
        if not target.exists():
            # Use the official portal download metadata; stop on CAPTCHA or permission requirements.
            url='https://www.data.go.kr/data/15165682/fileData.do'
            import re
            def request(url,fields=None):
                from urllib.parse import urlencode
                from http.cookiejar import CookieJar
                return opener.open(urllib.request.Request(url,data=urlencode(fields).encode() if fields else None,
                    headers={'User-Agent':'Mozilla/5.0','Referer':'https://www.data.go.kr/data/15165682/fileData.do','X-Requested-With':'XMLHttpRequest'}),timeout=120).read().decode()
            from http.cookiejar import CookieJar
            opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
            page=request(url); match=re.search(r"fn_fileDataDown\('15165682',\s*'([^']+)'",page)
            if not match: raise ValueError('No public TIMS download button')
            info=json.loads(request('https://www.data.go.kr/tcs/dss/selectFileDataDownload.do',dict(publicDataDetailPk=match[1],publicDataPk='15165682',atchFileId='',fileDetailSn='1',publicDataTyCode='PR0051')))
            if info.get('status') is not True: raise ValueError('TIMS download not authorized')
            checked=json.loads(request('https://www.data.go.kr/cmm/cmm/check-limit.json',dict(atchFileId=info['atchFileId'],fileDetailSn=info['fileDetailSn'])))
            if checked.get('needCaptcha') is not False: raise ValueError('CAPTCHA required; stop')
            from urllib.parse import urlencode
            address='https://www.data.go.kr/cmm/cmm/fileDownload.do?'+urlencode(dict(atchFileId=info['atchFileId'],fileDetailSn=info['fileDetailSn']))
            target.parent.mkdir(parents=True,exist_ok=True)
            temp=target.with_suffix('.zip.part')
            try:
                with opener.open(address,timeout=180) as response,temp.open('wb') as out: shutil.copyfileobj(response,out)
                validate_zip(temp);temp.replace(target)
            finally: temp.unlink(missing_ok=True)
        validate_zip(target)
        files.append(file_manifest(target,'https://www.data.go.kr/data/15165682/fileData.do'))
    if args.selection in ('all','living-move','c7'):
        dt=date(2026,10,1)
        while dt.month==10:
            if (dt.weekday()<5 and dt.day!=9) or dt.day==31:
                for kind,inf in ((3,'OA-22300'),(4,'OA-22299')):
                    name='seoul_purpose_admdong'+('3_' if kind==3 else '4_in_')+dt.strftime('%Y%m%d')+'.zip'
                    target=args.cache/'living-move'/name
                    fields=dict(infId=inf,seq=dt.strftime('%y%m%d'),infSeq='1',seqNo='')
                    download('https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?&useCache=false',target,fields)
                    validate_zip(target)
                    files.append(dict(date=dt.isoformat(),dataset=inf,**file_manifest(target,URLS[kind])))
                    print('Validated',name,flush=True)
            dt+=timedelta(days=1)
    manifest=dict(generated_at=datetime.now(timezone.utc).isoformat(),selection=args.selection,files=files,
                  raw_policy='원본은 .cache 안에만 저장. 공개 결과에는 SHA256와 비식별 집계만 포함.')
    args.cache.mkdir(parents=True,exist_ok=True)
    (args.cache/'acquisition-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__': main()
