#!/usr/bin/env bash
set -euo pipefail
mode=${1:?off or on required}
case "$mode" in off|on) ;; *) exit 2;; esac
root=/tmp/dayu-redis-eval-20261008
exec nice -n 15 ionice -c 3 taskset -c 3 /home/ubuntu/dayu-prod/tools/jdk17/bin/java \
  -Xmx384m -XX:ActiveProcessorCount=1 -cp "$root/probe-classes:$root/classes:$root/lib/*" CacheEvalServer "$mode"
