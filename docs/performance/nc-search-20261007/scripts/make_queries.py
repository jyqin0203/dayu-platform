from pathlib import Path
import json
out=Path(__file__).resolve().parents[1];r=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007')
data=[json.loads(l) for l in (r/'snapshot/metadata.jsonl').read_text().splitlines()]
product=next(x['row']['id'] for x in data if x['table']=='products' and x['row']['code']=='PRECIP')
base={'productCode':'PRECIP','from':'2026-09-02T06:00:00Z','to':'2026-09-08T06:00:00Z','pageSize':20,'page':1}
cases=[]
def add(name,mode,**kw):cases.append({'name':name,'params':dict(base,dataMode=mode,**kw)})
add('rt_short','REALTIME',to='2026-09-02T12:00:00Z')
add('rt_long_first','REALTIME')
add('rt_long_last','REALTIME',page=7)
add('fc_short','FORECAST',to='2026-09-02T12:00:00Z')
add('fc_long_first','FORECAST')
add('fc_long_last','FORECAST',page=19)
add('fc_cycle','FORECAST',cycleTime='2026-09-02T06:00:00Z')
add('fc_lead','FORECAST',leadMinutes=60)
add('empty_range','REALTIME',**{'from':'2026-10-01T00:00:00Z','to':'2026-10-02T00:00:00Z'})
for c in cases:
 p=c['params'];conv=lambda v:v.replace('T',' ').replace('Z','')
 where=f" WHERE a.asset_type='NETCDF' AND a.data_mode='{p['dataMode']}' AND a.status='AVAILABLE' AND EXISTS (SELECT 1 FROM data_asset_products ap WHERE ap.asset_id=a.id AND ap.product_id={product}) AND a.valid_time>='{conv(p['from'])}' AND a.valid_time<='{conv(p['to'])}'"
 if 'cycleTime' in p:where+=f" AND a.cycle_time='{conv(p['cycleTime'])}'"
 if 'leadMinutes' in p:where+=f" AND a.lead_minutes={p['leadMinutes']}"
 c['sql']={'list':'SELECT a.* FROM data_assets a'+where+f" ORDER BY a.valid_time DESC,a.id DESC LIMIT {p['pageSize']} OFFSET {(p['page']-1)*p['pageSize']}",'count':'SELECT COUNT(*) FROM data_assets a'+where}
(out/'queries.json').write_text(json.dumps(cases,indent=2))
