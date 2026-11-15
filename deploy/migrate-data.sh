#!/usr/bin/env bash
# Run on the NEW LXC. Restores a pg_dump (custom format) of the old DB into the new Postgres container.
# Usage: migrate-data.sh /path/to/old.dump       (dump made on the old VM with: pg_dump -Fc --no-owner ...)
# The new DB password is whatever is in Infisical; it is injected, never typed or printed.
set -euo pipefail
set +x
DUMP="${1:?usage: migrate-data.sh <dump-file>}"
cd "$(dirname "$0")"
# Secrets: tmpfs env file (parallel-run phase) or injected by `infisical run` when already in the environment.
[ -f /dev/shm/book.env ] && { set -a; . /dev/shm/book.env; set +a; }
export BACKEND_TAG=unused FRONTEND_TAG=unused   # only needed to satisfy compose interpolation
cat "$DUMP" | docker compose -f compose.prod.yml exec -T db \
  sh -c 'pg_restore --no-owner --role="$POSTGRES_USER" -U "$POSTGRES_USER" -d bookdb --clean --if-exists --no-acl'
echo "restore finished"
