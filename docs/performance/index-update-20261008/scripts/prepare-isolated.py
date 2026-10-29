import pathlib,json,subprocess,secrets
r=pathlib.Path('/tmp/dayu-index-update-20261008')
cmd=['mariadb','--no-defaults','--socket='+str(r/'db.sock'),'-u','root','-N']
def sql(q):
 p=subprocess.run(cmd,input=q,text=True,capture_output=True)
 if p.returncode: raise RuntimeError('Isolated SQL failed; private details withheld')
 return p.stdout
assert sql('SELECT @@datadir').strip()==str(r/'db/')+'/'
if (r/'db-password').exists() or sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('dayu_scan_a','dayu_scan_b','dayu_scan_correctness')").strip()!='0':
 raise SystemExit('Experiment already exists; refusing to overwrite credentials or schemas')
password=secrets.token_hex(24)
(r/'db-password').write_text(password);(r/'db-password').chmod(0o600)
def lit(v):
 if v is None:return 'NULL'
 if isinstance(v,(int,float)):return str(v)
 return "CONVERT(X'"+str(v).encode().hex()+"' USING utf8mb4)"
repo=pathlib.Path('/home/ubuntu/dayu-platform')
for name in ['dayu_scan_a','dayu_scan_b']:
 sql(f'CREATE DATABASE {name} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
 for f in sorted((repo/'backend/src/main/resources/db/migration').glob('V*.sql')):sql(f'USE {name};'+f.read_text())
 for l in open('/home/ubuntu/dayu-prod/performance/nc-search-20261007/snapshot/metadata.jsonl'):
  n=json.loads(l)
  if n['table'] not in ['products','product_mode_policies']:continue
  row=n['row'];row.update({k:None for k in ['created_by','updated_by'] if k in row})
  sql(f'USE {name}; INSERT INTO '+n['table']+' ('+','.join('`'+k+'`' for k in row)+') VALUES ('+','.join(lit(v) for v in row.values())+');')
 sql(f"CREATE USER '{name}'@'localhost' IDENTIFIED BY '{password}'; GRANT ALL ON {name}.* TO '{name}'@'localhost';")
sql("CREATE DATABASE dayu_scan_correctness CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'dayu_scan_correctness'@'localhost' IDENTIFIED BY '"+password+"'; GRANT ALL ON dayu_scan_correctness.* TO 'dayu_scan_correctness'@'localhost';")
print('Created isolated schemas; no production users or credentials copied.')
