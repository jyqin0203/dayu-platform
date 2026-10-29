"""Create two brand-new write-test schemas on isolated 13307; never writes dayu_perf asset rows."""
from pathlib import Path
import subprocess,zipfile
r=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007');source=Path(__file__).resolve().parent
cmd=['mariadb','--defaults-file='+str(r/'config/client.cnf'),'--batch','--raw','--skip-column-names']
def sql(q):return subprocess.run(cmd,input=q,text=True,capture_output=True,check=True).stdout
assert sql('SELECT @@port,@@datadir;').strip()==f'13307\t{r}/db/'
for variant in ['a','b']:
 db='dayu_perf_ingest_'+variant
 assert sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+db+"';").strip()=='0'
 sql('CREATE DATABASE '+db+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
 sql('USE '+db+';'+(r/'snapshot/schema.sql').read_text())
 sql('INSERT INTO '+db+'.products SELECT * FROM dayu_perf.products; INSERT INTO '+db+'.product_mode_policies SELECT * FROM dayu_perf.product_mode_policies; GRANT ALL ON '+db+".* TO 'dayu_perf'@'127.0.0.1';")
 if variant=='b':sql('CREATE INDEX idx_assets_type_mode_status_lead_time ON '+db+'.data_assets(asset_type,data_mode,status,lead_minutes,valid_time,id);')
classes=r/'probe/classes';lib=r/'probe/lib';classes.mkdir(parents=True,exist_ok=True);lib.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(r/'backend.jar') as z:
 for name in z.namelist():
  if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
   dst=classes/name.removeprefix('BOOT-INF/classes/');dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(z.read(name))
  if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(lib/Path(name).name).write_bytes(z.read(name))
subprocess.run(['/home/ubuntu/dayu-test/tools/jdk17/bin/javac','-cp',str(classes)+':'+str(lib/'*'),'-d',str(classes),str(source/'IngestCostProbe.java')],check=True)
# Precreate log as ubuntu so Java service output remains readable, without printing connection settings.
(r/'logs/ingest-console.log').touch(mode=0o600)
unit=f'''[Unit]
Description=Temporary metadata ingestion index cost replay
[Service]
Type=oneshot
User=ubuntu
Group=ubuntu
UMask=0077
Nice=10
IOSchedulingClass=idle
CPUAffinity=2 3
CPUQuota=100%
CPUWeight=10
MemoryMax=512M
MemorySwapMax=0
TasksMax=128
TimeoutStartSec=1800
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths={r}
InaccessiblePaths=/home/user/FDU-CRIAS /datapool /var/lib/mysql /home/ubuntu/dayu-test/db
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
IPAddressDeny=any
IPAddressAllow=localhost
EnvironmentFile={r}/config/backend.env
ExecStart=/home/ubuntu/dayu-test/tools/jdk17/bin/java -Xms128m -Xmx384m -XX:ActiveProcessorCount=2 -cp {classes}:{lib}/* IngestCostProbe
StandardOutput=append:{r}/logs/ingest-console.log
StandardError=append:{r}/logs/ingest-console.log
'''
(r/'config/dayu-perf-ingest.service').write_text(unit)
print('Two empty isolated ingestion schemas and compiled production-store replay prepared.')
