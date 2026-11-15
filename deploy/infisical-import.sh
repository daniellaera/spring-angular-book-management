#!/usr/bin/env bash
# One-time, run ON THE LXC after the Infisical project exists. Loads the secrets staged in /dev/shm/book.env (tmpfs; migrated
# from the old VM + freshly generated) into Infisical (env prod) and stores the runtime identity. Prints names only, never values.
# Usage: INFISICAL_TOKEN=<token of an identity/user with WRITE on the project> infisical-import.sh <projectId> [--shred]
set -euo pipefail
set +x
PID="${1:?usage: infisical-import.sh <projectId> [--shred]}"
: "${INFISICAL_TOKEN:?export INFISICAL_TOKEN (write access) first}"
export INFISICAL_DOMAIN="${INFISICAL_DOMAIN:-http://192.168.1.163/api}"
infisical secrets set --projectId="$PID" --env=prod --file=/dev/shm/book.env --silent
echo "$PID" > /root/.config/book-management/project_id; chmod 600 /root/.config/book-management/project_id
echo "secret names now in Infisical prod:"
infisical secrets --projectId="$PID" --env=prod --plain --silent 2>/dev/null | cut -d= -f1 | sort | tr '\n' ' ' || true; echo
[ "${2:-}" = "--shred" ] && shred -u /dev/shm/book.env && echo "tmpfs env shredded"
