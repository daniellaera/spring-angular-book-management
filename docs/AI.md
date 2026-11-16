# AI assistant: architecture, tools, MCP

The library assistant (`POST /api/v3/ai/books/ask`, `GET /api/v3/ai/books/ask/stream`) is a Claude Haiku 5.5 chat
(`spring.ai.anthropic`, no temperature/top-p/top-k) that answers by **calling read-only tools** instead of receiving
a dump of books in the prompt. The same tools are exposed to external agents through an **MCP server**.

```
                         +--------------------------------------+
 browser  -> /api/ask ---> BookAiController                      |
                         |  cache -> rate limit -> ChatClient    |
                         |        system prompt (no book data)   |
                         |        tools: BookTools (3 calls max) |---> BookToolsService ---> BookService / BookRepository (PostgreSQL)
 Claude Code / Desktop   |                                       |                      \--> QdrantSearchService (semantic search, Ollama embeddings)
   -> /mcp (Bearer) ---> McpTokenFilter -> MCP server (stateless  |
                         |   streamable HTTP) -> BookTools (same) |
                         +--------------------------------------+
```

## Tools (defined once in `ai/BookTools.java`, logic in `ai/BookToolsService.java`)
All read-only, public catalogue data only (no comments, borrowers or any user data), no raw SQL (services/repositories).
Output is compact text: `id|title|author|rating|available(Y/N)`.

| Tool | Arguments | Returns |
|------|-----------|---------|
| `search_books` | `query`, `limit` (1-15, default 8), `availableOnly` | semantic matches (Qdrant relevance order); empty query + `availableOnly=true` lists borrowable books |
| `get_book` | `id` | id, title, author, genre, ISBN, published, rating, availability, description (300 chars max) |
| `count_books` | none | `total=N;available=A;borrowed=B` (exact) |
| `list_by_author` | `author`, `limit` | exact total + up to `limit` rows (case-insensitive partial match) |
| `list_by_genre` | `genre`, `limit` | exact total + up to `limit` rows |

## Chat flow
1. `AiCacheService` (24 h, normalised question) -> hit = 0 tokens.
2. Rate limit: authenticated users `ai.daily-limit` per day, anonymous clients `ai.anonymous-daily-limit` per IP (stream endpoint only).
3. `ChatClient` with the short system prompt and a **fresh `BookTools` per question**, which allows at most
   `ai.max-tool-calls` (3) calls; further calls get "Tool call limit reached". `max-tokens` is 1024.
4. The answer is cached and the call is logged: `AI usage user=... model=... in=... out=... cached=N`.
   `/ask` also returns `inputTokens`, `outputTokens`, `toolCalls`.
The anonymous stream uses the same tools; they only return public catalogue data, so nothing user-specific can leak.

## MCP server
- Starter `spring-ai-starter-mcp-server-webmvc` 2.0.0-M2, protocol **STATELESS streamable HTTP**, endpoint `POST /mcp`
  (JSON-RPC: `tools/list`, `tools/call`). Same five tools, nothing that writes.
- **Auth**: `Authorization: Bearer <MCP_API_TOKEN>`. Separate from user JWTs and from `INDEX_API_TOKEN` (neither opens `/mcp`, and the
  MCP token opens nothing else). No/wrong token = 401; when `MCP_API_TOKEN` is unset the endpoint is closed for everyone.
  The token is compared by SHA-256 digest in constant time (`config/McpTokenFilter.java`).
- **Rate limit**: `mcp.rate-limit-per-minute` (60) requests per minute across the token, then 429 with `Retry-After`.
- **Logs**: one INFO line per tool call `tool=count_books channel=mcp ms=12 chars=31`; arguments and the token are never logged.
- nginx (frontend container) proxies `/mcp` unbuffered to the backend, so it is reachable on the public hostname.

### Set up
```
openssl rand -hex 32          # generate the token; put it in Infisical (project book-management, env prod) as MCP_API_TOKEN
```
`compose.prod.yml` already passes `MCP_API_TOKEN`; redeploy to pick it up (`redeploy.sh <tag>`). Rotate = change in Infisical + redeploy.

### Connect Claude Code
```
claude mcp add --transport http book-library https://bookmanagement.daniellaera.com/mcp \
  --header "Authorization: Bearer $MCP_API_TOKEN"
```
Then `/mcp` in Claude Code lists `book-library` with its five tools; ask e.g. "how many books are available?".

### Connect Claude Desktop
Custom connectors in Desktop use OAuth, so use the `mcp-remote` bridge in `claude_desktop_config.json`
(the token goes through an env var so the space in the header value does not break argument parsing):
```json
{
  "mcpServers": {
    "book-library": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "https://bookmanagement.daniellaera.com/mcp", "--header", "Authorization:${AUTH_HEADER}"],
      "env": { "AUTH_HEADER": "Bearer <MCP_API_TOKEN>" }
    }
  }
}
```

### Quick manual check
```
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://bookmanagement.daniellaera.com/mcp            # 401
curl -s -X POST https://bookmanagement.daniellaera.com/mcp -H "Authorization: Bearer $MCP_API_TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Cost notes
- Each uncached question costs one model call plus one more per tool round trip (at most 3). Tool results are short rows, so a typical
  question is a small system prompt + one or two tool results; the old approach sent 15 book rows every time.
- Cached questions cost nothing; `/ask` returns `inputTokens`/`outputTokens`/`toolCalls` and the log has one `AI usage` line per call.
- Caps: 3 tool calls, 1024 output tokens, daily limits per user and per anonymous IP, MCP 60 requests/minute (MCP calls cost no model tokens:
  the calling agent pays for its own model).
- Measure for real with `deploy/ai-eval.sh <url> --fresh` (bypasses the cache and prints tokens per question).

## Tests and eval
- `AiToolsITTest` (each tool, real PostgreSQL), `AiChatToolsITTest` (chat flow with a scripted model that calls the real tool callbacks: exact count,
  call budget, anonymous stream), `McpEndpointITTest` (401 without the token, `tools/list`, `tools/call`), `McpTokenFilterTest`, `BookToolsTest`.
  The integration tests do not call Anthropic; whether the real model picks the right tool is what the eval measures.
- Eval: `backend/src/test/resources/ai-eval/questions.json` (15 questions, expectations resolved against the live catalogue) and
  `deploy/ai-eval.sh [base-url] [--fresh]` (no interaction; signs up a throwaway user unless `AI_EVAL_TOKEN` or `AI_EVAL_EMAIL`/`AI_EVAL_PASSWORD` is set;
  one `/ask` per question against that user's daily limit). Exit code 1 below `AI_EVAL_MIN_PASS` (default: all).
- Run the tests on JDK 21 (the local JDK 25 breaks Lombok):
  `docker run --rm -v "$PWD/backend":/app -v ~/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /app maven:3.9.9-eclipse-temurin-21-alpine mvn -B verify`
  (on Linux hosts drop the override and add `--network host`).
