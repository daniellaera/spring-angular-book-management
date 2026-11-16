# book-management: deployment

Status of the migration from VM `app-book-dev` (+ separate DB host `book-management-db-dev.home`) to LXC CT 110 `book-management`
(192.168.1.110, Debian 13, unprivileged, nesting, 2 vCPU / 4 GB / 20 GB on `big-data-pve`, pool `book-pool`, onboot) with Postgres in Docker.

## Status / blockers
| Step | State |
|------|-------|
| 0-1 Audit, land AI hardening | Done. 75 tests green (run in `maven:3.9.9-temurin-21` with the Docker socket; local JDK 25 breaks Lombok). |
| 2 Secrets | **Done**: 11 secrets in Infisical (project `book-management`, env `prod`), runtime identity `book-runtime` (Viewer). `DATABASE_URL` and `QDRANT_URL` are NOT in Infisical: fixed in `compose.prod.yml` (internal service names). The tmpfs staging files were shredded. |
| 3 LXC | **Done**: CT 110 (192.168.1.110, Debian 13, unprivileged, nesting, 2 vCPU/4 GB/20 GB, onboot). |
| 4 Pipeline | Workflow ready (not pushed, not yet exercised). Needs Gitea secrets `VM_HOST`=192.168.1.110, `VM_USER`=root, `VM_SSH_PORT`=22, `SSH_PRIVATE_KEY` authorised on the LXC, and `GITEA_REGISTRY_USER` / `GITEA_REGISTRY_TOKEN` in Infisical (without them redeploy.sh uses local images only). See "Container registry" below. |
| 5 Data + parallel run | Done, green. |
| 6 Cutover | Final stop/dump/restore/count-compare done, stack live on `192.168.1.110:8081` with Infisical-injected secrets. NPM upstream switch is a manual step. The old backend container is STOPPED on `app-book-dev` (`docker start book-backend` = rollback). |

Rollback after the NPM switch: point NPM back to the old VM and `ssh app-book-dev docker start book-backend`. Note data written
on the new stack after cutover is not copied back.

## Reference pattern (what I could audit)
- **esign-workflow-api (CT 109)**: unprivileged Debian LXC, nesting=1, Docker CE + compose plugin, repo cloned in `/opt/<app>`,
  non-committed `deploy/*.env` (mode 600), `deploy/redeploy.sh` = fetch, reset to origin/main, `docker compose up --build --wait`.
  Only one port published; NPM (CT 150, 192.168.1.50) terminates TLS and proxies to `<LXC-IP>:<port>`; `onboot=1`.
  No automatic rollback.
- **dealje (production, Hostinger/Infomaniak)**: `docker-compose.prod.yml` with capped json-file logs, pinned image digests,
  memory limits, DB bound to 127.0.0.1, `deploy/backup` (systemd timers + restore test), `deploy/db/rotate-password.sh`.
  Secrets come from a non-committed `.env.prod`. **No Infisical usage found in any local repo**, so the Infisical wiring here is new
  (Universal Auth machine identity, read-only), not copied.
- **Deviations from the brief, on purpose**: images live in the Gitea container registry (`192.168.1.103:3000/daniellaera`). The existing Gitea secret
  names (`VM_HOST`, `VM_USER`, `VM_SSH_PORT`, `SSH_PRIVATE_KEY`) are reused so cutover only means changing their values.

## Estimate
| | |
|---|---|
| LXC | 2 vCPU, 4 GB RAM, 20 GB disk, unprivileged, nesting=1, onboot=1 (stack limits: db 512 MB, qdrant 512 MB, backend 1.25 GB, frontend 128 MB) |
| Running cost | Homelab: electricity only. Comparison: Infomaniak VPS Lite 4 vCPU/8 GB about CHF 18/month. Anthropic: cents/month with Haiku at 20 req/day/user, cache and 15-book context. |
| Effort | Infisical project + identity 20 min, LXC 20 min, pipeline 30 min, data restore + reindex 20 min, parallel verification 30 min, cutover + 10 min watch 20 min: about 2.5 h once access exists. |

## Architecture
```
Internet -> router -> NPM (CT 150, 192.168.1.50) -> http://<LXC>:8081
LXC book-management (Docker): book-frontend (nginx, :8081) -> book-backend (:8080) -> db (postgres) , qdrant
                                                              \-> Ollama 192.168.1.148:11434 (external), Anthropic API
CI (Gitea Actions, runner VM 200): test -> build images -> push to Gitea registry -> scp compose+redeploy.sh -> ssh redeploy.sh <sha>
```

## Container registry (Gitea)
- Images: `192.168.1.103:3000/daniellaera/book-backend` and `.../book-frontend`, tagged `<git sha>` and `latest` (`REGISTRY` in the workflow, compose and redeploy.sh).
- Push (CI): the built-in Actions token (`secrets.GITHUB_TOKEN`, package write). If Gitea rejects it, set the Gitea secrets `GITEA_PUSH_USER` + `GITEA_PUSH_TOKEN` (token scope `write:package` only); the workflow prefers them when present.
- Pull (CT 110): `redeploy.sh` logs in with `GITEA_REGISTRY_USER` / `GITEA_REGISTRY_TOKEN` from Infisical and logs out on exit.
- One-time on CT 110: `deploy/setup-registry.sh` (insecure-registries reload + re-tag existing local images).
- The registry is plain HTTP, so Docker must list it as insecure on every host that pushes or pulls (CT 110 and the runner VM 200):
  `/etc/docker/daemon.json` -> `{"insecure-registries": ["192.168.1.103:3000"]}` (CT 110: `systemctl reload docker`).
- Maven: no private repository (Central only): no `distributionManagement`, no `mvn deploy` step, no `MAVEN_SETTINGS_XML` secret.

## Secrets
Source of truth: Infisical (192.168.1.163), project `book-management`, env `prod`.
| Secret | Origin |
|--------|--------|
| `DATABASE_USERNAME`, `GITHUB_CLIENT_ID`, `GITHUB_SECRET`, `GITHUB_REDIRECT_URI`, `FRONTEND_URL`, `OLLAMA_URL`, `ANTHROPIC_API_KEY` | migrate once from old `backend.env`, piped, never displayed |
| `DATABASE_PASSWORD` | NEW (`openssl rand -base64 36 | tr -d '/+=' `), replaces `testpass` |
| `JWT_SECRET` | NEW, `openssl rand -base64 64` (JwtService does `Decoders.BASE64.decode`, so Base64 is required; the old one is in a public repo = compromised) |
| `INDEX_API_TOKEN` | NEW, `openssl rand -hex 32` |
| `ANTHROPIC_MODEL` | `claude-haiku-5-5` |
| `GITEA_REGISTRY_USER`, `GITEA_REGISTRY_TOKEN` | Gitea user + personal access token with only `read:package`, used by redeploy.sh to pull images |
`QDRANT_URL` and `DATABASE_URL` are fixed in `deploy/compose.prod.yml` (internal service names).

Runtime access: Universal-Auth machine identity, read-only on this project/env. Credentials live only on the LXC in
`/root/.config/book-management/infisical.env` (mode 600) + `project_id`. They are not in git or Gitea.
Containers receive secrets as environment variables at `up` time (no `.env` on disk); they are visible to root via `docker inspect`.

**Rotate a secret:** change it in Infisical, then `ssh root@<LXC> /opt/book-management/deploy/redeploy.sh $(cat /var/lib/book-management/backend.tag)`.
(`DATABASE_PASSWORD` also needs `ALTER USER` in the db container first. Rotating `JWT_SECRET` logs everyone out.)
**Add a secret:** add it in Infisical, add its name under `book-backend.environment` in `deploy/compose.prod.yml`, commit, push, done.

## Runbook
- Deploy: push to `main` (CI) or manually `ssh root@<LXC> /opt/book-management/deploy/redeploy.sh <sha>`.
- Health: `curl -fsS http://<LXC>:8081/api/v3/book`; logs: `docker compose -f /opt/book-management/deploy/compose.prod.yml logs -f book-backend`.
- Rollback: automatic on a failed health check (previous tags in `/var/lib/book-management/*.tag`). Manual: run redeploy.sh with an older sha.
- Reindex Qdrant: `POST /api/v3/ai/books/index` with header `X-Index-Token` (value from Infisical `INDEX_API_TOKEN`).

## Cutover procedure (used; repeat for any re-do)
1. `ssh app-book-dev docker stop book-backend`. 2. On the LXC: `deploy/dump-old-db.sh` (needs `/dev/shm/olddb.env`, piped from the old VM backend.env),
`deploy/migrate-data.sh /root/dumps/latest.dump`, `deploy/count-rows.sh new` must equal `/root/dumps/latest.old.counts` or abort and restart the old container.
3. `deploy/redeploy.sh <tag>`, reindex, checks, switch NPM, watch logs 10 min.

## Check list used in the parallel run (`/root/e2e.sh` pattern)
1. Restore: on old VM `pg_dump -Fc --no-owner`, copy to LXC, `deploy/migrate-data.sh <dump>`.
2. Start stack, reindex, test via `curl -H 'Host: bookmanagement.daniellaera.com' http://<LXC>:8081/...` or a temporary NPM host.
3. Checks: signup/login, book CRUD, `/ask` twice (second `cached=true`, `AI usage ... cached=Y` log), anonymous `/ask/stream`, `/index` without token = 401/403, with token = 200, GitHub OAuth redirect URI.
4. Switch the NPM upstream, repeat checks on the public URL, watch logs 10 min; on any failure switch NPM back.
5. Keep the old VM untouched for 7 days.
