#!/usr/bin/env bash
set -euo pipefail
work=/tmp/dayu-index-update-20261008
if [[ ! -d "$work/db/mysql" ]]; then
  mkdir -p "$work"
  chmod 700 "$work"
  mariadb-install-db --no-defaults --datadir="$work/db" --auth-root-authentication-method=normal --skip-test-db > "$work/install.log" 2>&1
fi
if [[ -S "$work/db.sock" ]]; then
  printf 'Private socket already exists; refusing duplicate startup.\n' >&2
  exit 1
fi
# Foreground process; use a dedicated terminal. No public/TCP listener or system service changes.
exec nice -n 15 ionice -c 3 taskset -c 2 /usr/sbin/mariadbd --no-defaults \
  --datadir="$work/db" --socket="$work/db.sock" --skip-networking \
  --pid-file="$work/db.pid" --log-error="$work/db.log" \
  --innodb-buffer-pool-size=128M --max-connections=10 --performance-schema=ON
