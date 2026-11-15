#!/usr/bin/env bash
# Usage: count-rows.sh old|new  -> prints "table count" per public table (exact counts). No secrets printed.
set -euo pipefail
set +x
Q="select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' order by 1"
if [ "$1" = old ]; then
  set -a; . /dev/shm/olddb.env; set +a
  H=$(sed -E 's#jdbc:postgresql://([^:/]+):.*#\1#' <<<"$DATABASE_URL"); P=$(sed -E 's#.*:([0-9]+)/.*#\1#' <<<"$DATABASE_URL"); D=$(sed -E 's#.*/([^/?]+).*#\1#' <<<"$DATABASE_URL")
  psqlc() { docker run --rm -i -e PGPASSWORD="$DATABASE_PASSWORD" postgres:18.1-alpine psql -h "$H" -p "$P" -U "$DATABASE_USERNAME" -d "$D" -At "$@"; }
else
  psqlc() { docker exec -i book-management-db-1 sh -c 'psql -U "$POSTGRES_USER" -d bookdb -At'; }
fi
for t in $(echo "$Q" | psqlc); do printf '%s %s\n' "$t" "$(echo "select count(*) from \"$t\"" | psqlc)"; done
