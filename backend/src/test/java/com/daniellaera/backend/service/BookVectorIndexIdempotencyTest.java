package com.daniellaera.backend.service;

import com.daniellaera.backend.model.Book;
import com.daniellaera.backend.repository.BookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Idempotency of BookVectorIndexService against a stateful fake Qdrant (JDK HttpServer) that stores points by id:
 * PUT .../points upserts, POST .../points/scroll lists, POST .../points/delete removes. The service is real;
 * only the repository and the embedding client are stubbed.
 */
class BookVectorIndexIdempotencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer qdrant;
    private final Map<Integer, Map<String, Object>> points = new ConcurrentHashMap<>();
    private BookRepository bookRepository;
    private EmbeddingService embeddingService;
    private BookVectorIndexService service;

    private static Book book(int id, String title) {
        Book b = new Book();
        b.setId(id);
        b.setTitle(title);
        b.setAuthor("Author");
        b.setGenre("Genre");
        b.setDescription("Desc " + id);
        return b;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        qdrant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        qdrant.createContext("/collections/books/points", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> req = body.isBlank() ? Map.of() : JSON.readValue(body, Map.class);
            String response = "{\"status\":\"ok\"}";
            if (path.endsWith("/scroll")) {
                List<Map<String, Object>> out = new ArrayList<>();
                points.forEach((id, p) -> out.add(Map.of("id", id, "payload", p.get("payload"))));
                response = JSON.writeValueAsString(Map.of("result", Map.of("points", out)));
            } else if (path.endsWith("/delete")) {
                ((List<Number>) req.get("points")).forEach(id -> points.remove(id.intValue()));
            } else if ("PUT".equals(ex.getRequestMethod())) { // upsert: same id overwrites
                for (Map<String, Object> p : (List<Map<String, Object>>) req.get("points")) {
                    points.put(((Number) p.get("id")).intValue(), p);
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        qdrant.start();

        bookRepository = mock(BookRepository.class);
        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed(anyString())).thenAnswer(i -> new ArrayList<>(Collections.nCopies(768, 0.1)));
        service = new BookVectorIndexService(WebClient.builder(), bookRepository, embeddingService);
        ReflectionTestUtils.setField(service, "qdrantUrl", "http://127.0.0.1:" + qdrant.getAddress().getPort());
        ReflectionTestUtils.setField(service, "collection", "books");
    }

    @AfterEach
    void tearDown() {
        qdrant.stop(0);
    }

    @Test
    void fullReindexTwice_leavesSamePoints_andSecondRunEmbedsNothing() {
        when(bookRepository.findAll()).thenReturn(List.of(book(1, "A"), book(2, "B"), book(3, "C")));

        var first = service.indexAllBooks();
        assertThat(first.indexed()).isEqualTo(3);
        assertThat(points).containsOnlyKeys(1, 2, 3);
        verify(embeddingService, times(3)).embed(anyString());

        var second = service.indexAllBooks();
        assertThat(second.indexed()).isZero();
        assertThat(second.skipped()).isEqualTo(3);
        assertThat(points).containsOnlyKeys(1, 2, 3);
        verify(embeddingService, times(3)).embed(anyString()); // no new calls
    }

    @Test
    void indexBookRepeatedly_overwritesSamePoint_withTheHashTheFullRunProduces() {
        Book b = book(7, "Seven");
        when(bookRepository.findAll()).thenReturn(List.of(b));
        when(bookRepository.findById(7)).thenReturn(Optional.of(b));

        service.indexAllBooks();
        Object fullRunHash = payload(7).get("contentHash");
        assertThat(fullRunHash).isEqualTo(BookVectorIndexService.sha256(BookVectorIndexService.buildBookText(b)));

        service.indexBook(7);
        service.indexBook(7);

        assertThat(points).containsOnlyKeys(7);
        assertThat(payload(7)).containsEntry("contentHash", fullRunHash).containsEntry("title", "Seven");
    }

    @Test
    void fullRunAfterIndexBook_skipsThatBook() {
        Book b = book(5, "Five");
        when(bookRepository.findAll()).thenReturn(List.of(b));
        when(bookRepository.findById(5)).thenReturn(Optional.of(b));

        service.indexBook(5);
        assertThat(points).containsOnlyKeys(5);
        verify(embeddingService, times(1)).embed(anyString());

        var result = service.indexAllBooks();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.indexed()).isZero();
        assertThat(points).containsOnlyKeys(5);
        verify(embeddingService, times(1)).embed(anyString());
    }

    @Test
    void indexBookOfMissingBook_removesItsPoint() {
        Book keep = book(8, "Eight");
        Book gone = book(9, "Nine");
        when(bookRepository.findAll()).thenReturn(List.of(keep, gone));
        service.indexAllBooks();
        assertThat(points).containsOnlyKeys(8, 9);

        when(bookRepository.findById(9)).thenReturn(Optional.empty());
        service.indexBook(9);

        assertThat(points).containsOnlyKeys(8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(int id) {
        return (Map<String, Object>) points.get(id).get("payload");
    }
}
