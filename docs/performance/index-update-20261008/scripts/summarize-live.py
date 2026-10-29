from pathlib import Path
import json,statistics,shutil
root=Path('/tmp/dayu-index-update-20261008');dest=Path(__file__).resolve().parents[1]/'results'
rows=[]
for variant in ('a','b'):
 for round in (0,1,2,3):
  p=root/f'live-{variant}-{round}.json';d=json.loads(p.read_text());shutil.copyfile(p,dest/p.name)
  if round:
   assert all(d[k]==0 for k in ('created','updated','removed','missing','errors'))
   assert d['stableSincePrevious'];rows.append(d)
assert len({r['sourceMetadataSha256'] for r in rows})==1
assert len({r['businessStateSha256'] for r in rows})==1
assert len({(r['assetRows'],r['linkRows'],r['scannedFiles']) for r in rows})==1
summary={}
for variant in ('a','b'):
 group=[r for r in rows if r['variant']==variant]
 summary[variant]={'seconds':[r['wallMs']/1000 for r in group],
 'medianSeconds':statistics.median(r['wallMs']/1000 for r in group),
 'inventoryExclusiveMs':[r['inventoryExclusiveMs'] for r in group],
 'storeMs':[r['storeMs'] for r in group],
 'storeSharePct':[r['storeMs']/r['wallMs']*100 for r in group],
 'sqlExecutions':group[0]['sqlExecutions'],'commits':group[0]['commits'],
 'files':group[0]['scannedFiles'],'assetRows':group[0]['assetRows'],'linkRows':group[0]['linkRows']}
summary['timeReductionPct']=(1-summary['b']['medianSeconds']/summary['a']['medianSeconds'])*100
summary['sqlReductionPct']=(1-summary['b']['sqlExecutions']/summary['a']['sqlExecutions'])*100
(dest/'live-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
