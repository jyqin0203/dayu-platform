#!/usr/bin/env bash
set -euo pipefail
root=/tmp/dayu-redis-eval-20261008
jdk=/home/ubuntu/dayu-prod/tools/jdk17
python3 - <<'PY'
from pathlib import Path
import hashlib,zipfile,shutil
r=Path('/tmp/dayu-redis-eval-20261008');r.mkdir(exist_ok=True);r.chmod(0o700)
p=r/'backend-baseline.jar'
if not p.exists():shutil.copyfile('/home/ubuntu/dayu-prod/releases/backend.jar',p)
assert hashlib.sha256(p.read_bytes()).hexdigest()=='12a7faae8d53c67bd47e3dcfabda3dc048cc31d38434d821be2151c6931afb2f','Baseline jar changed'
with zipfile.ZipFile(p) as z:
 for entry in z.infolist():
  for prefix,dest in [('BOOT-INF/classes/','classes'),('BOOT-INF/lib/','lib')]:
   if entry.filename.startswith(prefix) and not entry.is_dir():
    rel=Path(entry.filename[len(prefix):]);assert not rel.is_absolute() and '..' not in rel.parts
    out=r/dest/rel;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(entry))
p=r/'source-metadata.jsonl'
if not p.exists():shutil.copyfile('/home/ubuntu/dayu-prod/performance/nc-search-20261007/snapshot/metadata.jsonl',p)
PY
"$jdk/bin/javac" -cp "$root/classes:$root/lib/*" -d "$root/probe-classes" \
  docs/performance/redis-eval-20261008/scripts/CacheEvalServer.java
