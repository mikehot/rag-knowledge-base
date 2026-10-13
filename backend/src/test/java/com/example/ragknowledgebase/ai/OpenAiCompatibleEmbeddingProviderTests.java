package com.example.ragknowledgebase.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingProviderTests {
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void acceptsTheModelsOwnDimensionWhenNoneIsPinned() {
        responseBody.set(embeddings(1024));

        List<float[]> result = provider(0).embed(List.of("问题"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).hasSize(1024);
    }

    @Test
    void rejectsAVectorThatDiffersFromAPinnedDimension() {
        responseBody.set(embeddings(1024));

        // The provider wraps the cause in a generic message so provider details never reach callers.
        assertThatThrownBy(() -> provider(768).embed(List.of("问题")))
            .cause().hasMessageContaining("768");
    }

    @Test
    void acceptsAVectorThatMatchesAPinnedDimension() {
        responseBody.set(embeddings(768));

        assertThat(provider(768).embed(List.of("问题")).get(0)).hasSize(768);
    }

    @Test
    void rejectsEmptyVectors() {
        responseBody.set("{\"data\":[{\"embedding\":[]}]}");

        assertThatThrownBy(() -> provider(0).embed(List.of("问题")))
            .cause().hasMessageContaining("不受支持");
    }

    @Test
    void rejectsABatchWithMixedDimensions() {
        responseBody.set(
            "{\"data\":[{\"embedding\":" + array(4) + "},{\"embedding\":" + array(5) + "}]}"
        );

        assertThatThrownBy(() -> provider(0).embed(List.of("a", "b")))
            .cause().hasMessageContaining("不同维度");
    }

    private OpenAiCompatibleEmbeddingProvider provider(int pinnedDimension) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        AppProperties properties = new AppProperties(
            new AppProperties.Auth("demo", "demo123456", "test-secret", 86400),
            new AppProperties.Upload("./uploads", 20_971_520, List.of("md")),
            new AppProperties.Rag(700, 100, 5, 0.35, pinnedDimension, false, 50, 2.0, 60, false),
            new AppProperties.Enterprise(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                true
            ),
            new AppProperties.Ai("openai-compatible", baseUrl, "", "test-model", 2400, 3200, false, 5, 0, 0, 50, "json_schema"),
            new AppProperties.Embedding("openai-compatible", baseUrl, "", "embedding-model", 16, 5, 0)
        );
        return new OpenAiCompatibleEmbeddingProvider(properties, new ObjectMapper());
    }

    private String embeddings(int dimensions) {
        return "{\"data\":[{\"embedding\":" + array(dimensions) + "}]}";
    }

    private String array(int dimensions) {
        return "[" + "0.5,".repeat(dimensions - 1) + "0.5]";
    }
}
