package com.daniellaera.backend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The read-only book tools, defined once and shared by the chat (a fresh instance per request, capped at
 * {@code maxCalls} tool calls) and the MCP server (one long-lived instance, uncapped; rate limited at the HTTP layer).
 * Each call logs the tool name, channel and duration only: arguments are never logged.
 */
@Slf4j
public class BookTools {

    static final String LIMIT_REACHED = "Tool call limit reached for this question. Answer with the information already retrieved.";

    private final BookToolsService service;
    private final int maxCalls;
    private final String channel;
    private final AtomicInteger calls = new AtomicInteger();

    public BookTools(BookToolsService service, int maxCalls, String channel) {
        this.service = service;
        this.maxCalls = maxCalls;
        this.channel = channel;
    }

    public int callsMade() { return calls.get(); }

    @Tool(name = "search_books", description = "Semantic search by TOPIC or THEME (e.g. 'space travel', 'cooking', 'machine learning'); "
            + "fuzzy, so it can miss a book you name exactly: for a known title use find_by_title, for an author list_by_author. "
            + "Returns up to 'limit' books as id|title|author|rating|available(Y/N). "
            + "Use availableOnly=true for books that can be borrowed now (query may then be empty).")
    public String searchBooks(
            @ToolParam(description = "What to look for: a topic, title, author or theme. Empty only with availableOnly=true", required = false) String query,
            @ToolParam(description = "Max results, 1-15 (default 8)", required = false) Integer limit,
            @ToolParam(description = "Only books currently available to borrow", required = false) Boolean availableOnly) {
        return run("search_books", () -> service.searchBooks(query, limit, availableOnly));
    }

    @Tool(name = "find_by_title", description = "Find books by title or part of a title (case-insensitive, exact match first). "
            + "Use it whenever the user names a specific book: who wrote it, is it available, its rating or id. "
            + "Returns the exact total plus up to 'limit' rows as id|title|author|rating|available(Y/N); 0 means it is not in the library.")
    public String findByTitle(
            @ToolParam(description = "Book title or part of it, e.g. 'Effective Java' or 'Pragmatic'") String title,
            @ToolParam(description = "Max results, 1-15 (default 8)", required = false) Integer limit) {
        return run("find_by_title", () -> service.findByTitle(title, limit));
    }

    @Tool(name = "get_book", description = "Full details of one book by its numeric id: title, author, genre, ISBN, "
            + "published date, rating, availability and a short description.")
    public String getBook(@ToolParam(description = "The book id") Integer id) {
        return run("get_book", () -> service.getBook(id));
    }

    @Tool(name = "count_books", description = "Exact number of books in the library: total, available and borrowed. "
            + "Use this for any 'how many' question.")
    public String countBooks() {
        return run("count_books", service::countBooks);
    }

    @Tool(name = "list_by_author", description = "Books whose author matches the given name (case-insensitive, partial match). "
            + "Returns the exact total plus up to 'limit' rows as id|title|author|rating|available(Y/N).")
    public String listByAuthor(
            @ToolParam(description = "Author name or part of it") String author,
            @ToolParam(description = "Max results, 1-15 (default 8)", required = false) Integer limit) {
        return run("list_by_author", () -> service.listByAuthor(author, limit));
    }

    @Tool(name = "list_by_genre", description = "Books whose genre matches the given genre (case-insensitive, partial match). "
            + "Returns the exact total plus up to 'limit' rows as id|title|author|rating|available(Y/N).")
    public String listByGenre(
            @ToolParam(description = "Genre or part of it") String genre,
            @ToolParam(description = "Max results, 1-15 (default 8)", required = false) Integer limit) {
        return run("list_by_genre", () -> service.listByGenre(genre, limit));
    }

    private String run(String name, Supplier<String> body) {
        if (calls.incrementAndGet() > maxCalls) {
            log.warn("tool={} channel={} rejected: call budget {} exhausted", name, channel, maxCalls);
            return LIMIT_REACHED;
        }
        long start = System.nanoTime();
        try {
            String result = body.get();
            log.info("tool={} channel={} ms={} chars={}", name, channel, (System.nanoTime() - start) / 1_000_000, result.length());
            return result;
        } catch (RuntimeException e) {
            log.warn("tool={} channel={} failed after {} ms: {}", name, channel, (System.nanoTime() - start) / 1_000_000, e.getClass().getSimpleName());
            return "The lookup failed; tell the user the library data is temporarily unavailable.";
        }
    }
}
