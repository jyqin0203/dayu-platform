"""Aggregate measured results; assert equivalent indexed business state, never invent missing rounds."""
from pathlib import Path
import json,statistics,shutil
root=Path('/tmp/dayu-index-update-20261008')
dest=Path(__file__).resolve().parents[1]/'results'
results=[]
for variant in ('a','b'):
 for round in (1,2,3):
  for phase in ('unchanged','new-half-hour'):
   path=root/f'result-{variant}-{round}-{phase}.json'
   d=json.loads(path.read_text());assert d['errors']==0
   results.append(d);shutil.copyfile(path,dest/path.name)
summary={}
for phase in ('unchanged','new-half-hour'):
 rows=[r for r in results if r['phase']==phase]
 assert len({r['businessStateSha256'] for r in rows})==1
 assert len({(r['assets'],r['links'],r['scannedFiles'],r['created']) for r in rows})==1
 out={}
 for v in ('a','b'):
  selected=[r for r in rows if r['variant']==v]
  out[v]={'seconds':[r['wallMs']/1000 for r in selected],
          'medianSeconds':statistics.median(r['wallMs']/1000 for r in selected),
          'sqlExecutions':[r['sqlExecutions'] for r in selected],
          'commits':[r['commits'] for r in selected],
          'assets':selected[0]['assets'],'links':selected[0]['links'],'created':selected[0]['created']}
 out['medianTimeReductionPct']=(1-out['b']['medianSeconds']/out['a']['medianSeconds'])*100
 out['sqlReductionPct']=(1-out['b']['sqlExecutions'][0]/out['a']['sqlExecutions'][0])*100
 summary[phase]=out
(dest/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
