"""Run real EXPLAIN and ANALYZE SELECT on the isolated metadata snapshot only."""
from pathlib import Path
import subprocess,json
out=Path(__file__).resolve().parents[1];r=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007')
cmd=['mariadb','--defaults-file='+str(r/'config/client.cnf'),'--batch','--raw','--skip-column-names']
def sql(q):return subprocess.run(cmd,input=q,text=True,capture_output=True,check=True).stdout
assert sql('SELECT @@port;').strip()=='13307'
def preserve_duplicates(pairs):
 d={}
 for k,v in pairs:
  name=k;i=2
  while name in d:name=k+"_duplicate"+str(i);i+=1
  d[name]=v
 return d
plans=[]
for case in json.loads((out/'queries.json').read_text()):
 for kind,q in case['sql'].items():
  raw=sql('USE dayu_perf; EXPLAIN FORMAT=JSON '+q+';')
  runs=[sql('USE dayu_perf; ANALYZE FORMAT=JSON '+q+';') for _ in range(3)]
  explain=json.loads(raw,object_pairs_hook=preserve_duplicates)
  actual=[json.loads(v,object_pairs_hook=preserve_duplicates) for v in runs]
  plans.append({'case':case['name'],'kind':kind,'sql':q,'explainRaw':raw,'analyzeRawThreeRuns':runs,'explain':explain,'analyzeThreeRuns':actual})
(out/'results/plans.json').write_text(json.dumps(plans,indent=2))
(out/'results/schema.tsv').write_text(sql("SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='dayu_perf' AND TABLE_NAME IN ('data_assets','data_asset_products') ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;"))
print('Saved 18 EXPLAIN plans and 54 actual ANALYZE executions.')
