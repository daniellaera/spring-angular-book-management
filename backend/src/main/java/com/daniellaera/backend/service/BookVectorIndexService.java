package com.daniellaera.backend.service;

import com.daniellaera.backend.model.Book;
import com.daniellaera.backend.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class BookVectorIndexService {

    static final int BATCH_SIZE = 64;
    static final int VECTOR_SIZE = 768;

    public static final String TRIGGER_MANUAL = "manual";
    public static final String TRIGGER_SCHEDULED = "scheduled";
    public static final String TRIGGER_EVENT = "event";

    /**
     * Outcome of an indexing run. {@code failed} = books whose embedding could not be produced;
     * {@code alreadyRunning} = the run was not executed because another full run was in progress.
     */
    public record IndexResult(int indexed, int skipped, int deleted, int failed, boolean alreadyRunning) {
        public IndexResult(int indexed, int skipped, int deleted, int failed) {
            this(indexed, skipped, deleted, failed, false);
        }

        public static IndexResult skippedAlreadyRunning() {
            return new IndexResult(0, 0, 0, 0, true);
        }

        public String summary() {
            if (alreadyRunning) return "Skipped: indexing already in progress";
            return "Indexed " + indexed + ", skipped " + skipped + ", deleted " + deleted + ", failed " + failed;
        }
    }

    /** In-JVM guard: the app runs as a single instance, so one flag is enough (no distributed lock). */
    private final AtomicBoolean fullRunInProgress = new AtomicBoolean(false);

    private final WebClient webClient;
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;

    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.collection}")
    private String collection;

    public BookVectorIndexService(WebClient.Builder builder, BookRepository bookRepository,
                                  EmbeddingService embeddingService) {
        this.webClient = builder.build();
        this.bookRepository = bookRepository;
        this.embeddingService = embeddingService;
    }

    /** Manual trigger (POST /index). */
    public IndexResult indexAllBooks() {
        return indexAllBooks(TRIGGER_MANUAL);
    }

    /**
     * Incremental full run: only new/changed books are re-embedded; points of removed books are deleted.
     * Guarded: if another full run is already in progress this one is skipped (logged) and returns
     * {@link IndexResult#skippedAlreadyRunning()}. {@code trigger} is only used for the log lines.
     */
    public IndexResult indexAllBooks(String trigger) {
        if (!fullRunInProgress.compareAndSet(false, true)) {
            log.info("indexing skipped trigger={} reason=already-running", trigger);
            return IndexResult.skippedAlreadyRunning();
        }
        long start = System.nanoTime();
        log.info("indexing start trigger={}", trigger);
        try {
            IndexResult result = doIndexAll();
            log.info("indexing done trigger={} books={} durationMs={} indexed={} skipped={} deleted={} failed={}",
                    trigger, result.indexed() + result.skipped() + result.failed(),
                    (System.nanoTime() - start) / 1_000_000,
                    result.indexed(), result.skipped(), result.deleted(), result.failed());
            return result;
        } finally {
            fullRunInProgress.set(false);
        }
    }

    /**
     * Event-driven: (re)index one book by its deterministic point id (the book id), or remove its point when the
     * book no longer exists. Same document text, embedding and point layout as the full run.
     * Throws if Ollama/Qdrant fail: the caller (the on-change listener) catches and logs.
     */
    public void indexBook(Integer bookId) {
        Optional<Book> book = bookRepository.findById(bookId);
        if (book.isEmpty()) {
            if (!deletePoints(List.of(bookId))) {
                throw new IllegalStateException("Qdrant delete of book " + bookId + " failed");
            }
            return;
        }
        String text = buildBookText(book.get());
        List<Double> embedding = embeddingService.embed(text);
        if (!validEmbedding(embedding)) {
            throw new IllegalStateException("no valid embedding for book " + bookId);
        }
        if (!upsert(List.of(buildPoint(book.get(), sha256(text), embedding)))) {
            throw new IllegalStateException("Qdrant upsert of book " + bookId + " failed");
        }
    }

    private IndexResult doIndexAll() {
        List<Book> books = bookRepository.findAll();

        Map<Integer, String> existing = fetchExistingHashes(); // null if Qdrant could not be read
        Map<Integer, String> known = existing != null ? existing : Map.of();

        int skipped = 0, failed = 0, indexed = 0;
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

        for (Book book : books) {
            String text = buildBookText(book);
            String hash = sha256(text);

            if (hash.equals(known.get(book.getId()))) {
                skipped++;
                continue;
            }

            List<Double> embedding = embeddingService.embed(text);
            if (!validEmbedding(embedding)) {
                log.warn("Skipping book {}: invalid embedding (size {})", book.getTitle(),
                        embedding == null ? "null" : embedding.size());
                failed++;
                continue;
            }

            batch.add(buildPoint(book, hash, embedding));

            if (batch.size() >= BATCH_SIZE) {
                if (upsert(batch)) indexed += batch.size(); else failed += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            if (upsert(batch)) indexed += batch.size(); else failed += batch.size();
        }

        int deleted = 0;
        if (existing != null) {
            Set<Integer> liveIds = new HashSet<>();
            books.forEach(b -> liveIds.add(b.getId()));
            List<Integer> orphans = existing.keySet().stream().filter(id -> !liveIds.contains(id)).toList();
            if (!orphans.isEmpty() && deletePoints(orphans)) {
                deleted = orphans.size();
            }
        }

        return new IndexResult(indexed, skipped, deleted, failed);
    }

    private static boolean validEmbedding(List<Double> embedding) {
        return embedding != null && embedding.size() == VECTOR_SIZE;
    }

    /** The point id is the book id: deterministic, so re-indexing a book overwrites its point (upsert), never duplicates. */
    static Map<String, Object> buildPoint(Book book, String hash, List<Double> embedding) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookId", book.getId());
        payload.put("title", book.getTitle());
        payload.put("author", book.getAuthor() != null ? book.getAuthor() : "Unknown");
        payload.put("genre", book.getGenre() != null ? book.getGenre() : "Unknown");
        payload.put("contentHash", hash);

        Map<String, Object> point = new HashMap<>();
        point.put("id", book.getId());
        point.put("vector", embedding);
        point.put("payload", payload);
        return point;
    }

    /** Best effort: never throws. Used when a book is deleted. */
    public void deleteBookPoint(Integer bookId) {
        try {
            deletePoints(List.of(bookId));
        } catch (Exception e) {
            log.warn("Could not delete book {} from Qdrant: {}", bookId, e.getMessage());
        }
    }

    private boolean upsert(List<Map<String, Object>> points) {
        try {
            webClient.put()
                    .uri(qdrantUrl + "/collections/" + collection + "/points?wait=true")
                    .bodyValue(Map.of("points", new ArrayList<>(points)))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Qdrant upsert of {} points failed: {}", points.size(), e.getMessage());
            return false;
        }
    }

    private boolean deletePoints(List<Integer> ids) {
        try {
            webClient.post()
                    .uri(qdrantUrl + "/collections/" + collection + "/points/delete?wait=true")
                    .bodyValue(Map.of("points", ids))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Qdrant delete of {} points failed: {}", ids.size(), e.getMessage());
            return false;
        }
    }

    /** bookId -> contentHash for every point in the collection; null if it cannot be read. */
    @SuppressWarnings("unchecked")
    private Map<Integer, String> fetchExistingHashes() {
        Map<Integer, String> hashes = new HashMap<>();
        Object offset = null;
        try {
            do {
                Map<String, Object> body = new HashMap<>();
                body.put("limit", 256);
                body.put("with_payload", List.of("contentHash"));
                body.put("with_vector", false);
                if (offset != null) body.put("offset", offset);

                Map<String, Object> response = webClient.post()
                        .uri(qdrantUrl + "/collections/" + collection + "/points/scroll")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                if (response == null || !(response.get("result") instanceof Map<?, ?> result)) return null;

                List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
                if (points != null) {
                    for (Map<String, Object> p : points) {
                        if (!(p.get("id") instanceof Number id)) continue; // non-numeric ids are not ours
                        Map<String, Object> payload = (Map<String, Object>) p.get("payload");
                        Object h = payload != null ? payload.get("contentHash") : null;
                        hashes.put(id.intValue(), h instanceof String s ? s : "");
                    }
                }
                offset = result.get("next_page_offset");
            } while (offset != null);
            return hashes;
        } catch (Exception e) {
            log.warn("Could not read existing Qdrant points ({}); re-indexing everything, no deletions", e.getMessage());
            return null;
        }
    }

    /** Lowercased on purpose: the embedding model is case-sensitive and queries are embedded lowercased (EmbeddingService). */
    static String buildBookText(Book book) {
        return String.join(". ",
                book.getTitle() + " by " + book.getAuthor(),
                "Genre: " + (book.getGenre() != null ? book.getGenre() : "Unknown"),
                "Description: " + (book.getDescription() != null ? book.getDescription() : "")
        ).trim().toLowerCase(java.util.Locale.ROOT);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
