from pathlib import Path
import json,secrets,subprocess,collections,datetime,urllib.parse,socket
R=Path('/tmp/dayu-redis-eval-20261008'); C=R/'classes'
cmd=['mariadb','--no-defaults','--socket='+str(R/'db.sock'),'-uroot','-N']
def sql(q):
 p=subprocess.run(cmd,input=q,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('Isolated database initialization failed; private details withheld')
 return p.stdout
assert sql('SELECT @@datadir').strip()==str(R/'db')+'/'
assert sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='dayu_cache_eval'").strip()=='0'
assert not (R/'db-password').exists()
pw=secrets.token_hex(24);rpw=secrets.token_hex(24)
for name,value in [('db-password',pw),('redis-password',rpw)]:
 (R/name).write_text(value);(R/name).chmod(0o600)
sql("CREATE DATABASE dayu_cache_eval CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'dayu_cache_eval'@'127.0.0.1' IDENTIFIED BY '"+pw+"'; GRANT ALL ON dayu_cache_eval.* TO 'dayu_cache_eval'@'127.0.0.1';")
for f in sorted((C/'db/migration').glob('V*.sql')):sql('USE dayu_cache_eval;'+f.read_text())
def lit(v):
 if v is None:return 'NULL'
 if isinstance(v,(int,float)):return str(v)
 return "CONVERT(X'"+str(v).encode().hex()+"' USING utf8mb4)"
statements=[];assets=[];links=collections.defaultdict(list);products={}
for line in (R/'source-metadata.jsonl').read_text().splitlines():
 n=json.loads(line);table=n['table'];row=n['row']
 if table not in ('products','product_mode_policies','data_assets','data_asset_products'):continue
 for k in ('created_by','updated_by'):
  if k in row:row[k]=None
 if table=='products':products[row['id']]=row['code']
 if table=='data_assets':assets.append(row)
 if table=='data_asset_products':links[row['asset_id']].append(row['product_id'])
 statements.append('INSERT INTO '+table+' ('+','.join('`'+k+'`' for k in row)+') VALUES ('+','.join(lit(v) for v in row.values())+');')
sql("USE dayu_cache_eval; SET time_zone='+00:00'; START TRANSACTION;"+''.join(statements)+"INSERT INTO users(id,email,password_hash,organization,role,status) VALUES(1,'cache-fixture@example.test','unusable-test-fixture','Synthetic','ADMIN','ACTIVE'); COMMIT;")
# Authenticate the new isolated Redis without putting its password on a process command line.
with socket.create_connection(('127.0.0.1',16389)) as s:
 args=['CONFIG','SET','requirepass',rpw];s.sendall(('*'+str(len(args))+'\r\n'+''.join('$'+str(len(x.encode()))+'\r\n'+x+'\r\n' for x in args)).encode());assert s.recv(128)==b'+OK\r\n'
groups=collections.defaultdict(list)
for a in assets:
 if a['asset_type']=='WEBP' and a['data_mode']=='FORECAST':
  for pid in links[a['id']]:groups[(products[pid],a['cycle_time'])].append(a)
queries=[]
for code in ('BT855','BT108','BT120','BT133'):
 cycles=sorted([cycle for p,cycle in groups if p==code],reverse=True)[:20]
 for cycle in cycles:
  stamp=datetime.datetime.fromisoformat(cycle).strftime('%Y%m%d%H%M')
  params={'path':'WebP/WebP_V2_Dpi500_4KM/forecast/'+stamp+'/'+code,'number':200}
  queries.append({'id':len(queries),'product':code,'cycle':cycle,'expectedFiles':len(groups[(code,cycle)]),'url':'/api/files.php?'+urllib.parse.urlencode(params)})
assert len(queries)==80
(R/'queries.json').write_text(json.dumps(queries,indent=2))
for mode,port in [('off',18580),('on',18581)]:
 props={'server.address':'127.0.0.1','server.port':port,'server.tomcat.threads.max':8,
 'spring.datasource.url':'jdbc:mariadb://127.0.0.1:13309/dayu_cache_eval','spring.datasource.username':'dayu_cache_eval','spring.datasource.password':pw,'spring.datasource.hikari.maximum-pool-size':6,
 'spring.flyway.enabled':'false','dayu.cache.enabled':str(mode=='on').lower(),'dayu.cache.catalog-ttl':'5m','dayu.cache.preview-ttl':'2m','dayu.cache.forecast-cycle-ttl':'2m',
 'spring.data.redis.host':'127.0.0.1','spring.data.redis.port':16389,'spring.data.redis.password':rpw,'spring.data.redis.timeout':'250ms','spring.data.redis.connect-timeout':'250ms',
 'dayu.indexing.enabled':'false','dayu.indexing.roots.webp-preview':R/'fixtures/webp','dayu.indexing.roots.netcdf-data':R/'fixtures/nc','dayu.copilot.enabled':'false','dayu.media.enabled':'false'}
 p=R/(mode+'.properties');p.write_text('\n'.join(str(k)+'='+str(v) for k,v in props.items())+'\n');p.chmod(0o600)
for t in ('webp','nc'):(R/'fixtures'/t).mkdir(parents=True)
summary={'assets':len(assets),'links':sum(len(v) for v in links.values()),'logicalBytes':sum(a['file_size'] for a in assets),'queryCount':len(queries),'fixtureUsers':1,'source':'retained real metadata snapshot 20261007; no scientific content copied'}
Path('docs/performance/redis-eval-20261008/results/dataset.json').write_text(json.dumps(summary,indent=2)+'\n');print(summary)
