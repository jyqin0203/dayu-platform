"""Aggregate all A/B/A rounds without selecting best runs; HTTP quantiles from all raw samples."""
from pathlib import Path
import json,math,gzip,collections
root=Path(__file__).resolve().parents[1];r=root/'results';labels=['ab-a-before','ab-b-indexed','ab-a-return'];data={};samples={};digests={}
for label in labels:
 data[label]=json.loads((r/f'{label}-summary.json').read_text());assert len(data[label])==54 and all(v['errors']==0 for v in data[label])
 p=r/f'{label}-requests.jsonl';raw=p.read_bytes() if p.exists() else gzip.decompress((r/f'{label}-requests.jsonl.gz').read_bytes())
 rows=[json.loads(l) for l in raw.splitlines()];assert len(rows)==54000 and all(v['error'] is None for v in rows)
 samples[label]=rows;digests[label]=json.loads((r/f'{label}-sql-digests.json').read_text())
result=[]
for q in json.loads((root/'queries.json').read_text()):
 for c in [1,4]:
  item={'case':q['name'],'concurrency':c,'variants':{}}
  for label in labels:
   s=[x for x in data[label] if x['case']==q['name'] and x['concurrency']==c];times=sorted(x['ms'] for x in samples[label] if x['case']==q['name'] and x['concurrency']==c)
   cost={}
   for g in digests[label]:
    if g['case']!=q['name'] or g['concurrency']!=c:continue
    for d in g['digests']:
     if 'FROM `data_assets`' not in d['sql']:continue
     k='count' if d['sql'].startswith('SELECT COUNT') else 'list';v=cost.setdefault(k,dict(calls=0,rows=0,timerPs=0,sortRows=0));v['calls']+=d['count'];v['rows']+=d['rowsExamined'];v['timerPs']+=d['timerPs'];v['sortRows']+=d['sortRows']
   item['variants'][label]={'requests':len(times),'p95PooledMs':times[math.ceil(len(times)*.95)-1],'p95RoundsMs':[x['p95Ms'] for x in s],'throughputRps':sum(x['requests'] for x in s)/sum(x['elapsedSeconds'] for x in s),'throughputRoundsRps':[x['throughputRps'] for x in s],'rowsPerRequest':sum(x['rowsExamined'] for x in s)/sum(x['requests'] for x in s),'sql':{k:{'rowsPerStatement':v['rows']/v['calls'],'meanServerMs':v['timerPs']/1e9/v['calls'],'sortRowsPerStatement':v['sortRows']/v['calls']} for k,v in cost.items()},'cpuThrottledUsec':sum(v.get('throttled_usec',0) for x in s for v in x['cpuDelta'].values())}
  a=item['variants'][labels[0]];b=item['variants'][labels[1]]
  item['beforeVsIndexed']={'p95ReductionPct':100*(1-b['p95PooledMs']/a['p95PooledMs']),'throughputIncreasePct':100*(b['throughputRps']/a['throughputRps']-1),'rowsReductionPct':100*(1-b['rowsPerRequest']/a['rowsPerRequest'])}
  result.append(item)
(r/'ab-comparison.json').write_text(json.dumps(result,indent=2))
lines=['| 查询 | 并发 | A前 P95/RPS | B索引 P95/RPS | A回撤 P95/RPS | A→B扫描行/请求 |','|---|---:|---|---|---|---|']
for x in result:
 vals=[x['variants'][l] for l in labels]
 fmt=lambda v:f"{v['p95PooledMs']:.2f} ms / {v['throughputRps']:.0f}"
 lines.append(f"| {x['case']} | {x['concurrency']} | {fmt(vals[0])} | {fmt(vals[1])} | {fmt(vals[2])} | {vals[0]['rowsPerRequest']:.2f} → {vals[1]['rowsPerRequest']:.2f} |")
(root/'ab-table.md').write_text('\n'.join(lines)+'\n\nP95合并该场景三轮共3000条原始请求按nearest-rank计算；吞吐=3000/三轮累计批次耗时，不取最优轮。逐轮值、CPU节流和列表/COUNT分拆见results/ab-comparison.json。\n')
print('\n'.join(lines))
