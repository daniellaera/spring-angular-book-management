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

@Service
@Slf4j
public class BookVectorIndexService {

    static final int BATCH_SIZE = 64;
    static final int VECTOR_SIZE = 768;

    /** Outcome of an indexing run. {@code failed} = books whose embedding could not be produced. */
    public record IndexResult(int indexed, int skipped, int deleted, int failed) {
        public String summary() {
            return "Indexed " + indexed + ", skipped " + skipped + ", deleted " + deleted + ", failed " + failed;
        }
    }

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

    /** Incremental: only new/changed books are re-embedded; points of removed books are deleted. */
    public IndexResult indexAllBooks() {
        List<Book> books = bookRepository.findAll();
        log.info("Indexing up to {} books into Qdrant", books.size());

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
            if (embedding == null || embedding.size() != VECTOR_SIZE) {
                log.warn("Skipping book {}: invalid embedding (size {})", book.getTitle(),
                        embedding == null ? "null" : embedding.size());
                failed++;
                continue;
            }

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
            batch.add(point);

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

        IndexResult result = new IndexResult(indexed, skipped, deleted, failed);
        log.info("Qdrant indexing done: {}", result.summary());
        return result;
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

    static String buildBookText(Book book) {
        return String.join(". ",
                book.getTitle() + " by " + book.getAuthor(),
                "Genre: " + (book.getGenre() != null ? book.getGenre() : "Unknown"),
                "Description: " + (book.getDescription() != null ? book.getDescription() : "")
        ).trim();
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
