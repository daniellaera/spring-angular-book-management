#!/usr/bin/env bash
# Run on the NEW LXC. Dumps the OLD database (separate host) to /root/dumps/old-<ts>.dump and prints per-table row counts
# to /root/dumps/old-<ts>.counts. Needs /dev/shm/olddb.env (DATABASE_URL/USERNAME/PASSWORD of the old DB, piped from the
# old VM, tmpfs only). Nothing secret is printed.
set -euo pipefail
set +x
set -a; . /dev/shm/olddb.env; set +a
H=$(sed -E 's#jdbc:postgresql://([^:/]+):.*#\1#' <<<"$DATABASE_URL"); P=$(sed -E 's#.*:([0-9]+)/.*#\1#' <<<"$DATABASE_URL"); D=$(sed -E 's#.*/([^/?]+).*#\1#' <<<"$DATABASE_URL")
install -d -m 700 /root/dumps; TS=$(date +%Y%m%d-%H%M%S); OUT=/root/dumps/old-$TS
pg() { docker run --rm -i -e PGPASSWORD="$DATABASE_PASSWORD" postgres:18.1-alpine "$@" -h "$H" -p "$P" -U "$DATABASE_USERNAME"; }
pg pg_dump -Fc --no-owner --no-acl -d "$D" > "$OUT.dump"
"$(dirname "$0")/count-rows.sh" old > "$OUT.counts"
ln -sf "$OUT.dump" /root/dumps/latest.dump; ln -sf "$OUT.counts" /root/dumps/latest.old.counts
echo "dump: $OUT.dump ($(du -h "$OUT.dump" | cut -f1))"
