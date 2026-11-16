package com.daniellaera.backend.ai;

import com.daniellaera.backend.dao.BookAiView;
import com.daniellaera.backend.dao.BookDTO;
import com.daniellaera.backend.service.BookService;
import com.daniellaera.backend.service.QdrantSearchService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Read-only book lookups for the AI tools (chat and MCP). Everything goes through the existing services and
 * repositories (never raw SQL) and returns a compact text format to keep the model's token usage low:
 * <pre>id|title|author|rating|available(Y/N)</pre>
 * Only public catalogue data is returned: no comments, no borrowers, nothing user-specific.
 */
@Service
public class BookToolsService {

    public static final int MAX_LIMIT = 15;
    static final int DEFAULT_LIMIT = 8;
    static final int MAX_TEXT = 100;
    static final int MAX_DESCRIPTION = 300;

    private final BookService bookService;
    private final QdrantSearchService qdrantSearch;

    public BookToolsService(BookService bookService, QdrantSearchService qdrantSearch) {
        this.bookService = bookService;
        this.qdrantSearch = qdrantSearch;
    }

    /** Semantic search (Qdrant). A blank query lists books by title; availableOnly keeps only borrowable ones. */
    public String searchBooks(String query, Integer limit, Boolean availableOnly) {
        int n = clamp(limit);
        boolean onlyAvailable = Boolean.TRUE.equals(availableOnly);
        String q = clean(query);
        if (q.isEmpty()) {
            if (onlyAvailable) {
                return format(bookService.findAvailableAiViews(n), "available books");
            }
            return "Provide a query (a topic, title or author) or set availableOnly=true.";
        }
        // over-fetch when filtering, so the filter does not starve the result
        List<Integer> ids = qdrantSearch.searchSimilarBooks(q, onlyAvailable ? Math.min(n * 3, 45) : n);
        List<BookAiView> views = bookService.getBooksAiOptimizedView(ids).stream()
                .filter(v -> !onlyAvailable || Boolean.TRUE.equals(v.available()))
                .sorted(java.util.Comparator.comparingInt(v -> ids.indexOf(v.id()))) // keep Qdrant relevance order
                .limit(n)
                .toList();
        return format(views, views.size() + " matches");
    }

    public String getBook(Integer id) {
        if (id == null) return "id is required";
        return bookService.findBookById(id).map(BookToolsService::detail).orElse("No book with id " + id);
    }

    public String countBooks() {
        long total = bookService.getTotalBookCount();
        long available = bookService.countAvailableBooks();
        return "total=" + total + ";available=" + available + ";borrowed=" + (total - available);
    }

    public String listByAuthor(String author, Integer limit) {
        String a = clean(author);
        if (a.isEmpty()) return "author is required";
        return format(bookService.findAiViewsByAuthor(a, clamp(limit)), "books by author matching '" + a + "'");
    }

    public String listByGenre(String genre, Integer limit) {
        String g = clean(genre);
        if (g.isEmpty()) return "genre is required";
        return format(bookService.findAiViewsByGenre(g, clamp(limit)), "books in genre matching '" + g + "'");
    }

    // ---- formatting ----

    private static String format(Page<BookAiView> page, String what) {
        String rows = rows(page.getContent());
        String header = page.getTotalElements() + " " + what
                + (page.getTotalElements() > page.getNumberOfElements() ? " (showing " + page.getNumberOfElements() + ")" : "");
        return rows.isEmpty() ? header : header + "\nid|title|author|rating|available\n" + rows;
    }

    private static String format(List<BookAiView> views, String what) {
        String rows = rows(views);
        return rows.isEmpty() ? "0 " + what : what + "\nid|title|author|rating|available\n" + rows;
    }

    private static String rows(List<BookAiView> views) {
        return views.stream()
                .filter(Objects::nonNull)
                .map(b -> "%d|%s|%s|%s|%s".formatted(b.id(), b.title(), b.author(),
                        b.averageRating() != null ? String.format(java.util.Locale.ROOT, "%.1f", b.averageRating()) : "-",
                        Boolean.TRUE.equals(b.available()) ? "Y" : "N"))
                .collect(Collectors.joining("\n"));
    }

    private static String detail(BookDTO b) {
        String published = b.getPublishedDate() != null ? new SimpleDateFormat("yyyy-MM-dd").format(b.getPublishedDate()) : "-";
        String description = b.getDescription() == null ? "-" : b.getDescription().strip();
        if (description.length() > MAX_DESCRIPTION) description = description.substring(0, MAX_DESCRIPTION) + "...";
        return "id=%d|title=%s|author=%s|genre=%s|isbn=%s|published=%s|rating=%s|available=%s|description=%s".formatted(
                b.getId(), b.getTitle(), b.getAuthor(), nz(b.getGenre()), nz(b.getIsbn()), published,
                b.getAverageRating() != null ? String.format(java.util.Locale.ROOT, "%.1f", b.getAverageRating()) : "-",
                Boolean.FALSE.equals(b.getIsAvailable()) ? "N" : "Y", description);
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "-" : s; }

    static int clamp(Integer limit) {
        if (limit == null || limit < 1) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    static String clean(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }
}
