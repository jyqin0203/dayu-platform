from pathlib import Path
import json,statistics,csv
out=Path(__file__).resolve().parents[1];r=out/'results';rows=json.loads((r/'baseline-summary.json').read_text());assert len(rows)==54 and sum(x['requests'] for x in rows)==54000 and sum(x['errors'] for x in rows)==0
cases=[x['name'] for x in json.loads((out/'queries.json').read_text())];lines=['| 查询 | 并发 | P95 三轮（ms） | 吞吐三轮（请求/s） | 每请求扫描行数¹ | 错误率 |','|---|---:|---|---|---:|---:|']
for case in cases:
 for c in [1,4]:
  group=sorted((x for x in rows if x['case']==case and x['concurrency']==c),key=lambda x:x['round'])
  fmt=lambda key:', '.join(f"{x[key]:.2f}" for x in group)
  count=', '.join(f"{x['rowsExamined']/x['requests']:.3f}" for x in group)
  lines.append(f"| {case} | {c} | {fmt('p95Ms')} | {fmt('throughputRps')} | {count} | 0% |")
(out/'baseline-table.md').write_text('\n'.join(lines)+'\n\n¹隔离库 performance_schema 中本接口全部 SELECT 的 SUM_ROWS_EXAMINED 增量/请求数，包含产品校验、产品映射、关联查询、列表、COUNT；不是去重文件数，也不是磁盘IO行数。\n')
with (r/'baseline-summary.csv').open('w') as f:
 keys=[k for k in rows[0] if k!='cpuDelta'];w=csv.DictWriter(f,fieldnames=keys);w.writeheader();w.writerows({k:x[k] for k in keys} for x in rows)
# Separate list/count costs from actual HTTP digests, not CLI process latency.
dig=json.loads((r/'baseline-sql-digests.json').read_text());decomp=[]
for g in dig:
 for d in g['digests']:
  if 'FROM `data_assets`' not in d['sql']:continue
  kind='count' if d['sql'].startswith('SELECT COUNT') else 'list'
  decomp.append({'round':g['round'],'case':g['case'],'concurrency':g['concurrency'],'kind':kind,'statements':d['count'],'meanServerMs':d['timerPs']/1e9/d['count'],'rowsExaminedPerStatement':d['rowsExamined']/d['count'],'sortRowsPerStatement':d['sortRows']/d['count'],'noIndexUsed':d['noIndex']})
(r/'list-count-costs.json').write_text(json.dumps(decomp,indent=2))
print('\n'.join(lines))
