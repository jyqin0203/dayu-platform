#!/usr/bin/env bash
set -euo pipefail
work=/tmp/dayu-index-update-20261008
jdk=/home/ubuntu/dayu-prod/tools/jdk17
repo=/home/ubuntu/dayu-platform
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
# Preserve the original baseline jar; abort if it has since been replaced.
python3 - "$work" <<'PY'
from pathlib import Path
import sys,zipfile,hashlib,json
work=Path(sys.argv[1]);jar=Path('/home/ubuntu/dayu-prod/releases/backend.jar')
expected='12a7faae8d53c67bd47e3dcfabda3dc048cc31d38434d821be2151c6931afb2f'
if hashlib.sha256(jar.read_bytes()).hexdigest()!=expected:raise SystemExit('Baseline jar changed; use the retained original artifact, do not silently change baseline')
with zipfile.ZipFile(jar) as z:
 for entry in z.infolist():
  for prefix,dest in [('BOOT-INF/classes/','classes'),('BOOT-INF/lib/','lib')]:
   if entry.filename.startswith(prefix) and not entry.is_dir():
    rel=Path(entry.filename[len(prefix):])
    if rel.is_absolute() or '..' in rel.parts:raise ValueError('Unsafe zip path')
    out=work/dest/rel;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(entry))
(work/'artifact.json').write_text(json.dumps({'jarSha256':expected,'jarSource':str(jar)}))
PY
"$jdk/bin/javac" -cp "$work/classes:$work/lib/*" -d "$work/probe-classes" "$script_dir/ScanBenchmark.java" "$script_dir/ReadOnlyScanProfile.java"
"$jdk/bin/javac" -cp "$work/probe-classes:$work/classes:$work/lib/*" -d "$work/probe-classes" "$script_dir/LiveScanProfile.java"
"$jdk/bin/javac" -cp "$work/classes:$work/lib/*" -d "$work/candidate-classes" \
  "$repo/backend/src/main/java/cn/edu/fudan/dayu/assetindex/application/AssetIndexStore.java" \
  "$repo/backend/src/main/java/cn/edu/fudan/dayu/assetindex/application/PersistentAssetScanner.java" \
  "$repo/backend/src/main/java/cn/edu/fudan/dayu/assetindex/infrastructure/JdbcAssetIndexStore.java"
