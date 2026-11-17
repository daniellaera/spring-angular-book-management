# Book Management: Spring Boot, Angular and an AI library assistant

[![Spring Boot](https://img.shields.io/badge/springboot-4.0.2-brightgreen)](https://github.com/spring-projects/spring-boot)
[![Java](https://img.shields.io/badge/jdk-21-brightgreen.svg?logo=java)](https://openjdk.org/projects/jdk/21/)
[![Angular](https://img.shields.io/badge/angular-22-brightgreen.svg?logo=angular)](https://angular.dev/)

![Book Management stack](screenshots/banner.svg)

A full-stack library app (browse, rate, comment, borrow) with an **AI assistant that answers by calling read-only tools** against the catalogue, and the same tools exposed to external agents through an **MCP server**. Semantic search runs on **Spring AI + Qdrant + local Ollama embeddings**; the chat model is **Claude Haiku 5.5**.

**Live demo:** <https://bookmanagement.daniellaera.com>

| Dark | Light |
|---|---|
| ![Library, dark](docs/screenshots/books-dark.jpg) | ![Library, light](docs/screenshots/books-light.jpg) |

*Screenshots show demo data.*

## Key features

- **Catalogue**: CRUD with pagination, sorting and search; ratings, comments and borrowing.
- **Auth**: JWT with refresh tokens, GitHub OAuth2 login.
- **AI chat assistant**: tool-calling agent (max 3 tool calls per question), streamed over SSE.
- **Semantic search**: book embeddings (`nomic-embed-text` via Ollama) in Qdrant.
- **MCP server**: six read-only tools for Claude Code / Claude Desktop, bearer-token protected.
- **Automatic indexing**: nightly run, on-change indexing after create/delete, manual endpoint.
- **Cost protection**: per-user and per-IP daily limits, response cache, feature toggle.
- **Dark and light theme**, Swagger UI in local development.

## Architecture

```mermaid
flowchart TB
    subgraph Clients
        direction LR
        B["Browser<br/>Angular SPA"]:::client
        M["MCP clients<br/>Claude Code / Desktop"]:::ai
    end

    P["Reverse proxy · TLS · nginx"]:::edge

    subgraph App["Spring Boot backend"]
        direction LR
        API["REST API<br/>CRUD · auth · chat SSE"]:::crud
        AG["Chat agent<br/>max 3 tool calls"]:::ai
        MCP["MCP server<br/>bearer token"]:::ai
        T["BookTools<br/>6 read-only tools"]:::ai
        IDX["Indexer<br/>nightly · on change"]:::ai
    end

    subgraph Data
        direction LR
        PG[("PostgreSQL")]:::data
        Q[("Qdrant<br/>vectors")]:::data
    end

    subgraph Models["AI runtime"]
        direction LR
        O["Ollama<br/>nomic-embed-text"]:::ai
        C["Claude Haiku 5.5<br/>Anthropic API"]:::ai
    end

    B -- "HTTPS" --> P
    M -- "MCP / HTTPS" --> P
    P -- "REST · SSE" --> API
    P -- "MCP" --> MCP
    API -- "SQL" --> PG
    API --> AG
    AG -- "tool loop" --> C
    AG --> T
    MCP --> T
    T -- "SQL" --> PG
    T -- "vector search" --> Q
    T -- "query embedding" --> O
    IDX -- "read books" --> PG
    IDX -- "embeddings" --> O
    IDX -- "upsert vectors" --> Q

    classDef client fill:#64748b22,stroke:#64748b,stroke-width:2px
    classDef edge fill:#64748b22,stroke:#64748b,stroke-width:2px
    classDef crud fill:#3b82f622,stroke:#3b82f6,stroke-width:2px
    classDef data fill:#f59e0b22,stroke:#f59e0b,stroke-width:2px
    classDef ai fill:#14b8a622,stroke:#14b8a6,stroke-width:2px
    linkStyle 0,2,4 stroke:#3b82f6,stroke-width:2px
    linkStyle 1,3,5,6,7,8,9,10,11,12,13,14 stroke:#14b8a6,stroke-width:2px
```

*Blue is the classic CRUD path, teal the AI path. The chat agent and the MCP server share the same six tools; the backend keeps Qdrant in sync with PostgreSQL itself.*

## AI

### How the assistant answers

The assistant does not receive a dump of the catalogue. It gets a short system prompt and decides which tool to call:

| Tool | Purpose |
|------|---------|
| `search_books` | semantic search by topic or theme (Qdrant) |
| `find_by_title` | exact/partial title lookup |
| `get_book` | full details by id |
| `count_books` | exact totals: all, available, borrowed |
| `list_by_author` | books by author |
| `list_by_genre` | books by genre |

Flow per question: cache lookup, rate limit, Claude Haiku 5.5 with at most 3 tool calls (`ai.max-tool-calls`), answer cached for 24 h. Tools return public catalogue data only, never user data. Details, cost notes and the eval: [docs/AI.md](docs/AI.md).

### MCP server

`POST /mcp` (stateless streamable HTTP, Spring AI MCP server) exposes the same six tools, authenticated with `Authorization: Bearer <MCP_API_TOKEN>` and rate limited (60 requests/minute). The endpoint is closed for everyone when no token is configured. Connection steps for Claude Code and Claude Desktop: [docs/AI.md](docs/AI.md#mcp-server).

### Indexing triggers

| Trigger | When | What |
|---------|------|------|
| `scheduled` | nightly, `app.indexing.scheduled.cron` | incremental full run (content-hash skip) |
| `event` | right after a book is created or deleted | only that book |
| `manual` | `POST /api/v3/ai/books/index` (JWT or `X-Index-Token`) | same run as the nightly one |

```mermaid
flowchart TB
    subgraph Triggers
        S["Nightly @Scheduled<br/>full reindex"]:::ai
        E["Book created / deleted<br/>AFTER_COMMIT event"]:::ai
        X["POST /api/v3/ai/books/index<br/>JWT or index token"]:::crud
    end
    EX["Async single-thread<br/>executor"]:::edge
    E --> EX
    subgraph SVC["BookVectorIndexService"]
        G{"Full run<br/>already running?"}:::edge
        H["Content-hash skip<br/>unchanged books"]:::ai
        U["Upsert point<br/>id = book id"]:::ai
    end
    S --> G
    X --> G
    G -- "yes: skip + log" --> Z["Done"]:::edge
    G -- "no" --> H
    EX -- "indexBook(id)" --> U
    H --> U
    U -- "embedding" --> O["Ollama"]:::ai
    U -- "vectors" --> Q[("Qdrant")]:::data

    classDef edge fill:#64748b22,stroke:#64748b,stroke-width:2px
    classDef crud fill:#3b82f622,stroke:#3b82f6,stroke-width:2px
    classDef data fill:#f59e0b22,stroke:#f59e0b,stroke-width:2px
    classDef ai fill:#14b8a622,stroke:#14b8a6,stroke-width:2px
```

*Three triggers share one service. Full runs are guarded and hash-skipped; single-book runs upsert by book id, so repeats never duplicate.*

### AI roadmap (planned, not implemented)

None of the following exists in the code yet:

- Evals in CI (today there is a manual script, `deploy/ai-eval.sh`)
- Tracing of agent tool calls with a self-hosted Langfuse
- Local Apertus 4B versus API models comparison
- Hybrid search plus reranking
- Prompt-injection hardening

## Tech stack

| Layer | Technologies |
|-------|--------------|
| Backend | Spring Boot 4.0.2, Java 21, Spring Security (JWT, GitHub OAuth2), Spring Data JPA, Flyway, WebFlux (SSE), Quartz, SpringDoc OpenAPI |
| AI | Spring AI 2.0.0-M2 (Anthropic, MCP server), Claude Haiku 5.5, Qdrant, Ollama (`nomic-embed-text`) |
| Frontend | Angular 22, TypeScript, PrimeNG 22, signals, SSE streaming |
| Data | PostgreSQL |
| Platform | Docker, nginx, Gitea Actions, Gitea container registry, Infisical |

## Quick start

Prerequisites: Java 21, Node.js 24+, Docker with Compose, [Ollama](https://ollama.com), an Anthropic API key.

```bash
# 1. PostgreSQL + Qdrant
cd backend && docker compose -f docker-compose.db.yml up -d

# 2. Embedding model
ollama pull nomic-embed-text

# 3. Backend (dev profile, http://localhost:8080)
export SPRING_AI_ANTHROPIC_API_KEY=<your-anthropic-key>
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 4. Frontend (new terminal, http://localhost:4200)
cd frontend && npm install && npm start
```

- `npm start` and `npm test` work without a PrimeUI licence key (a banner is shown); `npm run build` needs one. See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#primeui-licence-frontend-build).
- Swagger UI (dev only): `http://localhost:8080/swagger-ui.html`. Sign in via `POST /api/v3/auth/signin`, then use **Authorize** with the returned token.
- Tests: `cd backend && ./mvnw test` (Testcontainers, needs Docker) and `cd frontend && npm run test -- --watch=false`.

<details>
<summary>Backend environment variables (production profile)</summary>

| Variable | Description |
|----------|-------------|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `JWT_SECRET` | JWT signing secret |
| `FRONTEND_URL` | Frontend URL for CORS |
| `GITHUB_CLIENT_ID`, `GITHUB_SECRET`, `GITHUB_REDIRECT_URI` | GitHub OAuth app |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `ANTHROPIC_MODEL` | Optional, default `claude-haiku-5-5` |
| `QDRANT_URL`, `OLLAMA_URL` | Vector DB and embedding service |
| `MCP_API_TOKEN` | Optional. Bearer token for `/mcp`; the endpoint answers 401 when empty |
| `INDEX_API_TOKEN` | Optional. `X-Index-Token` for the manual index call |
| `APP_INDEXING_SCHEDULED_ENABLED`, `APP_INDEXING_SCHEDULED_CRON`, `APP_INDEXING_SCHEDULED_ZONE` | Optional nightly run settings (defaults in `application.yml`) |
| `APP_INDEXING_ON_CHANGE_ENABLED` | Optional, default `true` |

</details>

<details>
<summary>API overview</summary>

| Method | Endpoint | Auth |
|--------|----------|------|
| POST | `/api/v3/auth/signup`, `/signin`, `/refreshToken` | Public |
| GET | `/api/v3/auth/me` | USER |
| GET | `/api/v3/book`, `/api/v3/book/{id}` | Public |
| POST / DELETE | `/api/v3/book`, `/api/v3/book/{id}` | USER |
| GET | `/api/v3/ai/books/ask/stream` | Public (per-IP limit) |
| POST | `/api/v3/ai/books/ask` | USER |
| POST | `/api/v3/ai/books/search` | Public |
| POST | `/api/v3/ai/books/index` | USER or `X-Index-Token` |
| GET | `/api/v3/ai/books/remaining` | USER |
| POST | `/mcp` | Bearer `MCP_API_TOKEN` |

Comments, ratings and borrowing endpoints: see Swagger UI.

</details>

## CI/CD and deployment

A push to `main` runs the changed modules' tests, builds and pushes Docker images, and deploys with a health check and automatic rollback; secrets are injected at start and never stored in CI.

```mermaid
flowchart LR
    DEV["git push to main"]:::edge --> CI["Gitea Actions<br/>build + test"]:::crud
    CI --> REG["Container<br/>registry"]:::crud
    REG --> DEP["Deploy script<br/>health check +<br/>auto-rollback"]:::crud
    INF["Secret manager<br/>Infisical"]:::data -. "injected at start" .-> DEP
    DEP --> RUN["Running stack"]:::edge

    classDef edge fill:#64748b22,stroke:#64748b,stroke-width:2px
    classDef crud fill:#3b82f622,stroke:#3b82f6,stroke-width:2px
    classDef data fill:#f59e0b22,stroke:#f59e0b,stroke-width:2px
```

Runbook, secrets, rollback and the PrimeUI licence: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Design system

Dark-first UI with a warm-neutral surface scale, one amber accent, a teal accent reserved for AI features, Fraunces (display) and Inter (UI), self-hosted via `@fontsource-variable`.

| Dark | Light |
|---|---|
| ![Book detail, dark](docs/screenshots/book-detail-dark.jpg) | ![Book detail, light](docs/screenshots/book-detail-light.jpg) |

- Tokens live in one file: `frontend/src/theme/app-preset.ts` (a PrimeNG `definePreset(Aura, ...)`).
- Global styles: `frontend/src/styles.scss`; generated cover hues: `frontend/src/app/services/book-visuals.ts`.
- `ThemeService` persists the theme in localStorage and toggles the `my-app-dark` class on `<html>`.

## Database migrations

Flyway manages the schema in `backend/src/main/resources/db/migration/` (`V1` to `V13`). Never edit an applied migration; add a new one.

## License

No license file is included in this repository, so all rights are reserved by default. The project is shared for educational and portfolio purposes; contact the author for other uses.
