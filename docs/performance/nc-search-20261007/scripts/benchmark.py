"""Fixed-snapshot closed-loop HTTP benchmark, correctness checks and MariaDB digest row counters.
No writes to business tables; runs only on guarded 13307/18081 isolated services.
"""
from pathlib import Path
import argparse,http.client,urllib.parse,json,subprocess,hashlib,time,math,concurrent.futures,random,datetime,os
p=argparse.ArgumentParser();p.add_argument('--label',default='baseline');p.add_argument('--requests',type=int,default=1000);p.add_argument('--warmup-per-case',type=int,default=200);a=p.parse_args()
out=Path(__file__).resolve().parents[1];private=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007');queries=json.loads((out/'queries.json').read_text())
assert not (out/f'results/{a.label}-summary.json').exists(), 'Use a new --label to preserve prior measurements'
cmd=['mariadb','--defaults-file='+str(private/'config/client.cnf'),'--batch','--raw','--skip-column-names']
def sql(q):return subprocess.run(cmd,input=q,text=True,capture_output=True,check=True).stdout
assert sql('SELECT @@port;').strip()=='13307'
assert a.requests>=100
snapshot=(private/'snapshot/metadata.jsonl').read_bytes();data={}
for l in snapshot.splitlines():
 v=json.loads(l);data.setdefault(v['table'],[]).append(v['row'])
products={v['id']:v['code'] for v in data['products']};links={}
for v in data['data_asset_products']:links.setdefault(v['asset_id'],[]).append(products[v['product_id']])
def iso(v):return None if v is None else datetime.datetime.fromisoformat(v).isoformat()+'Z'
def expected(q):
 p=q['params'];rows=[v for v in data['data_assets'] if v['asset_type']=='NETCDF' and v['status']=='AVAILABLE' and v['data_mode']==p['dataMode'] and p['productCode'] in links.get(v['id'],[]) and p['from']<=iso(v['valid_time'])<=p['to'] and ('cycleTime' not in p or iso(v['cycle_time'])==p['cycleTime']) and ('leadMinutes' not in p or v['lead_minutes']==p['leadMinutes'])]
 rows.sort(key=lambda v:(v['valid_time'],v['id']),reverse=True);total=len(rows);rows=rows[(p['page']-1)*p['pageSize']:p['page']*p['pageSize']]
 items=[{'assetId':v['id'],'fileName':v['file_name'],'fileSize':v['file_size'],'products':sorted(links[v['id']]),'dataMode':v['data_mode'],'cycleTime':iso(v['cycle_time']),'validTime':iso(v['valid_time']),'leadMinutes':v['lead_minutes'],'status':v['status']} for v in rows]
 return {'items':items,'page':p['page'],'pageSize':p['pageSize'],'total':total}
def canonical(v):return json.dumps(v,sort_keys=True,separators=(',',':')).encode()
expected_values={q['name']:expected(q) for q in queries}
(out/'results/expected-signatures.json').write_text(json.dumps({k:{'sha256':hashlib.sha256(canonical(v)).hexdigest(),'total':v['total'],'returned':len(v['items']),'orderedIds':[x['assetId'] for x in v['items']]} for k,v in expected_values.items()},indent=2))
def stats():
 text=sql("SELECT JSON_OBJECT('digest',DIGEST,'sql',DIGEST_TEXT,'count',COUNT_STAR,'rowsExamined',SUM_ROWS_EXAMINED,'rowsSent',SUM_ROWS_SENT,'timerPs',SUM_TIMER_WAIT,'noIndex',SUM_NO_INDEX_USED,'sortRows',SUM_SORT_ROWS) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='dayu_perf' AND DIGEST_TEXT LIKE 'SELECT %';")
 return {v['digest']:v for v in map(json.loads,text.splitlines())}
def delta(before,after):
 rows=[]
 for k,v in after.items():
  d={key:v[key]-before.get(k,{}).get(key,0) for key in ['count','rowsExamined','rowsSent','timerPs','noIndex','sortRows']}
  if d['count']:rows.append(dict(d,digest=k,sql=v['sql']))
 return rows
rows=[];summaries=[];digests=[]
def worker(case,count,record=True):
 c=http.client.HTTPConnection('127.0.0.1',18081,timeout=15);path='/api/v1/scientific-assets?'+urllib.parse.urlencode(case['params']);wanted=expected_values[case['name']];samples=[]
 try:
  for i in range(count):
   start=time.perf_counter_ns();status=0;err=None
   try:
    c.request('GET',path);res=c.getresponse();body=res.read();status=res.status;elapsed=(time.perf_counter_ns()-start)/1e6
    if status!=200:err='http-status'
    elif json.loads(body)!=wanted:err='response-mismatch'
   except Exception as e:elapsed=(time.perf_counter_ns()-start)/1e6;err=type(e).__name__;c.close()
   samples.append({'ms':elapsed,'status':status,'error':err})
 finally:c.close()
 return samples
def resource():
 d={}
 for unit in ['dayu-perf-db.service','dayu-perf-backend.service']:
  group=subprocess.check_output(['systemctl','show',unit,'-p','ControlGroup','--value'],text=True).strip()
  path=Path('/sys/fs/cgroup'+group+'/cpu.stat')
  d[unit]={l.split()[0]:int(l.split()[1]) for l in path.read_text().splitlines()}
 return d
# Global warm-up before all measured rounds; SQL buffer and JVM JIT caches are not flushed.
for _ in range(a.warmup_per_case):
 for case in queries:
  assert all(s['error'] is None for s in worker(case,1))
for round_id in range(1,4):
 sequence=[(q,c) for q in queries for c in [1,4]];random.Random(20261007+round_id).shuffle(sequence)
 for case,c in sequence:
  assert all(s['error'] is None for s in worker(case,20))
  before=stats();cpu_before=resource();start=time.perf_counter()
  with concurrent.futures.ThreadPoolExecutor(max_workers=c) as pool:
   batches=list(pool.map(lambda n:worker(case,n),[a.requests//c+(i<a.requests%c) for i in range(c)]))
  elapsed=time.perf_counter()-start;cpu_after=resource();after=stats();samples=[s for b in batches for s in b]
  tag={'round':round_id,'case':case['name'],'concurrency':c}
  for i,s in enumerate(samples):rows.append(dict(tag,request=i,**s))
  times=sorted(s['ms'] for s in samples);errors=sum(s['error'] is not None for s in samples);diff=delta(before,after)
  entry=dict(tag,requests=len(samples),errors=errors,errorRate=errors/len(samples),elapsedSeconds=elapsed,p50Ms=times[math.ceil(len(times)*.5)-1],p95Ms=times[math.ceil(len(times)*.95)-1],throughputRps=len(samples)/elapsed,total=expected_values[case['name']]['total'],returned=len(expected_values[case['name']]['items']),rowsExamined=sum(d['rowsExamined'] for d in diff),selectStatements=sum(d['count'] for d in diff),cpuDelta={unit:{k:cpu_after[unit][k]-cpu_before[unit][k] for k in cpu_after[unit]} for unit in cpu_after})
  summaries.append(entry);digests.append(dict(tag,digests=diff))
  print(json.dumps({k:entry[k] for k in ['round','case','concurrency','p95Ms','throughputRps','errors','rowsExamined']}),flush=True)
  (out/f'results/{a.label}-requests.jsonl').write_text(''.join(json.dumps(v)+'\n' for v in rows))
  (out/f'results/{a.label}-summary.json').write_text(json.dumps(summaries,indent=2))
  (out/f'results/{a.label}-sql-digests.json').write_text(json.dumps(digests,indent=2))
  assert not errors,'Benchmark failed correctness or availability checks'
print('Completed 3 rounds; every response matched independent snapshot-derived expected results.')
