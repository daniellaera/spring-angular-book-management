package com.daniellaera.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/** Creates the Qdrant collection (768, Cosine) on startup if missing. Idempotent; never fails startup. */
@Component
@Slf4j
public class QdrantCollectionInitializer implements ApplicationRunner {

    private final WebClient webClient;
    private final String qdrantUrl;
    private final String collection;
    private final boolean enabled;

    public QdrantCollectionInitializer(WebClient.Builder builder,
                                       @Value("${qdrant.url}") String qdrantUrl,
                                       @Value("${qdrant.collection}") String collection,
                                       @Value("${qdrant.init-collection:true}") boolean enabled) {
        this.webClient = builder.build();
        this.qdrantUrl = qdrantUrl;
        this.collection = collection;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        String uri = qdrantUrl + "/collections/" + collection;
        try {
            try {
                webClient.get().uri(uri).retrieve().toBodilessEntity().block();
                log.info("Qdrant collection '{}' already exists", collection);
                return;
            } catch (WebClientResponseException.NotFound notFound) {
                // fall through and create it
            }
            webClient.put().uri(uri)
                    .bodyValue(Map.of("vectors", Map.of("size", BookVectorIndexService.VECTOR_SIZE, "distance", "Cosine")))
                    .retrieve().toBodilessEntity().block();
            log.info("Created Qdrant collection '{}' (size {}, Cosine)", collection, BookVectorIndexService.VECTOR_SIZE);
        } catch (Exception e) {
            log.warn("Could not verify/create Qdrant collection '{}': {}", collection, e.getMessage());
        }
    }
}
