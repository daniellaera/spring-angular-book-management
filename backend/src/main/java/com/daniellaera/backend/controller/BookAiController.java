package com.daniellaera.backend.controller;

import com.daniellaera.backend.ai.BookTools;
import com.daniellaera.backend.ai.BookToolsService;
import com.daniellaera.backend.dao.BookDTO;
import com.daniellaera.backend.properties.AiProperties;
import com.daniellaera.backend.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v3/ai/books")
public class BookAiController {

    private final ChatClient chatClient;
    private final BookService bookService;
    private final AiProperties aiProperties;
    private final AiRateLimiterService rateLimiter;
    private final AiCacheService cache;
    private final QdrantSearchService qdrantSearch;
    private final BookVectorIndexService indexService;
    private final AiUsageLogger usageLogger;
    private final BookToolsService toolsService;

    public BookAiController(ChatClient.Builder chatClientBuilder,
                            BookService bookService,
                            AiProperties aiProperties,
                            AiRateLimiterService rateLimiter,
                            AiCacheService cache,
                            QdrantSearchService qdrantSearch,
                            BookVectorIndexService indexService,
                            AiUsageLogger usageLogger,
                            BookToolsService toolsService) {
        this.bookService = bookService;
        this.aiProperties = aiProperties;
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.qdrantSearch = qdrantSearch;
        this.indexService = indexService;
        this.usageLogger = usageLogger;
        this.toolsService = toolsService;
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping("/search")
    public List<BookDTO> searchBooks(@RequestBody Map<String, String> payload) {
        String query = payload.get("query");
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query cannot be empty");
        }

        List<Integer> ids = qdrantSearch.searchSimilarBooks(query, 10);
        String queryLower = query.toLowerCase();

        return ids.stream()
                .map(id -> bookService.findBookById(id).orElse(null)) // returns Optional<BookDTO>
                .filter(Objects::nonNull)
                //.filter(b -> b.getTitle().toLowerCase().contains(queryLower))
                .toList();
    }

    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestParam String question, Authentication auth, HttpServletRequest request) {
        Caller caller = resolveCaller(auth, request);
        validateRequest(caller);

        // Check cache
        String cached = cache.get(question);
        if (cached != null) {
            usageLogger.logCacheHit(caller.label());
            return Flux.just(cached);
        }

        recordRequest(caller);

        StringBuilder fullResponse = new StringBuilder();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>();

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(question)
                .tools(newTools(caller))
                .stream()
                .chatResponse()
                .doOnNext(r -> captureMetadata(r, lastUsage, model))
                .mapNotNull(r -> r.getResult() != null ? r.getResult().getOutput().getText() : null)
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    cache.put(question, fullResponse.toString());
                    usageLogger.logCall(caller.label(), model.get(), lastUsage.get());
                });
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> payload, Authentication auth,
                                   HttpServletRequest request) {
        Caller caller = resolveCaller(auth, request);
        validateRequest(caller);

        String question = payload.get("question");

        // Check cache
        String cached = cache.get(question);
        if (cached != null) {
            usageLogger.logCacheHit(caller.label());
            return Map.of("answer", cached, "cached", true);
        }

        recordRequest(caller);

        var response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(question)
                .tools(newTools(caller))
                .call()
                .chatResponse();

        // join all generations: with some models the first one carries no text
        String answer = response.getResults().stream()
                .map(g -> g.getOutput().getText())
                .filter(t -> t != null && !t.isBlank())
                .collect(java.util.stream.Collectors.joining());
        if (!answer.isBlank()) {
            cache.put(question, answer);
        }
        usageLogger.logCall(caller.label(),
                response.getMetadata() != null ? response.getMetadata().getModel() : null,
                response.getMetadata() != null ? response.getMetadata().getUsage() : null);

        return Map.of("answer", answer);
    }

    @PostMapping("/index")
    public Map<String, Object> indexBooks() {
        BookVectorIndexService.IndexResult r = indexService.indexAllBooks();
        return Map.of(
                "result", r.summary(),
                "indexed", r.indexed(),
                "skipped", r.skipped(),
                "deleted", r.deleted(),
                "failed", r.failed()
        );
    }

    @GetMapping("/remaining")
    public Map<String, Object> getRemainingRequests(Authentication auth) {
        return Map.of(
                "remaining", rateLimiter.getRemainingRequests(auth.getName()),
                "dailyLimit", aiProperties.getDailyLimit()
        );
    }

    /** Who is calling: an authenticated user (daily-limit) or an anonymous client keyed by IP (anonymous-daily-limit). */
    private record Caller(String name, String clientIp) {
        boolean anonymous() { return name == null; }
        String label() { return anonymous() ? "anon:" + clientIp : name; }
    }

    private static Caller resolveCaller(Authentication auth, HttpServletRequest request) {
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return new Caller(null, clientIp(request));
        }
        return new Caller(auth.getName(), null);
    }

    /** First X-Forwarded-For hop if present (we sit behind a reverse proxy), else the socket address. */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }

    private void validateRequest(Caller caller) {
        if (!aiProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI feature is disabled");
        }
        boolean allowed = caller.anonymous()
                ? rateLimiter.isAnonymousAllowed(caller.clientIp())
                : rateLimiter.isAllowed(caller.name());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Daily AI limit reached");
        }
    }

    private void recordRequest(Caller caller) {
        if (caller.anonymous()) {
            rateLimiter.recordAnonymousRequest(caller.clientIp());
        } else {
            rateLimiter.recordRequest(caller.name());
        }
    }

    // Anthropic streams usage across chunks; keep the latest non-empty values.
    private static void captureMetadata(ChatResponse r, AtomicReference<Usage> usage, AtomicReference<String> model) {
        if (r.getMetadata() == null) return;
        Usage u = r.getMetadata().getUsage();
        if (u != null && ((u.getPromptTokens() != null && u.getPromptTokens() > 0)
                || (u.getCompletionTokens() != null && u.getCompletionTokens() > 0))) {
            usage.set(u);
        }
        String m = r.getMetadata().getModel();
        if (m != null && !m.isBlank()) model.set(m);
    }

    /** A fresh tool instance per question: it carries the per-question call budget (max tool-call iterations). */
    private BookTools newTools(Caller caller) {
        return new BookTools(toolsService, aiProperties.getMaxToolCalls(), caller.anonymous() ? "chat-anon" : "chat");
    }

    private static final String SYSTEM_PROMPT = """
            You are the assistant of a private book library. Answer only questions about this library.
            Use the tools to look things up: never guess counts, authors, ratings or availability.
            - "how many" questions: call count_books (or list_by_author / list_by_genre, which return exact totals).
            - Books by an author or in a genre: list_by_author / list_by_genre. Topics, themes, similar books: search_books.
            - What can be borrowed now: search_books with availableOnly=true, or count_books for the number.
            - Details of one book: get_book. Prefer one tool call; never more than three.
            Tool rows look like id|title|author|rating|available (Y = available, N = borrowed).
            Reply in at most three short sentences of plain text, no markdown tables. Mention when a book is borrowed.
            If nothing matches, say you found no matching books.
            """;
}
