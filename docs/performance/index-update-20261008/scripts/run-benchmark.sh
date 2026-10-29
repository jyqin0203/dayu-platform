#!/usr/bin/env bash
# Run sequentially; private DB directory guard is also enforced by the Java harness.
set -euo pipefail
variant=${1:?a or b required}
round=${2:?round required}
case "$variant" in a|b) ;; *) exit 2;; esac
work=/tmp/dayu-index-update-20261008
jdk=/home/ubuntu/dayu-prod/tools/jdk17
extra=""
if [[ "$variant" == b ]]; then extra="$work/candidate-classes:"; fi
cp="$extra$work/probe-classes:$work/classes:$work/lib/*:/home/ubuntu/dayu-prod/cache/m2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar:/home/ubuntu/dayu-prod/cache/m2/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar"
nice -n 15 ionice -c 3 taskset -c 3 "$jdk/bin/java" -Xmx256m -XX:ActiveProcessorCount=1 -cp "$cp" ScanBenchmark "$variant" "$round" > "$work/bench-$variant-$round.log" 2>&1
