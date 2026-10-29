"""Report every paired write-replay round, without claiming filesystem scan throughput."""
from pathlib import Path
import json,statistics,shutil
out=Path(__file__).resolve().parents[1];root=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007')
v=json.loads((root/'logs/ingest-results.json').read_text());assert len(v)==24 and all(x['correctnessPassed'] for x in v)
(out/'results/ingest-raw.json').write_text(json.dumps(v,indent=2));shutil.copy2(root/'logs/ingest-console.log',out/'results/ingest-console.log')
summary=[];lines=['| 元数据入库阶段 | 操作数/轮 | A三轮秒 | B三轮秒 | 中位耗时变化B/A |','|---|---:|---|---|---:|']
for phase in ['insert','unchanged','metadata-update-10pct','missing-10pct']:
 a=sorted((x for x in v if x['phase']==phase and x['variant']=='a'),key=lambda x:x['round']);b=sorted((x for x in v if x['phase']==phase and x['variant']=='b'),key=lambda x:x['round'])
 av=[x['seconds'] for x in a];bv=[x['seconds'] for x in b];change=(statistics.median(bv)/statistics.median(av)-1)*100
 row={'phase':phase,'operationsPerRound':a[0]['operations'],'aSeconds':av,'bSeconds':bv,'aMedianSeconds':statistics.median(av),'bMedianSeconds':statistics.median(bv),'bVsAMedianTimeChangePct':change,'pairedChangesPct':[(y/x-1)*100 for x,y in zip(av,bv)]};summary.append(row)
 f=lambda values:' / '.join(f'{x:.3f}' for x in values)
 lines.append(f"| {phase} | {a[0]['operations']} | {f(av)} | {f(bv)} | {change:+.2f}% |")
(out/'results/ingest-summary.json').write_text(json.dumps(summary,indent=2));(out/'ingest-table.md').write_text('\n'.join(lines)+'\n\n每种配置先有一轮不计入结果的预热；随后3轮交替B/A、A/B、B/A。使用相同真实元数据快照与原生产JdbcAssetIndexStore，数据库真实提交。无科学文件内容或文件系统枚举，不是完整端到端磁盘扫描性能。\n');print('\n'.join(lines))
