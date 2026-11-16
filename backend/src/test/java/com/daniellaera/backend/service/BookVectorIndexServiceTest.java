package com.daniellaera.backend.service;

import com.daniellaera.backend.model.Book;
import com.daniellaera.backend.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookVectorIndexServiceTest {

    record Call(HttpMethod method, URI uri) {}

    private final List<Call> calls = new ArrayList<>();
    private String scrollJson;
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
    void setUp() {
        bookRepository = mock(BookRepository.class);
        embeddingService = mock(EmbeddingService.class);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(req -> {
            calls.add(new Call(req.method(), req.url()));
            String body = req.url().getPath().endsWith("/scroll") ? scrollJson : "{\"status\":\"ok\"}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json").body(body).build());
        });
        service = new BookVectorIndexService(builder, bookRepository, embeddingService);
        ReflectionTestUtils.setField(service, "qdrantUrl", "http://qdrant:6333");
        ReflectionTestUtils.setField(service, "collection", "books");
    }

    @Test
    void buildBookText_isLowercased_becauseTheEmbeddingModelIsCaseSensitive() {
        Book b = new Book();
        b.setTitle("Effective Java"); b.setAuthor("Joshua Bloch"); b.setGenre("Programming"); b.setDescription("Best Practices");
        assertThat(BookVectorIndexService.buildBookText(b))
                .isEqualTo("effective java by joshua bloch. genre: programming. description: best practices");
    }

    @Test
    void embedsOnlyNewOrChanged_skipsUnchanged_deletesOrphans() {
        Book unchanged = book(1, "Same");
        Book changed = book(2, "Edited");
        Book brandNew = book(4, "New");
        when(bookRepository.findAll()).thenReturn(List.of(unchanged, changed, brandNew));

        String hash1 = BookVectorIndexService.sha256(BookVectorIndexService.buildBookText(unchanged));
        // book 2 has a stale hash, book 3 no longer exists in the DB
        scrollJson = "{\"result\":{\"points\":[" +
                "{\"id\":1,\"payload\":{\"contentHash\":\"" + hash1 + "\"}}," +
                "{\"id\":2,\"payload\":{\"contentHash\":\"stale\"}}," +
                "{\"id\":3,\"payload\":{\"contentHash\":\"x\"}}],\"next_page_offset\":null}}";
        when(embeddingService.embed(anyString())).thenAnswer(i -> new ArrayList<>(java.util.Collections.nCopies(768, 0.1)));

        var result = service.indexAllBooks();

        assertThat(result).isEqualTo(new BookVectorIndexService.IndexResult(2, 1, 1, 0));
        org.mockito.Mockito.verify(embeddingService, org.mockito.Mockito.times(2)).embed(anyString());
        assertThat(calls).extracting(c -> c.method() + " " + c.uri().getPath()).containsExactly(
                "POST /collections/books/points/scroll",
                "PUT /collections/books/points",
                "POST /collections/books/points/delete");
    }

    @Test
    void invalidEmbeddingCountsAsFailed_andIsNotUpserted() {
        when(bookRepository.findAll()).thenReturn(List.of(book(1, "A")));
        scrollJson = "{\"result\":{\"points\":[],\"next_page_offset\":null}}";
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1)); // wrong size

        var result = service.indexAllBooks();

        assertThat(result).isEqualTo(new BookVectorIndexService.IndexResult(0, 0, 0, 1));
        assertThat(calls).extracting(c -> c.method().name()).doesNotContain("PUT");
    }

    @Test
    void upsertsInBatchesOf64() {
        List<Book> books = new ArrayList<>();
        for (int i = 1; i <= 130; i++) books.add(book(i, "B" + i));
        when(bookRepository.findAll()).thenReturn(books);
        scrollJson = "{\"result\":{\"points\":[],\"next_page_offset\":null}}";
        when(embeddingService.embed(anyString())).thenAnswer(i -> new ArrayList<>(java.util.Collections.nCopies(768, 0.1)));

        var result = service.indexAllBooks();

        assertThat(result.indexed()).isEqualTo(130);
        assertThat(calls.stream().filter(c -> c.method() == HttpMethod.PUT)).hasSize(3); // 64 + 64 + 2
    }

    @Test
    void deleteBookPoint_neverThrows() {
        WebClient.Builder failing = WebClient.builder().exchangeFunction(req -> Mono.error(new RuntimeException("down")));
        BookVectorIndexService s = new BookVectorIndexService(failing, bookRepository, embeddingService);
        ReflectionTestUtils.setField(s, "qdrantUrl", "http://qdrant:6333");
        ReflectionTestUtils.setField(s, "collection", "books");

        s.deleteBookPoint(5); // must not throw
    }

    @Test
    void contentHashIsStableAndChangesWithContent() {
        Book a = book(1, "A");
        String h1 = BookVectorIndexService.sha256(BookVectorIndexService.buildBookText(a));
        assertThat(h1).hasSize(64).isEqualTo(BookVectorIndexService.sha256(BookVectorIndexService.buildBookText(a)));
        a.setDescription("changed");
        assertThat(BookVectorIndexService.sha256(BookVectorIndexService.buildBookText(a))).isNotEqualTo(h1);
    }
}
