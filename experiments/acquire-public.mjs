/** Public C5 acquisition only. No Kakao, payment API, login or desktop automation. */
import https from 'node:https';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { pipeline } from 'node:stream/promises';

const here=path.dirname(fileURLToPath(import.meta.url));
const cache=path.join(here,'.cache');
const cookies=new Map();
const events=[];
const ua='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36';
await fsp.mkdir(cache,{recursive:true});

async function request(url,{data,referer,output}={}) {
  const body=data?new URLSearchParams(data).toString():null;
  const headers={'User-Agent':ua};
  if (new URL(url).hostname==='www.data.go.kr') headers.Cookie=[...cookies].map(([k,v])=>`${k}=${v}`).join('; ');
  if (referer) headers.Referer=referer;
  if (body) Object.assign(headers,{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
    'Content-Length':Buffer.byteLength(body),'X-Requested-With':'XMLHttpRequest'});
  return new Promise((resolve,reject)=>{
    const req=https.request(url,{method:body?'POST':'GET',headers},async res=>{
      for (const entry of res.headers['set-cookie']||[]) {
        const pair=entry.split(';')[0];const sep=pair.indexOf('=');cookies.set(pair.slice(0,sep),pair.slice(sep+1));
      }
      events.push({url,method:body?'POST':'GET',status:res.statusCode});
      try {
        if (res.statusCode!==200) {res.resume();throw Error(`HTTP ${res.statusCode}: ${url}`);}
        if (output) {
          const temporary=output+'.part';
          await pipeline(res,fs.createWriteStream(temporary));
          if (/text\/html/.test(res.headers['content-type']||'')) throw Error('Expected data file, received HTML');
          await fsp.rename(temporary,output);resolve(output);
        } else {
          const chunks=[];for await (const chunk of res) chunks.push(chunk);
          resolve(Buffer.concat(chunks).toString('utf8'));
        }
      }catch(error){reject(error);}
    });
    req.setTimeout(120000,()=>req.destroy(Error('network timeout')));
    req.on('error',reject);req.end(body);
  });
}
async function manifest(file,url,extra={}) {
  const hash=createHash('sha256');for await(const block of fs.createReadStream(file))hash.update(block);
  return {file:path.relative(here,file).replaceAll('\\','/'),url,bytes:(await fsp.stat(file)).size,
    sha256:hash.digest('hex'),verified_at:new Date().toISOString(),...extra};
}
async function portal(pk,filename) {
  const pageUrl=`https://www.data.go.kr/data/${pk}/fileData.do`;
  const page=await request(pageUrl);
  const match=page.match(new RegExp(`fn_fileDataDown\\('${pk}',\\s*'([^']+)'`));
  if(!match)throw Error('No public download button for '+pk);
  const data={publicDataDetailPk:match[1],publicDataPk:pk,atchFileId:'',fileDetailSn:'1',publicDataTyCode:'PR0051'};
  const info=JSON.parse(await request('https://www.data.go.kr/tcs/dss/selectFileDataDownload.do',{data,referer:pageUrl}));
  if(info.status!==true)throw Error('Portal metadata download not authorized');
  const checked=JSON.parse(await request('https://www.data.go.kr/cmm/cmm/check-limit.json',{
    data:{atchFileId:info.atchFileId,fileDetailSn:info.fileDetailSn},referer:pageUrl}));
  if(checked.needCaptcha!==false)throw Error('CAPTCHA required: stopping this source without bypass');
  const url='https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId='+encodeURIComponent(info.atchFileId)+
    '&fileDetailSn='+encodeURIComponent(info.fileDetailSn)+'&dataNm='+encodeURIComponent(info.dataSetFileDetailInfo.dataNm);
  const output=path.join(cache,'kr-public',filename);await fsp.mkdir(path.dirname(output),{recursive:true});
  if(!fs.existsSync(output))await request(url,{referer:pageUrl,output});
  return manifest(output,url,{catalogue:pageUrl,login_required:false,needCaptcha:false});
}
const selection=process.argv[2]||'all';
const output={generated_at:new Date().toISOString(),sources:[],failures:[],requests:events};
for(const [pk,name] of [['15115859','seoul_dcall_2025.csv'],['15165682','tims_2025.zip']]) {
  if(!['all','korea',pk].includes(selection))continue;
  try {output.sources.push(await portal(pk,name));console.log('Acquired',name);}
  catch(error){output.failures.push({pk,error:error.message});console.error(pk,error.message);}
}
if(['all','nyc'].includes(selection)) {
  const url='https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page';
  const page=await request(url);await fsp.writeFile(path.join(cache,'tlc-page.html'),page);
  const months=[...new Set([...page.matchAll(/yellow_tripdata_(\d{4}-\d{2})\.parquet/g)].map(x=>x[1]))].sort();
  const latest=months.at(-1);const d=new Date(latest+'-01T00:00:00Z');d.setUTCMonth(d.getUTCMonth()-1);
  for(const month of [d.toISOString().slice(0,7),latest]) {
    const url=`https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_${month}.parquet`;
    const file=path.join(cache,`yellow_tripdata_${month}.parquet`);
    if(!fs.existsSync(file))await request(url,{output:file});
    output.sources.push(await manifest(file,url,{month}));
  }
  for(const [name,url] of [
    ['taxi_zone_lookup.csv','https://d37ci6vzurychx.cloudfront.net/misc/taxi_zone_lookup.csv'],
    ['nyc-data-dictionary.pdf','https://www.nyc.gov/assets/tlc/downloads/pdf/data_dictionary_trip_records_yellow.pdf'],
    ['nyc-tariff.html','https://www.nyc.gov/site/tlc/passengers/taxi-fare.page']]) {
    const file=path.join(cache,name);if(!fs.existsSync(file))await request(url,{output:file});
    output.sources.push(await manifest(file,url));
  }
}
await fsp.writeFile(path.join(cache,'public-download-manifest.json'),JSON.stringify(output,null,2)+'\n');
console.log(JSON.stringify(output.sources.map(x=>({file:x.file,bytes:x.bytes,sha256:x.sha256})),null,2));
if(output.failures.length)process.exitCode=1;
