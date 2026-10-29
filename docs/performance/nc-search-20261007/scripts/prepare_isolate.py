"""Prepare isolated services; does not install units, start services or touch the source database."""
from pathlib import Path
import os,secrets,shutil,socket,subprocess
os.umask(0o077)
r=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007');assert not (r/'config/backend.env').exists()
for port in [13307,18081]:
 s=socket.socket();s.bind(('127.0.0.1',port));s.close()
pw=secrets.token_hex(32)
(r/'config/backend.env').write_text('PERF_DB_PASSWORD='+pw+'\n')
(r/'config/client.cnf').write_text('[client]\nuser=ubuntu\nprotocol=socket\nsocket='+str(r/'run/db.sock')+'\n')
(r/'config/database.cnf').write_text(f'''[mysqld]
user=ubuntu
bind-address=127.0.0.1
port=13307
datadir={r}/db
socket={r}/run/db.sock
pid-file={r}/run/db.pid
log-error={r}/logs/db.log
tmpdir={r}/run
skip-name-resolve
local-infile=0
skip-log-bin
max-connections=20
innodb-buffer-pool-size=128M
innodb-log-file-size=64M
performance-schema=ON
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+00:00
''')
(r/'config/application.yml').write_text('''spring:
  datasource:
    url: jdbc:mariadb://127.0.0.1:13307/dayu_perf
    username: dayu_perf
    password: ${PERF_DB_PASSWORD}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
server:
  address: 127.0.0.1
  port: 18081
  tomcat:
    threads:
      max: 24
      min-spare: 2
dayu:
  indexing:
    enabled: false
  media:
    enabled: false
  cache:
    enabled: false
  copilot:
    enabled: false
''')
shutil.copy2('/home/ubuntu/dayu-test/releases/backend.jar',r/'backend.jar')
common=f'''[Service]
Type=simple
User=ubuntu
Group=ubuntu
UMask=0077
Nice=10
IOSchedulingClass=idle
CPUAffinity=2 3
CPUQuota=100%
CPUWeight=10
MemorySwapMax=0
TasksMax=128
Restart=no
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
'''
(r/'config/dayu-perf-db.service').write_text('[Unit]\nDescription=Temporary isolated Dayu NC performance database\n'+common+f'MemoryMax=512M\nExecStart=/usr/sbin/mariadbd --defaults-file={r}/config/database.cnf\n')
(r/'config/dayu-perf-backend.service').write_text('[Unit]\nDescription=Temporary isolated Dayu NC performance backend\n'+common+f'MemoryMax=768M\nEnvironmentFile={r}/config/backend.env\nExecStart=/home/ubuntu/dayu-test/tools/jdk17/bin/java -Xms128m -Xmx512m -XX:ActiveProcessorCount=2 -jar {r}/backend.jar --spring.config.additional-location=file:{r}/config/application.yml\nStandardOutput=append:{r}/logs/backend.log\nStandardError=append:{r}/logs/backend.log\n')
with (r/'logs/initialize.log').open('w') as log:
 subprocess.run(['nice','-n','10','taskset','-c','2,3','mariadb-install-db','--no-defaults','--datadir='+str(r/'db'),'--auth-root-authentication-method=socket','--auth-root-socket-user=ubuntu','--skip-test-db'],stdout=log,stderr=log,check=True)
print('Isolated database initialized; units prepared but not started.')
