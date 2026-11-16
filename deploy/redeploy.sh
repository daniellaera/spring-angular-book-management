#!/usr/bin/env bash
# Redeploy book-management on the LXC. Usage: redeploy.sh <image-tag>   (CI passes the git SHA)
# Secrets: pulled from Infisical at start with the machine identity in /root/.config/book-management/infisical.env
# (root-only, mode 600). Nothing secret is written to disk by this script, and nothing is printed.
# Rollback: on a failed health check the previous image tags are restored automatically.
set -euo pipefail
set +x

cd "$(dirname "$0")"
TAG="${1:?usage: redeploy.sh <image-tag>}"
STATE_DIR=/var/lib/book-management
CRED=/root/.config/book-management/infisical.env
PROJECT_ID_FILE=/root/.config/book-management/project_id
APP_PORT=8081
COMPOSE=(docker compose -f compose.prod.yml)
mkdir -p "$STATE_DIR"
export INFISICAL_API_URL="${INFISICAL_API_URL:-http://192.168.1.163/api}"; export INFISICAL_DOMAIN="$INFISICAL_API_URL"   # self-hosted Infisical

[ "$(stat -c %a "$CRED")" = "600" ] || { echo "$CRED must be mode 600" >&2; exit 1; }

# --- Infisical login (client id/secret stay in this process only) ---
# shellcheck disable=SC1090
INFISICAL_TOKEN="$(set -a; . "$CRED"; set +a
  infisical login --method=universal-auth --client-id="$INFISICAL_CLIENT_ID" \
    --client-secret="$INFISICAL_CLIENT_SECRET" --silent --plain)"
export INFISICAL_TOKEN
PROJECT_ID="$(cat "$PROJECT_ID_FILE")"
run_with_secrets() { infisical run --projectId="$PROJECT_ID" --env=prod --silent -- "$@"; }

# --- registry login (Gitea container registry): pull credentials GITEA_REGISTRY_USER / GITEA_REGISTRY_TOKEN come from Infisical ---
# REGISTRY is "<gitea-host:port>/<owner>"; docker login needs only the host part.
export REGISTRY="${REGISTRY:-192.168.1.103:3000/daniellaera}"
REGISTRY_HOST="${REGISTRY%%/*}"; export REGISTRY_HOST
run_with_secrets bash -c 'if [ -n "${GITEA_REGISTRY_USER:-}" ] && [ -n "${GITEA_REGISTRY_TOKEN:-}" ]; then echo "$GITEA_REGISTRY_TOKEN" | docker login "$REGISTRY_HOST" -u "$GITEA_REGISTRY_USER" --password-stdin >/dev/null; else echo "no GITEA_REGISTRY_* in Infisical: using local images only"; fi'
trap 'docker logout "$REGISTRY_HOST" >/dev/null 2>&1 || true' EXIT

PREV_BACKEND="$(cat "$STATE_DIR/backend.tag" 2>/dev/null || true)"
PREV_FRONTEND="$(cat "$STATE_DIR/frontend.tag" 2>/dev/null || true)"

deploy() { # $1 backend tag, $2 frontend tag
  export BACKEND_TAG="$1" FRONTEND_TAG="$2"
  # pull when possible; if the registry is unreachable/unauthorised the image must already exist locally (up uses --pull missing)
  run_with_secrets "${COMPOSE[@]}" pull --quiet book-backend book-frontend || echo "pull skipped/failed, trying local images" >&2
  run_with_secrets "${COMPOSE[@]}" up --detach --remove-orphans --pull missing
}

healthy() {
  for _ in $(seq 1 40); do   # up to ~200 s: Spring + Flyway start
    if curl -fsS -o /dev/null "http://127.0.0.1:${APP_PORT}/api/v3/book"; then return 0; fi
    sleep 5
  done
  return 1
}

echo "deploying tag ${TAG} (previous: ${PREV_BACKEND:-none})"
deploy "$TAG" "$TAG"

if healthy; then
  echo "$TAG" > "$STATE_DIR/backend.tag"; echo "$TAG" > "$STATE_DIR/frontend.tag"
  docker image prune --force >/dev/null
  "${COMPOSE[@]}" ps
  echo "OK: ${TAG} is live"
  exit 0
fi

echo "health check FAILED for ${TAG}" >&2
"${COMPOSE[@]}" logs --tail=40 book-backend 2>&1 | sed -E 's/(password|secret|token|key)=[^ ]+/\1=<redacted>/Ig' >&2 || true
if [ -n "$PREV_BACKEND" ]; then
  echo "rolling back to ${PREV_BACKEND}" >&2
  deploy "$PREV_BACKEND" "${PREV_FRONTEND:-$PREV_BACKEND}"
  healthy && echo "rollback OK" >&2 || echo "ROLLBACK ALSO UNHEALTHY - investigate" >&2
fi
exit 1
