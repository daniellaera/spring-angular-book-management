package com.daniellaera.backend.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * nomic-embed-text is case-sensitive ("Effective Java" ranked the real book 27th of 32, "effective java" first), and the
 * indexed documents are lowercased, so queries must be embedded lowercased too, whatever casing the first caller used.
 */
class EmbeddingServiceTest {

    private HttpServer ollama;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private EmbeddingService service;

    @BeforeEach
    void start() throws Exception {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/embeddings", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"embedding\":[0.1,0.2,0.3]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        ollama.start();
        service = new EmbeddingService(WebClient.builder());
        ReflectionTestUtils.setField(service, "ollamaUrl", "http://127.0.0.1:" + ollama.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        ollama.stop(0);
    }

    @Test
    void embedQuery_embedsTheLowercasedNormalisedText() {
        assertThat(service.embedQuery("  Effective   JAVA ")).containsExactly(0.1, 0.2, 0.3);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"prompt\":\"effective java\"").doesNotContain("Effective");
    }

    @Test
    void embedQuery_sameQuestionInAnyCasing_isOneOllamaCall() {
        service.embedQuery("Effective Java");
        service.embedQuery("effective java");
        service.embedQuery("EFFECTIVE  JAVA");
        assertThat(bodies).hasSize(1);
    }

    @Test
    void embed_forDocuments_isLeftUntouched() {
        service.embed("Some Text");
        assertThat(bodies.get(0)).contains("Some Text");
    }
}
