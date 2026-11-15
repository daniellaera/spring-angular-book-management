package com.daniellaera.backend.controller;

import com.daniellaera.backend.dao.BookAiView;
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
import java.util.stream.Collectors;

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

    public BookAiController(ChatClient.Builder chatClientBuilder,
                            BookService bookService,
                            AiProperties aiProperties,
                            AiRateLimiterService rateLimiter,
                            AiCacheService cache,
                            QdrantSearchService qdrantSearch,
                            BookVectorIndexService indexService,
                            AiUsageLogger usageLogger) {
        this.bookService = bookService;
        this.aiProperties = aiProperties;
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.qdrantSearch = qdrantSearch;
        this.indexService = indexService;
        this.usageLogger = usageLogger;
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
                .system(buildSmartPrompt(question))
                .user(question)
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
                .system(buildSmartPrompt(question))
                .user(question)
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

    /**
     * The Heart of the RAG system: Assembles the prompt dynamically.
     */
    private String buildSmartPrompt(String question) {
        // 1. DYNAMIC COUNT: Tells Claude how big the library is
        long totalLibrarySize = bookService.getTotalBookCount();

        // 2. VECTOR SEARCH: Gets relevant IDs
        List<Integer> relevantIds = qdrantSearch.searchSimilarBooks(question, aiProperties.getSearchLimit());

        // 3. SLIM DATA FETCH: Converts IDs to Record Views
        List<BookAiView> books = bookService.getBooksAiOptimizedView(relevantIds);

        // 4. CONTEXT BUILDING: Uses Record Accessors (id(), title(), etc.)
        String bookContext = books.stream()
                .map(b -> String.format("%d|%s|%s|%s|%s",
                        b.id(), b.title(), b.author(),
                        b.averageRating() != null ? b.averageRating() : "-",
                        b.available() ? "Y" : "N"))
                .collect(Collectors.joining("\n"));

        // 5. THE FINAL SYSTEM PROMPT
        return """
            You are a library assistant for a private collection.
            
            Current Library Metadata:
            - Total books in database: %d
            - The following %d books are the most relevant matches found for the user's query.
            
            Books (format: id|title|author|rating|available Y/N):
            %s
            
            Instructions:
            1. If the user's question can be answered by the metadata above, do so concisely.
            2. If the user asks about the "entire catalog", mention there are %d books total.
            3. If a book is marked as 'Available: No', mention it is currently borrowed.
            4. Keep responses short and professional.
            """.formatted(totalLibrarySize, books.size(), bookContext, totalLibrarySize);
    }
}