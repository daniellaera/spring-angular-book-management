package com.daniellaera.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ollama embeddings. Query embeddings are cached (LRU) so one question costs one Ollama call. */
@Service
@Slf4j
public class EmbeddingService {

    static final int QUERY_CACHE_SIZE = 200;

    private final WebClient webClient;

    @Value("${ollama.api-url}")
    private String ollamaUrl;

    private final Map<String, List<Double>> queryCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Double>> eldest) {
            return size() > QUERY_CACHE_SIZE;
        }
    };

    public EmbeddingService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    static String normalise(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Cached embedding for a user query; null when Ollama fails. */
    public List<Double> embedQuery(String query) {
        String key = normalise(query);
        synchronized (queryCache) {
            List<Double> hit = queryCache.get(key);
            if (hit != null) return hit;
        }
        List<Double> embedding = embed(query);
        if (embedding != null && !embedding.isEmpty()) {
            synchronized (queryCache) {
                queryCache.put(key, embedding);
            }
        }
        return embedding;
    }

    /** Uncached embedding (used when indexing documents); null when Ollama fails. */
    public List<Double> embed(String text) {
        try {
            Map<?, ?> response = webClient.post()
                    .uri(ollamaUrl + "/api/embeddings")
                    .bodyValue(Map.of("model", "nomic-embed-text", "prompt", text))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.get("embedding") instanceof List<?> rawList) {
                List<Double> embedding = new ArrayList<>(rawList.size());
                for (Object o : rawList) {
                    if (o instanceof Number n) {
                        embedding.add(n.doubleValue());
                    } else {
                        log.warn("Non-numeric value in embedding: {}", o);
                        return null;
                    }
                }
                return embedding;
            }
        } catch (Exception e) {
            log.error("Ollama embedding failed: {}", e.getMessage());
        }
        return null;
    }
}
