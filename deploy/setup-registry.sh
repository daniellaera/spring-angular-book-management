#!/usr/bin/env bash
# One-time, run ON THE LXC (CT 110) as root. Prepares Docker for the Gitea container registry (plain HTTP):
#  1. lists the registry as insecure (daemon reload, NOT a restart: running containers are untouched)
#  2. re-tags images already present locally to the Gitea names so redeploy.sh rollback keeps working
# Prints no secrets.
set -euo pipefail
REG_HOST="${REG_HOST:-192.168.1.103:3000}"
REG="${REG:-$REG_HOST/daniellaera}"

if ! docker info 2>/dev/null | grep -q "$REG_HOST"; then
  [ -f /etc/docker/daemon.json ] && [ -s /etc/docker/daemon.json ] && { echo "/etc/docker/daemon.json exists: add \"$REG_HOST\" to insecure-registries by hand" >&2; exit 1; }
  printf '{\n  "insecure-registries": ["%s"]\n}\n' "$REG_HOST" > /etc/docker/daemon.json
  systemctl reload docker; sleep 3
fi
docker info 2>/dev/null | grep -A2 -i "insecure registries" || { echo "reload did not pick it up: use 'systemctl restart docker' in a quiet moment" >&2; exit 1; }

for img in book-backend book-frontend; do
  docker images --format '{{.Repository}}:{{.Tag}}' | grep -E "^[^/]+(:[0-9]+)?/(.*/)?${img}:" | grep -v "^${REG}/" | while read -r old; do
    docker tag "$old" "${REG}/${img}:${old##*:}"
  done
done
docker images --format '{{.Repository}}:{{.Tag}}' | grep "^${REG}/" | sort
