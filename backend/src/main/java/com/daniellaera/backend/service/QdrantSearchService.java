package com.daniellaera.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class QdrantSearchService {

    private final WebClient webClient;
    private final EmbeddingService embeddingService;

    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.collection}")
    private String collection;

    /** 0 = off. Tune using the DEBUG score logs. */
    @Value("${qdrant.score-threshold:0}")
    private double scoreThreshold;

    public QdrantSearchService(WebClient.Builder builder, EmbeddingService embeddingService) {
        this.webClient = builder.build();
        this.embeddingService = embeddingService;
    }

    /**
     * Search Qdrant for books similar to the query.
     * Returns a list of book IDs ranked by relevance.
     */
    @SuppressWarnings("unchecked")
    public List<Integer> searchSimilarBooks(String query, int limit) {
        List<Double> queryEmbedding = embeddingService.embedQuery(query);

        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("Failed to generate embedding for query: {}", query);
            return List.of();
        }

        Map<String, Object> searchBody = new HashMap<>();
        searchBody.put("vector", queryEmbedding);
        searchBody.put("limit", limit);
        searchBody.put("with_payload", true);
        if (scoreThreshold > 0) {
            searchBody.put("score_threshold", scoreThreshold);
        }

        try {
            Map<String, Object> response = webClient.post()
                    .uri(qdrantUrl + "/collections/" + collection + "/points/search")
                    .bodyValue(searchBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("result")) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("result");
                List<Integer> bookIds = new ArrayList<>();

                for (Map<String, Object> result : results) {
                    Map<String, Object> payload = (Map<String, Object>) result.get("payload");
                    if (payload != null && payload.get("bookId") instanceof Number id) {
                        bookIds.add(id.intValue());
                        log.debug("Qdrant hit bookId={} score={}", id, result.get("score"));
                    }
                }

                log.info("Qdrant returned {} results for query: {}", bookIds.size(), query);
                return bookIds;
            }
        } catch (Exception e) {
            log.error("Qdrant search failed: {}", e.getMessage());
        }

        return List.of();
    }
}
