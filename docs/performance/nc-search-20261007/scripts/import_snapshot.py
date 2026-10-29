"""Import metadata into the new, empty performance database only; never prints rows or credentials."""
from pathlib import Path
import json,subprocess
r=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007');cmd=['mariadb','--defaults-file='+str(r/'config/client.cnf'),'--batch','--raw','--skip-column-names']
def sql(q):
 p=subprocess.run(cmd,input=q,capture_output=True,text=True)
 if p.returncode:raise SystemExit('Isolated import failed; details withheld')
 return p.stdout
assert sql('SELECT @@port,@@datadir;').strip()==f'13307\t{r}/db/'
assert sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='dayu_perf';").strip()=='0'
pw=(r/'config/backend.env').read_text().strip().split('=',1)[1]
sql("CREATE DATABASE dayu_perf CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'dayu_perf'@'127.0.0.1' IDENTIFIED BY '"+pw+"'; GRANT ALL ON dayu_perf.* TO 'dayu_perf'@'127.0.0.1';")
sql('USE dayu_perf;'+(r/'snapshot/schema.sql').read_text())
cols=json.loads((r/'snapshot/columns.json').read_text());statements=[]
def literal(v):
 if v is None:return 'NULL'
 if isinstance(v,(int,float)):return str(v)
 return "CONVERT(X'"+str(v).encode().hex()+"' USING utf8mb4)"
for l in (r/'snapshot/metadata.jsonl').read_text().splitlines():
 v=json.loads(l);t=v['table'];c=cols[t]
 statements.append('INSERT INTO `'+t+'` ('+','.join('`'+x+'`' for x in c)+') VALUES ('+','.join(literal(v['row'][x]) for x in c)+');')
sql("USE dayu_perf; SET time_zone='+00:00'; START TRANSACTION;"+''.join(statements)+'COMMIT;')
print(sql('USE dayu_perf; SELECT COUNT(*) AS assets FROM data_assets; SELECT COUNT(*) AS users FROM users; SELECT @@performance_schema;'))
