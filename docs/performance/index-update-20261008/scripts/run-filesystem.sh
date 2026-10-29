#!/usr/bin/env bash
# Read-only probe. Requires an existing deployed jar and JDK; no database/service changes.
set -euo pipefail
jar=${1:?backend.jar path required}
jdk=${2:?JDK home required}
webp=${3:?WebP root required}
nc=${4:?NC root required}
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
work=$(mktemp -d /tmp/dayu-index-profile.XXXXXX)
chmod 700 "$work"
python3 - "$jar" "$work" <<'PY'
import sys, zipfile, pathlib, hashlib, json
jar, work = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
with zipfile.ZipFile(jar) as archive:
    for entry in archive.infolist():
        for prefix, dest in [('BOOT-INF/classes/', 'classes'), ('BOOT-INF/lib/', 'lib')]:
            if entry.filename.startswith(prefix) and not entry.is_dir():
                rel = pathlib.PurePosixPath(entry.filename[len(prefix):])
                if rel.is_absolute() or '..' in rel.parts: raise ValueError('Unsafe archive path')
                out = work / dest / rel
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(archive.read(entry))
(work/'artifact.json').write_text(json.dumps({'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}))
PY
"$jdk/bin/javac" -cp "$work/classes:$work/lib/*" -d "$work/probe-classes" "$script_dir/ReadOnlyScanProfile.java"
nice -n 15 ionice -c 3 taskset -c 2 "$jdk/bin/java" -Xmx256m -XX:ActiveProcessorCount=1 \
  -cp "$work/probe-classes:$work/classes:$work/lib/*" ReadOnlyScanProfile "$webp" "$nc" > "$work/filesystem.jsonl"
printf 'Results retained at %s\n' "$work"
