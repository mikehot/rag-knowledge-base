package com.example.ragknowledgebase.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragknowledgebase.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleAiProviderTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"message":{"content":"{\\"answer\\":\\"已处理\\",\\"found\\":true,\\"grounded\\":true,\\"sourceIndexes\\":[1]}"}}],"usage":{"total_tokens":17}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsStrictStructuredOutputContractToOpenAiCompatibleProvider() throws Exception {
        OpenAiCompatibleAiProvider provider = new OpenAiCompatibleAiProvider(
            properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
            objectMapper
        );

        AiProviderResponse response = provider.generate("回答问题");

        assertThat(response.text()).contains("\"sourceIndexes\":[1]");
        assertThat(response.tokenUsage()).isEqualTo(17);
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("response_format").path("json_schema").path("name").asText())
            .isEqualTo("rag_answer");
        assertThat(body.path("response_format").path("json_schema").path("strict").asBoolean())
            .isTrue();
        JsonNode schema = body.path("response_format").path("json_schema").path("schema");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertThat(required)
            .containsExactlyInAnyOrder("answer", "found", "grounded", "sourceIndexes");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2400);
    }

    @Test
    void sendsExplicitBudgetForComplexQuestionRoute() throws Exception {
        OpenAiCompatibleAiProvider provider = new OpenAiCompatibleAiProvider(
            properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
            objectMapper
        );

        provider.generate("复杂问题", 3200);

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(3200);
    }

    private AppProperties properties(String baseUrl) {
        return new AppProperties(
            new AppProperties.Auth("demo", "demo123456", "test-secret", 86400),
            new AppProperties.Upload("./uploads", 20_971_520, List.of("md")),
            new AppProperties.Rag(700, 100, 5, 0.35, 768, false, 50, 2.0, 60, false),
            new AppProperties.Enterprise(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                true
            ),
            new AppProperties.Ai("openai-compatible", baseUrl, "", "test-model", 2400, 3200, false, 5, 0, 0, 50),
            new AppProperties.Embedding("openai-compatible", baseUrl, "", "embedding-model", 16, 5, 0)
        );
    }
}
