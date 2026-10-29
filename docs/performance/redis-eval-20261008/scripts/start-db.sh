#!/usr/bin/env bash
set -euo pipefail
root=/tmp/dayu-redis-eval-20261008
if [[ ! -d "$root/db/mysql" ]]; then
  mkdir -p "$root"
  chmod 700 "$root"
  mariadb-install-db --no-defaults --datadir="$root/db" --auth-root-authentication-method=normal --skip-test-db > "$root/install.log" 2>&1
fi
exec nice -n 15 ionice -c 3 taskset -c 2 /usr/sbin/mariadbd --no-defaults \
  --datadir="$root/db" --socket="$root/db.sock" --bind-address=127.0.0.1 --port=13309 \
  --pid-file="$root/db.pid" --log-error="$root/db.log" --innodb-buffer-pool-size=128M \
  --max-connections=20 --performance-schema=ON
