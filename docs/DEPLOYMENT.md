# book-management: deployment

Status of the migration from VM `<old-vm>` (+ separate DB host `<old-db-host>`) to LXC CT 110 `book-management`
(<app-host>, Debian 13, unprivileged, nesting, 2 vCPU / 4 GB / 20 GB on `<pve-node>`, pool `book-pool`, onboot) with Postgres in Docker.

## Status / blockers
| Step | State |
|------|-------|
| 0-1 Audit, land AI hardening | Done. 75 tests green (run in `maven:3.9.9-temurin-21` with the Docker socket; local JDK 25 breaks Lombok). |
| 2 Secrets | **Done**: 11 secrets in Infisical (project `book-management`, env `prod`), runtime identity `book-runtime` (Viewer). `DATABASE_URL` and `QDRANT_URL` are NOT in Infisical: fixed in `compose.prod.yml` (internal service names). The tmpfs staging files were shredded. |
| 3 LXC | **Done**: CT 110 (<app-host>, Debian 13, unprivileged, nesting, 2 vCPU/4 GB/20 GB, onboot). |
| 4 Pipeline | **Done and exercised** (Gitea Actions, main -> test -> build -> push to the Gitea registry -> deploy to CT 110 with `redeploy.sh <sha>`). Gitea repo secrets: `VM_HOST`=<app-host>, `VM_USER`=root, `VM_SSH_PORT`=22, `SSH_PRIVATE_KEY` (dedicated deploy key, authorised on CT 110), `REGISTRY_PUSH_USER` + `REGISTRY_PUSH_TOKEN` (package read+write). Pull side: `GITEA_REGISTRY_USER` / `GITEA_REGISTRY_TOKEN` (read:package) in Infisical. Registry `<registry-host>` is declared insecure on CT 110 and runner VM 200. |
| 5 Data + parallel run | Done, green. |
| 6 Cutover | Final stop/dump/restore/count-compare done, stack live on `<app-host>:8081` with Infisical-injected secrets. NPM upstream switch is a manual step. The old backend container is STOPPED on `<old-vm>` (`docker start book-backend` = rollback). |

Rollback after the NPM switch: point NPM back to the old VM and `ssh <old-vm> docker start book-backend`. Note data written
on the new stack after cutover is not copied back.

## Reference pattern (what I could audit)
- **esign-workflow-api (CT 109)**: unprivileged Debian LXC, nesting=1, Docker CE + compose plugin, repo cloned in `/opt/<app>`,
  non-committed `deploy/*.env` (mode 600), `deploy/redeploy.sh` = fetch, reset to origin/main, `docker compose up --build --wait`.
  Only one port published; NPM (CT 150, <proxy-host>) terminates TLS and proxies to `<LXC-IP>:<port>`; `onboot=1`.
  No automatic rollback.
- **dealje (production, Hostinger/Infomaniak)**: `docker-compose.prod.yml` with capped json-file logs, pinned image digests,
  memory limits, DB bound to 127.0.0.1, `deploy/backup` (systemd timers + restore test), `deploy/db/rotate-password.sh`.
  Secrets come from a non-committed `.env.prod`. **No Infisical usage found in any local repo**, so the Infisical wiring here is new
  (Universal Auth machine identity, read-only), not copied.
- **Deviations from the brief, on purpose**: images live in the Gitea container registry (`<registry-host>/<owner>`). The existing Gitea secret
  names (`VM_HOST`, `VM_USER`, `VM_SSH_PORT`, `SSH_PRIVATE_KEY`) are reused so cutover only means changing their values.

## Estimate
| | |
|---|---|
| LXC | 2 vCPU, 4 GB RAM, 20 GB disk, unprivileged, nesting=1, onboot=1 (stack limits: db 512 MB, qdrant 512 MB, backend 1.25 GB, frontend 128 MB) |
| Running cost | Homelab: electricity only. Comparison: Infomaniak VPS Lite 4 vCPU/8 GB about CHF 18/month. Anthropic: cents/month with Haiku at 20 req/day/user, cache and 15-book context. |
| Effort | Infisical project + identity 20 min, LXC 20 min, pipeline 30 min, data restore + reindex 20 min, parallel verification 30 min, cutover + 10 min watch 20 min: about 2.5 h once access exists. |

## Architecture
```
Internet -> router -> NPM (CT 150, <proxy-host>) -> http://<LXC>:8081
LXC book-management (Docker): book-frontend (nginx, :8081) -> book-backend (:8080) -> db (postgres) , qdrant
                                                              \-> Ollama <ollama-host>:11434 (external), Anthropic API
CI (Gitea Actions, runner VM 200): test -> build images -> push to Gitea registry -> scp compose+redeploy.sh -> ssh redeploy.sh <sha>
```

## Container registry (Gitea)
- Images: `<registry-host>/<owner>/book-backend` and `.../book-frontend`, tagged `<git sha>` and `latest` (`REGISTRY` in the workflow, compose and redeploy.sh).
- Push (CI): Gitea repo secrets `REGISTRY_PUSH_USER` + `REGISTRY_PUSH_TOKEN` (personal access token with package read+write). The built-in Actions token is rejected by the registry, and Gitea does not allow secret names starting with `GITEA_`.
- Pull (CT 110): `redeploy.sh` logs in with `GITEA_REGISTRY_USER` / `GITEA_REGISTRY_TOKEN` from Infisical and logs out on exit.
- One-time on CT 110: `deploy/setup-registry.sh` (insecure-registries reload + re-tag existing local images).
- The registry is plain HTTP, so Docker must list it as insecure on every host that pushes or pulls (CT 110 and the runner VM 200):
  `/etc/docker/daemon.json` -> `{"insecure-registries": ["<registry-host>"]}` (CT 110: `systemctl reload docker`).
- Maven: no private repository (Central only): no `distributionManagement`, no `mvn deploy` step, no `MAVEN_SETTINGS_XML` secret.

## CI Node.js requirement

Angular CLI 22, `@angular/build` and jsdom 31 require Node **^24.15.0 or >=26.0.0** (their `engines` fields; 25.x is not supported). The frontend test job therefore runs on the CI image tag `ci-java21-node24:bookworm-2` (Node 24.21.0, npm 11.19, Docker CLI 29.8.2 + buildx), as does the deploy job. The legacy tag `24.04-1` is Debian with Node 24.11.1 and must not be used for these jobs (the infra-ci-images README also says not to use it). A guard step in the frontend job prints `node -v` and fails with an explicit message if Node is older than 24.15.0.

- The image is defined in the `infra-ci-images` repo (Dockerfile with pinned versions and checksums). To change the Node version, bump `NODE_VERSION` + checksums there, publish a new immutable tag, then update the `container:` lines here.
- `detect-changes` and `build-and-test-backend` still use `24.04-1` (they need neither Node nor Angular). Moving the backend job to `bookworm-2` (Maven 3.9.16) is a separate change.
- The frontend Docker image build uses `node:24-slim` from Docker Hub and runs `docker build --pull`, so a stale cached base image cannot reintroduce an old Node.

## PrimeUI licence (frontend build)

PrimeNG 22 is no longer MIT: it needs a PrimeUI licence key, read at runtime via `providePrimeNG({ license })`. Without a valid key PrimeNG shows an "Invalid PrimeUI License" banner.

- **Licence:** Community (individual), valid until **2027-11-17**, renewable for free, with a 30-day grace period.
- **Not a server secret:** the key is verified client-side (signed token), so it ends up in the shipped JS bundle. It is still kept out of git, logs and docs.
- **How it is wired:** `frontend/scripts/generate-primeui-license.mjs` reads the `PRIMEUI_LICENSE` env var and writes `frontend/src/environments/primeui-license.generated.ts` (gitignored), imported by `app.config.ts`. It runs as the `prestart`, `prebuild`, `prewatch` and `pretest` npm hooks. `npm run build` (`--require`) **fails** if the variable is empty or missing; start/watch/test only warn and continue with an empty licence.
- **CI:** Gitea repo secret `PRIMEUI_LICENSE` (Repo > Settings > Actions > Secrets). The `dockerize-and-deploy` job has a guard step that fails before any image build or deploy if the secret is empty. The frontend image build receives it as a BuildKit secret (`--secret id=primeui_license,env=PRIMEUI_LICENSE`), not a build-arg or ENV, so it is not in the image history. `npm run test:scripts` runs the generator tests. A second guard step fails early with a clear message if the Docker client or daemon on the runner is older than 20.10 (needed for `RUN --mount=type=secret` and `--secret ...,env=`); the job image `ci-java21-node24:24.04-1` ships Docker client 20.10.24 without buildx, the daemon version on runner VM 200 is not recorded anywhere in the repo.
- **Local:** `export PRIMEUI_LICENSE="$(pbpaste)"` (key in the clipboard), then `npm start` / `npm run build` from `frontend/`.
- **Local Docker build:** `PRIMEUI_LICENSE=... docker build --secret id=primeui_license,env=PRIMEUI_LICENSE frontend/` (BuildKit).
- **Renewal reminder:** the licence expires on **2027-11-17**. Renew it at https://primeui.dev/licenses around **2027-10-17** (one month ahead), then update the Gitea secret `PRIMEUI_LICENSE` and redeploy. After expiry the 30-day grace period runs until about 2027-12-17.

## Secrets
Source of truth: Infisical (<secrets-host>), project `book-management`, env `prod`.
| Secret | Origin |
|--------|--------|
| `DATABASE_USERNAME`, `GITHUB_CLIENT_ID`, `GITHUB_SECRET`, `GITHUB_REDIRECT_URI`, `FRONTEND_URL`, `OLLAMA_URL`, `ANTHROPIC_API_KEY` | migrate once from old `backend.env`, piped, never displayed |
| `DATABASE_PASSWORD` | NEW (`openssl rand -base64 36 | tr -d '/+=' `), replaces `testpass` |
| `JWT_SECRET` | NEW, `openssl rand -base64 64` (JwtService does `Decoders.BASE64.decode`, so Base64 is required; the old one is in a public repo = compromised) |
| `INDEX_API_TOKEN` | NEW, `openssl rand -hex 32` |
| `MCP_API_TOKEN` | NEW, `openssl rand -hex 32`; Bearer token for the `/mcp` server (see `docs/AI.md`); empty/absent = `/mcp` answers 401 to everyone |
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
- Rollback: automatic on a failed health check (previous tags in `/var/lib/book-management/*.tag`). Manual: run redeploy.sh with an older tag. Older images stay on CT 110 under the Gitea names (e.g. `<registry-host>/<owner>/book-backend:local-features1`).
- MCP server: `POST /mcp` with `Authorization: Bearer <MCP_API_TOKEN>` (nginx in the frontend container proxies it to the backend). Connect Claude Code/Desktop and test: `docs/AI.md`.
- AI eval (17 questions, prints pass/fail, tokens, latency): `deploy/ai-eval.sh https://<your-domain> [--fresh]`.
- After the `fix/title-search` deploy the embedded text changed (lowercased): run the Qdrant reindex once (below) so documents match the lowercased queries; see `docs/AI.md`.
- Reindex Qdrant: automatic (nightly, plus on book create/delete, see `docs/AI.md`). Manual: `POST /api/v3/ai/books/index` with header `X-Index-Token` (value from Infisical `INDEX_API_TOKEN`). Check: `grep 'indexing done'` in the backend logs.

## Cutover procedure (used; repeat for any re-do)
1. `ssh <old-vm> docker stop book-backend`. 2. On the LXC: `deploy/dump-old-db.sh` (needs `/dev/shm/olddb.env`, piped from the old VM backend.env),
`deploy/migrate-data.sh <dump-dir>/latest.dump`, `deploy/count-rows.sh new` must equal `<dump-dir>/latest.old.counts` or abort and restart the old container.
3. `deploy/redeploy.sh <tag>`, reindex, checks, switch NPM, watch logs 10 min.

## Check list used in the parallel run (`/root/e2e.sh` pattern)
1. Restore: on old VM `pg_dump -Fc --no-owner`, copy to LXC, `deploy/migrate-data.sh <dump>`.
2. Start stack, reindex, test via `curl -H 'Host: <your-domain>' http://<LXC>:8081/...` or a temporary NPM host.
3. Checks: signup/login, book CRUD, `/ask` twice (second `cached=true`, `AI usage ... cached=Y` log), anonymous `/ask/stream`, `/index` without token = 401/403, with token = 200, GitHub OAuth redirect URI.
4. Switch the NPM upstream, repeat checks on the public URL, watch logs 10 min; on any failure switch NPM back.
5. Keep the old VM untouched for 7 days.
