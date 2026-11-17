# Deployment

How the production stack is built, shipped and operated. Host names, addresses and credentials are intentionally generic
(`<prod-host>`, `<registry-host>`, `<secrets-host>`, `<owner>`); the real values live in CI settings and the secret manager, never in git.

## Architecture

```
Internet -> TLS reverse proxy -> http://<prod-host>:<port>
Docker host: frontend (nginx) -> backend (:8080) -> db (PostgreSQL), qdrant
                                    \-> Ollama (separate host), Anthropic API
CI (Gitea Actions): test -> build images -> push to the container registry -> copy compose + redeploy.sh -> ssh redeploy.sh <sha>
```

- One port is published on the Docker host; the database and Qdrant are only reachable inside the compose network.
- Resource limits in `deploy/compose.prod.yml`: db 512 MB, qdrant 512 MB, backend 1.25 GB, frontend 128 MB; json-file logs are capped.
- Ollama runs outside the stack and is reached through `OLLAMA_URL`.

## Pipeline

On push to `main` (`.gitea/workflows/main.yml`):

1. Detect which parts changed (backend, frontend, deploy files).
2. Build and test the changed modules.
3. Build the Docker images and push them to the container registry, tagged `<git sha>` and `latest`.
4. Copy `deploy/` to the Docker host and run `deploy/redeploy.sh <sha>` over SSH.

`redeploy.sh` pulls the secrets from the secret manager, pulls the images, starts the stack and runs a health check. If the
health check fails it rolls back to the previously deployed tags (kept in a state directory on the host).

### Repository settings the pipeline expects

| Name | Purpose |
|------|---------|
| `PRIMEUI_LICENSE` | frontend build licence key (see below) |
| `REGISTRY_PUSH_USER`, `REGISTRY_PUSH_TOKEN` | registry credentials with package read+write |
| `VM_HOST`, `VM_USER`, `VM_SSH_PORT`, `SSH_PRIVATE_KEY` | SSH target and a dedicated deploy key |
| `GIT_USERNAME`, `GIT_PASSWORD` | registry login for the CI job container |

### Container registry

- Images: `<registry-host>/<owner>/book-backend` and `.../book-frontend`.
- The built-in Actions token is not accepted by the registry, so a personal access token is used for pushing.
  Gitea does not allow secret names starting with `GITEA_`.
- If the registry is plain HTTP, every Docker host that pushes or pulls must list it under `insecure-registries` in
  `/etc/docker/daemon.json`. `deploy/setup-registry.sh` does this on the deploy host.
- Maven uses Maven Central only: no `distributionManagement`, no `mvn deploy` step.

### CI Node.js requirement

Angular CLI 22, `@angular/build` and jsdom 31 require Node **^24.15.0 or >=26.0.0** (25.x is not supported). The frontend test
and deploy jobs therefore run on a CI image tag that ships Node 24.21 and a recent Docker CLI with buildx; the older tag used by
the backend jobs ships Node 24.11 and must not be used for the frontend. A guard step prints `node -v` and fails with an explicit
message if Node is older than 24.15.0. To change the Node version, build a new immutable CI image tag and update the `container:`
lines in the workflow. The frontend image build uses `node:24-slim` and `docker build --pull`, so a stale cached base image
cannot reintroduce an old Node.

## PrimeUI licence (frontend build)

PrimeNG 22 needs a PrimeUI licence key, read at runtime via `providePrimeNG({ license })`. Without a valid key PrimeNG shows an
"Invalid PrimeUI License" banner.

- **Licence:** Community (individual), valid until **2027-11-17**, renewable for free, with a 30-day grace period.
- **Not a server secret:** the key is verified client-side (signed token), so it ends up in the shipped JS bundle. It is still kept out of git, logs and docs.
- **How it is wired:** `frontend/scripts/generate-primeui-license.mjs` reads the `PRIMEUI_LICENSE` env var and writes `frontend/src/environments/primeui-license.generated.ts` (gitignored), imported by `app.config.ts`. It runs as the `prestart`, `prebuild`, `prewatch` and `pretest` npm hooks. `npm run build` (`--require`) **fails** if the variable is empty or missing; start/watch/test only warn and continue with an empty licence.
- **CI:** repository secret `PRIMEUI_LICENSE`. The deploy job has a guard step that fails before any image build or deploy if it is empty. The frontend image build receives it as a BuildKit secret (`--secret id=primeui_license,env=PRIMEUI_LICENSE`), not a build-arg or ENV, so it is not in the image history. A second guard step fails early if the Docker client or daemon on the runner is older than 20.10 (needed for `RUN --mount=type=secret`). `npm run test:scripts` runs the generator tests.
- **Local:** `export PRIMEUI_LICENSE=<key>`, then `npm start` / `npm run build` from `frontend/`.
- **Local Docker build:** `PRIMEUI_LICENSE=<key> docker build --secret id=primeui_license,env=PRIMEUI_LICENSE frontend/` (BuildKit).
- **Renewal:** renew at <https://primeui.dev/licenses> around **2027-10-17**, update the repository secret and redeploy.

## Secrets

Source of truth: a self-hosted Infisical instance (`<secrets-host>`), project `<infisical-project-id>`, environment `prod`.
`redeploy.sh` logs in with a read-only machine identity whose credentials exist only on the deploy host (mode 600, never in git or CI)
and starts the stack through `infisical run`, so containers get secrets as environment variables at `up` time and no `.env` file is written.

| Secret | Notes |
|--------|-------|
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | generate a long random password (`openssl rand -base64 36`) |
| `JWT_SECRET` | Base64 is required (`openssl rand -base64 64`); rotating it logs everyone out |
| `GITHUB_CLIENT_ID`, `GITHUB_SECRET`, `GITHUB_REDIRECT_URI` | GitHub OAuth app |
| `FRONTEND_URL`, `OLLAMA_URL` | public URL for CORS; embedding service |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` | chat model (`claude-haiku-5-5`) |
| `INDEX_API_TOKEN` | optional, `openssl rand -hex 32`; `X-Index-Token` for the manual index call |
| `MCP_API_TOKEN` | optional, `openssl rand -hex 32`; Bearer token for `/mcp` (see [AI.md](AI.md)); empty = `/mcp` answers 401 to everyone |
| registry pull user and token | read-only package access, used by `redeploy.sh` to pull images |

`DATABASE_URL` and `QDRANT_URL` are fixed in `deploy/compose.prod.yml` (internal service names).

**Rotate a secret:** change it in the secret manager, then re-run `deploy/redeploy.sh` with the currently deployed tag
(`DATABASE_PASSWORD` also needs an `ALTER USER` in the db container first).
**Add a secret:** add it to the secret manager, add its name under `backend.environment` in `deploy/compose.prod.yml`, commit and push.

## Runbook

- **Deploy:** push to `main`, or run `deploy/redeploy.sh <sha>` on the host.
- **Health:** `curl -fsS http://<prod-host>:<port>/api/v3/book`; logs: `docker compose -f deploy/compose.prod.yml logs -f book-backend`.
- **Rollback:** automatic on a failed health check; manually run `redeploy.sh` with an older tag.
- **MCP server:** `POST /mcp` with `Authorization: Bearer <MCP_API_TOKEN>`; connection steps and checks in [AI.md](AI.md#mcp-server).
- **AI eval:** `deploy/ai-eval.sh https://bookmanagement.daniellaera.com [--fresh]` (17 questions; prints pass/fail, tokens, latency).
- **Reindex Qdrant:** automatic (nightly and on book create/delete, see [AI.md](AI.md)). Manual: `POST /api/v3/ai/books/index` with
  header `X-Index-Token`. Check with `grep 'indexing done'` in the backend logs. After a change to the embedded text, run one reindex.

## Migrating from another host (checklist)

1. Stop the old backend, dump the old database (`deploy/dump-old-db.sh`), restore it (`deploy/migrate-data.sh <dump>`).
2. Compare row counts (`deploy/count-rows.sh`) before and after; abort and restart the old backend if they differ.
3. Run `deploy/redeploy.sh <tag>`, reindex, then verify: sign-up/login, book CRUD, `/ask` twice (second answer cached), anonymous
   `/ask/stream`, `/index` without token = 401/403 and with token = 200, GitHub OAuth redirect URI.
4. Switch the reverse proxy upstream, repeat the checks on the public URL and watch the logs; switch back on any failure.
5. Keep the old host untouched for a week.
