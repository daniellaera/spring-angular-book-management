# Spring Boot & Angular Book Management Application

[![versionspringboot](https://img.shields.io/badge/springboot-4.0.2-brightgreen)](https://github.com/spring-projects/spring-boot)
[![versionjava](https://img.shields.io/badge/jdk-21-brightgreen.svg?logo=java)](https://github.com/spring-projects/spring-boot)
[![versionangular](https://img.shields.io/badge/angular-21-brightgreen.svg?logo=angular)](https://angular.dev/)
![Coverage](https://raw.githubusercontent.com/daniellaera/spring-vuejs-book-management/badges/badges/jacoco.svg)

<div style="width: 100%; margin: auto;">
  <img src="screenshots/banner.svg" alt="Book Management Stack" style="width: 100%;"/>
</div>

A full-stack book management application with an **AI-powered chat assistant**. Users can browse, create, rate, and comment on books, and interact with an intelligent library assistant that uses **semantic search** and **Claude AI** to answer questions about the book catalog.

**Live Demo:** [https://bookmanagement.daniellaera.com](https://bookmanagement.daniellaera.com)

---

## Architecture Overview

```mermaid
flowchart LR
    subgraph Clients
        B["Browser<br/>(Angular 21)"]
        M["MCP clients<br/>(Claude Code / Desktop)"]
    end
    P["Reverse proxy<br/>(TLS)"]
    subgraph App["Spring Boot backend"]
        API["REST API + AI chat"]
        MCP["MCP endpoint<br/>6 book-library tools"]
        IDX["Indexing<br/>(BookVectorIndexService)"]
    end
    subgraph Data["Data and AI services"]
        PG[("PostgreSQL")]
        Q[("Qdrant<br/>vectors")]
        O["Ollama<br/>embeddings"]
        C["Claude API"]
    end
    B --> P
    M --> P
    P --> API
    P --> MCP
    API --> PG
    API --> Q
    API --> C
    API -. "query embedding" .-> O
    MCP --> PG
    IDX --> PG
    IDX --> O
    IDX --> Q
```

*System overview: the browser and MCP clients reach one Spring Boot backend through the reverse proxy; the backend keeps Qdrant in sync with PostgreSQL itself.*

```mermaid
flowchart LR
    DEV["git push to main"] --> CI["Gitea Actions<br/>build + test"]
    CI --> REG["Gitea container<br/>registry"]
    REG --> DEP["Deploy script<br/>health check +<br/>auto-rollback"]
    INF["Infisical<br/>secrets"] -. "injected at start" .-> DEP
    DEP --> RUN["Running stack"]
```

*CI/CD: a push to main is tested, built, pushed to the registry and deployed; a failed health check rolls back to the previous image; secrets are pulled at start, never stored in CI.*

## Key Features

- **Book Management** — CRUD operations with pagination, sorting, and search
- **User Authentication** — JWT-based auth with GitHub OAuth2 login
- **Ratings & Comments** — Users can rate and comment on books
- **Book Borrowing** — Track book availability and borrowing history
- **AI Chat Assistant** — Conversational library assistant powered by Claude Haiku
- **Semantic Search** — Vector-based book search using Qdrant and Ollama embeddings
- **Automatic Indexing** — the backend keeps the Qdrant embeddings in sync: nightly scheduled run, immediate on-change indexing, manual endpoint
- **Cost Protection** — Rate limiting (per user/day), response caching, and feature toggles
- **Dark Mode** — Toggle between light and dark themes
- **Swagger UI** — Interactive API documentation at `/swagger-ui.html`

## Technologies

### Backend
- **Spring Boot 4.0.2** with Java 21
- **Spring Security** — JWT + OAuth2 (GitHub)
- **Spring Data JPA** — PostgreSQL with Flyway migrations
- **Spring AI** — Anthropic Claude integration (Haiku model)
- **WebFlux** — SSE streaming for real-time AI responses
- **SpringDoc OpenAPI 3** — Swagger UI
- **Quartz** — Scheduled tasks

### Frontend
- **Angular 21** with TypeScript
- **PrimeNG** — UI component library
- **Signals** — Reactive state management
- **SSE Streaming** — Real-time chat responses

### AI & Search
- **Claude Haiku** — Fast, low-cost LLM for natural language answers
- **Qdrant** — Vector database for semantic book search
- **Ollama** — Local embedding generation (nomic-embed-text model)

### Infrastructure
- **Docker** — Containerized deployment
- **Nginx Proxy Manager** — HTTPS reverse proxy
- **Gitea Actions** — CI/CD pipeline
- **Gitea container registry** — Docker images
- **Proxmox** — Self-hosted virtualization

---

## Project Structure

```
spring-book-management
├── backend/                → Spring Boot API
│   ├── src/main/java/
│   │   ├── config/         → Security, CORS, OpenAPI configs
│   │   ├── controller/     → REST controllers (Book, Auth, AI, etc.)
│   │   ├── model/          → JPA entities
│   │   ├── dao/            → DTOs
│   │   ├── repository/     → Spring Data repositories
│   │   ├── service/        → Business logic, AI services
│   │   └── properties/     → Configuration properties
│   ├── src/main/resources/
│   │   ├── db/migration/   → Flyway SQL migrations
│   │   ├── application.yml → Production config (env vars)
│   │   └── application-dev.yml → Local dev config
│   └── pom.xml
├── frontend/               → Angular 21 SPA
│   ├── src/app/
│   │   ├── components/     → UI components (chat-widget, navbar, etc.)
│   │   ├── services/       → HTTP services (book, auth, chat-ai)
│   │   ├── models/         → TypeScript interfaces
│   │   └── guards/         → Route guards
│   ├── angular.json
│   └── package.json
└── compose.yml             → Docker Compose (dev)
```

---

## Getting Started

### Prerequisites

- Java 21
- Node.js 24+
- Docker & Docker Compose
- Ollama (for local AI features)

### Local Development

**1. Start infrastructure:**

```bash
docker compose up -d   # PostgreSQL + Qdrant
```

**2. Pull the embedding model:**

```bash
ollama pull nomic-embed-text
```

**3. Start the backend:**

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**4. Start the frontend:**

```bash
cd frontend
npm install
export PRIMEUI_LICENSE="$(pbpaste)"   # PrimeUI licence key, see below (optional in dev: a banner is shown without it)
npm start
```

PrimeNG 22 needs a PrimeUI licence key. `npm start`/`npm test` work without it (warning only); `npm run build` and the Docker build fail without it. Details, CI secret and renewal date (2027-11-17): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#primeui-licence-frontend-build).

**5. Access the application** at `http://localhost:4200`

### Environment Variables

The backend requires these environment variables (or use `application-dev.yml` for local dev):

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | Database username |
| `DATABASE_PASSWORD` | Database password |
| `JWT_SECRET` | JWT signing secret |
| `FRONTEND_URL` | Frontend URL for CORS |
| `GITHUB_CLIENT_ID` | GitHub OAuth client ID |
| `GITHUB_SECRET` | GitHub OAuth secret |
| `GITHUB_REDIRECT_URI` | GitHub OAuth redirect URI |
| `ANTHROPIC_API_KEY` | Anthropic API key for Claude |
| `QDRANT_URL` | Qdrant vector DB URL |
| `OLLAMA_URL` | Ollama API URL |
| `ANTHROPIC_MODEL` | Optional. Claude model (default `claude-haiku-5-5`) |
| `INDEX_API_TOKEN` | Optional. Token for the manual `/index` call (see below); endpoint token auth is disabled when unset |
| `APP_INDEXING_SCHEDULED_ENABLED` | Optional, default `true`. Nightly full reindex |
| `APP_INDEXING_SCHEDULED_CRON` | Optional, default `0 0 0 * * *` (Spring 6-field cron) |
| `APP_INDEXING_SCHEDULED_ZONE` | Optional, default empty = JVM default time zone (e.g. `Europe/Zurich`) |
| `APP_INDEXING_ON_CHANGE_ENABLED` | Optional, default `true`. Index/remove a book right after it is created/deleted |

---

## AI Chat Assistant

The AI chat assistant uses a **RAG (Retrieval-Augmented Generation)** pattern:

1. **User asks a question** in the chat widget
2. **Ollama** generates an embedding for the question (free, local)
3. **Qdrant** finds the most relevant books via vector similarity search (free)
4. **Claude Haiku** receives only the relevant books and generates a natural language answer (low cost)
5. **Cache** stores responses — identical questions are served instantly at zero cost

### Cost Protection

- **Rate Limiting** — `ai.daily-limit` per user; `ai.anonymous-daily-limit` (default 5) per client IP for the public `/ask/stream`
- **Response Caching** — bounded LRU (500 entries), 24h TTL, normalised questions
- **Feature Toggle** — AI can be enabled/disabled via config
- **Minimal Context** — Only relevant books sent to Claude (not entire catalog)

### Indexing (Qdrant embeddings)

The Spring app keeps the vector database in sync itself (the former n8n "book-index" workflow is obsolete):

| Trigger | When | What |
|---------|------|------|
| `scheduled` | nightly, `app.indexing.scheduled.cron` (default `0 0 0 * * *`, JVM time zone unless `zone` is set) | incremental full run |
| `event` | right after a book is created or deleted (`app.indexing.on-change.enabled`) | only that book: upsert its point, or delete it |
| `manual` | `POST /api/v3/ai/books/index` | same incremental full run as the nightly one |

Full runs are guarded in-JVM: if one is already running, the next one is skipped and logged. On-change indexing runs after the
DB commit on a dedicated single-thread executor; if Ollama or Qdrant are down it logs one WARN line and the nightly run catches up.
Details and log lines to grep: `docs/AI.md`.

```mermaid
flowchart TB
    subgraph Triggers
        S["Nightly @Scheduled<br/>full reindex"]
        E["Book created / deleted<br/>AFTER_COMMIT event"]
        X["POST /api/v3/ai/books/index<br/>(JWT or index token)"]
    end
    EX["Async single-thread<br/>executor"]
    E --> EX
    subgraph SVC["BookVectorIndexService"]
        G{"Full run<br/>already running?"}
        H["Content-hash skip<br/>(unchanged books)"]
        U["Upsert point<br/>id = book id"]
    end
    S --> G
    X --> G
    G -- "yes: skip + log" --> Z["Done"]
    G -- "no" --> H
    EX -- "indexBook(id)" --> U
    H --> U
    U --> O["Ollama<br/>embedding"]
    U --> Q[("Qdrant")]
```

*Indexing flow: three triggers share one service; the n8n workflow it replaces is removed. Full runs are guarded and hash-skipped, single-book runs upsert by book id, so repeats never duplicate.*

```mermaid
sequenceDiagram
    actor U as User
    participant API as Backend
    participant DB as PostgreSQL
    participant L as Index listener (async)
    participant O as Ollama
    participant Q as Qdrant
    U->>API: POST /api/v3/book
    API->>DB: save book (commit)
    API-->>U: 201 Created
    API--)L: BookChangedEvent (after commit)
    L->>O: embed book text
    L->>Q: upsert point (id = book id)
    Note over U,Q: Book is now found by semantic search
    alt Ollama or Qdrant down
        L->>L: log WARN (no stack trace)
        Note over U,L: Request already succeeded - the nightly run catches up
    end
```

*Book created to searchable: indexing happens after the response, and a failure is only logged; the nightly full run repairs it.*


### Manual indexing endpoint

`POST /api/v3/ai/books/index` requires the `USER` authority (browser JWT) **or** the header
`X-Index-Token` whose value equals the backend env var `INDEX_API_TOKEN`:

```
POST /api/v3/ai/books/index
X-Index-Token: <value of INDEX_API_TOKEN>
```

- The token is compared in constant time and only unlocks this single endpoint.
- If `INDEX_API_TOKEN` is unset/empty, token auth is disabled.
- Indexing is incremental: only new/changed books are re-embedded (SHA-256 `contentHash` in the Qdrant payload),
  points of deleted books are removed. Response: `{"result": "...", "indexed": n, "skipped": n, "deleted": n, "failed": n}`.

---

## API Endpoints

### Authentication
| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v3/auth/signup` | Public | Register new user |
| POST | `/api/v3/auth/signin` | Public | Login, returns JWT |
| GET | `/api/v3/auth/me` | USER | Get current user details |

### Books
| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/v3/book` | Public | List books (paginated) |
| GET | `/api/v3/book/{id}` | Public | Get book by ID |
| POST | `/api/v3/book` | USER | Create book |
| PUT | `/api/v3/book/{id}` | USER | Update book |
| DELETE | `/api/v3/book/{id}` | USER | Delete book |

### AI Assistant
| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/v3/ai/books/ask/stream` | Public | Stream AI response (SSE) |
| POST | `/api/v3/ai/books/ask` | USER | Get AI response |
| POST | `/api/v3/ai/books/search` | Public | Semantic book search |
| POST | `/api/v3/ai/books/index` | Public | Trigger book indexing |
| GET | `/api/v3/ai/books/remaining` | USER | Check remaining daily requests |

### Swagger UI

Interactive API documentation is available at:
- **Local:** `http://localhost:8080/swagger-ui/index.html`
- **Production:** `https://bookmanagement.daniellaera.com/swagger-ui/index.html`

To use protected endpoints in Swagger:
1. Call `POST /api/v3/auth/signin` with `{ "email": "...", "password": "..." }`
2. Copy the `token` from the response
3. Click the **Authorize** 🔓 button at the top of Swagger UI
4. Enter the token and click **Authorize**
5. All subsequent requests will include the JWT automatically

---

## Design system

| Dark | Light |
|---|---|
| ![Library, dark](docs/screenshots/books-dark.jpg) | ![Library, light](docs/screenshots/books-light.jpg) |
| ![Book detail, dark](docs/screenshots/book-detail-dark.jpg) | ![Book detail, light](docs/screenshots/book-detail-light.jpg) |

Dark-first UI with a warm-neutral surface scale, one amber accent, a teal accent reserved for AI features, Fraunces (display) and Inter (UI), both self-hosted via `@fontsource-variable`.

- **Tokens live in one file:** `frontend/src/theme/app-preset.ts` (a PrimeNG `definePreset(Aura, …)`). Accent scale, surfaces, radii, fonts and the `--p-app-*` CSS variables used by all components are defined there; change the `ACCENT` scale, `RADIUS` or `FONT` constants to re-skin the app.
- **Fonts:** also swap the two `@fontsource-variable/*` imports at the top of `frontend/src/styles.scss`.
- **Global styles:** `frontend/src/styles.scss` (base typography, focus rings, chips/badges, form layout, reduced-motion rules). It contains no colours.
- **Generated covers:** per-genre hues are in `frontend/src/app/services/book-visuals.ts`.
- **Theme toggle:** `ThemeService` persists `theme` in localStorage and toggles the `my-app-dark` class on `<html>`.

## Deployment

### Production Stack (Docker Compose)

```yaml
services:
  backend:
    image: 192.168.1.103:3000/daniellaera/book-backend:latest
    env_file: ./backend.env
    ports: ["8080:8080"]
  frontend:
    image: 192.168.1.103:3000/daniellaera/book-frontend:latest
    ports: ["80:80"]
  qdrant:
    image: qdrant/qdrant
    ports: ["6333:6333"]
    volumes: [qdrant-data:/qdrant/storage]
```

### CI/CD Pipeline (Gitea Actions)

On push to `main`:
1. Detect changed files (backend/frontend)
2. Build and test changed modules
3. Check the `PRIMEUI_LICENSE` secret is set (fails the deploy otherwise), build Docker images and push to the Gitea container registry
4. SSH to server → pull images → `docker compose down && up -d`

---

## Testing

```bash
# Backend tests (with Testcontainers)
cd backend && ./mvnw test

# Frontend tests
cd frontend && npm run test -- --watch=false
```

---

## Database Migrations

Flyway manages schema versioning. Never modify applied migrations — create new ones:

```
backend/src/main/resources/db/migration/
├── V1__init.sql
├── V2__add_column.sql
└── V3__add_ratings.sql
```

---

## License

This project is for educational and portfolio purposes.