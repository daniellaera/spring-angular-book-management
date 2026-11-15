package com.daniellaera.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class QdrantSearchServiceTest {

    private EmbeddingService embeddingService;
    private String qdrantJson = "{\"result\":[]}";
    private QdrantSearchService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        // stub transport: answer every Qdrant call with qdrantJson
        WebClient.Builder builder = WebClient.builder().exchangeFunction(req -> {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json").body(qdrantJson).build());
        });
        service = new QdrantSearchService(builder, embeddingService);
        ReflectionTestUtils.setField(service, "qdrantUrl", "http://localhost:6333");
        ReflectionTestUtils.setField(service, "collection", "books");
    }

    @Test
    void embeddingFails_returnsEmptyList() {
        when(embeddingService.embedQuery(anyString())).thenReturn(null);
        assertThat(service.searchSimilarBooks("test", 5)).isEmpty();
    }

    @Test
    void emptyEmbedding_returnsEmptyList() {
        when(embeddingService.embedQuery(anyString())).thenReturn(List.of());
        assertThat(service.searchSimilarBooks("test", 5)).isEmpty();
    }

    @Test
    void returnsBookIdsInRankOrder() {
        when(embeddingService.embedQuery("clean code")).thenReturn(List.of(0.1, 0.2));
        qdrantJson = "{\"result\":[{\"score\":0.9,\"payload\":{\"bookId\":7}},{\"score\":0.5,\"payload\":{\"bookId\":3}},{\"score\":0.1}]}";

        assertThat(service.searchSimilarBooks("clean code", 5)).containsExactly(7, 3);
    }

    @Test
    void qdrantError_returnsEmptyList() {
        when(embeddingService.embedQuery(anyString())).thenReturn(new ArrayList<>(List.of(0.1)));
        qdrantJson = "not json";
        assertThat(service.searchSimilarBooks("x", 5)).isEmpty();
    }
}
