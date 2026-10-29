#!/usr/bin/env bash
set -euo pipefail
root=/tmp/dayu-redis-eval-20261008
python3 - <<'PY'
from pathlib import Path
r=Path('/tmp/dayu-redis-eval-20261008')
lines=['bind 127.0.0.1','port 16389','protected-mode yes','save ""','appendonly no','maxmemory 64mb','maxmemory-policy allkeys-lru','dir '+str(r),'pidfile '+str(r/'redis.pid'),'logfile '+str(r/'redis.log')]
if (r/'redis-password').exists():lines.append('requirepass '+(r/'redis-password').read_text().strip())
p=r/'redis.conf';p.write_text('\n'.join(lines)+'\n');p.chmod(0o600)
PY
exec nice -n 15 taskset -c 5 /home/ubuntu/dayu-prod/tools/redis/bin/redis-server "$root/redis.conf"
