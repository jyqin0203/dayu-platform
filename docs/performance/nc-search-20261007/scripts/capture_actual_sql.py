"""Capture only generated SELECT text on private DB after timing runs; logging is off during benchmarks."""
from pathlib import Path
import json,subprocess,http.client,urllib.parse,re,argparse
parser=argparse.ArgumentParser();parser.add_argument('--label',default='actual-sql-plans');args=parser.parse_args()
out=Path(__file__).resolve().parents[1];r=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007');cmd=['mariadb','--defaults-file='+str(r/'config/client.cnf'),'--batch','--raw','--skip-column-names']
assert not (out/f'results/{args.label}.json').exists(), 'Use a new --label for plan capture'
def sql(q):return subprocess.run(cmd,input=q,capture_output=True,text=True,check=True).stdout
assert sql('SELECT @@port;').strip()=='13307'
log=r/'logs/query-diagnostic.log'
sql("SET GLOBAL general_log=OFF; SET GLOBAL log_output='FILE'; SET GLOBAL general_log_file='"+str(log)+"';")
results=[]
def pairs(values):
 d={}
 for k,v in values:
  key=k;i=2
  while key in d:key=k+'_duplicate'+str(i);i+=1
  d[key]=v
 return d
for case in json.loads((out/'queries.json').read_text()):
 start=log.stat().st_size if log.exists() else 0
 sql('SET GLOBAL general_log=ON;')
 try:
  c=http.client.HTTPConnection('127.0.0.1',18081,timeout=10);c.request('GET','/api/v1/scientific-assets?'+urllib.parse.urlencode(case['params']));res=c.getresponse();res.read();assert res.status==200;c.close()
 finally:sql('SET GLOBAL general_log=OFF;')
 with log.open() as f:f.seek(start);text=f.read()
 found={}
 for line in text.splitlines():
  if 'Query\tSELECT a.* FROM data_assets a' in line:found['list']=line.split('Query\t',1)[1]
  if 'Query\tSELECT COUNT(*) FROM data_assets a' in line:found['count']=line.split('Query\t',1)[1]
 assert set(found)=={'list','count'}, 'Expected generated SQL not captured'
 for kind,q in found.items():
  raw=sql('USE dayu_perf; EXPLAIN FORMAT=JSON '+q+';');runs=[sql('USE dayu_perf; ANALYZE FORMAT=JSON '+q+';') for _ in range(3)]
  results.append({'case':case['name'],'kind':kind,'sql':q,'explainRaw':raw,'analyzeRawThreeRuns':runs,'explain':json.loads(raw,object_pairs_hook=pairs),'analyzeThreeRuns':[json.loads(x,object_pairs_hook=pairs) for x in runs]})
(out/f'results/{args.label}.json').write_text(json.dumps(results,indent=2));print('Captured exact HTTP-generated SQL and 3 actual plan executions each; general log disabled.')
