package com.example.ragknowledgebase.ai;

import com.example.ragknowledgebase.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenAiCompatibleAiProvider implements AiProvider {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleAiProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(properties.ai().timeoutSeconds()))
            .build();
    }

    @Override
    public AiProviderResponse generate(String prompt) {
        return generate(prompt, properties.ai().maxTokens());
    }

    @Override
    public AiProviderResponse generate(String prompt, long maxTokens) {
        Exception last = null;
        int attempts = Math.max(1, properties.ai().maxRetries() + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                return request(prompt, maxTokens);
            } catch (Exception ex) {
                last = ex;
            }
        }
        if (AiCallException.isTimeout(last)) {
            throw AiCallException.timeout("AI 生成超时，请稍后重试", last);
        }
        throw new AiCallException("AI 生成失败，请稍后重试", last);
    }

    private AiProviderResponse request(String prompt, long maxTokens) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "model", properties.ai().modelId(),
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "response_format", structuredResponseFormat(),
            "max_tokens", maxTokens > 0 ? maxTokens : properties.ai().maxTokens(),
            "stream", false
        ));
        HttpRequest.Builder builder = HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(Duration.ofSeconds(properties.ai().timeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (properties.ai().apiKey() != null && !properties.ai().apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.ai().apiKey());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiCallException("OpenAI 兼容接口调用失败，HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        if (text.isBlank()) {
            throw new AiCallException("OpenAI 兼容接口返回内容为空");
        }
        return new AiProviderResponse(
            text.trim(),
            tokenUsage(root),
            completionTokens(root),
            finishReason(root)
        );
    }

    private Map<String, Object> structuredResponseFormat() {
        return Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                "name", "rag_answer",
                "strict", true,
                "schema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "answer", Map.of("type", "string"),
                        "found", Map.of("type", "boolean"),
                        "grounded", Map.of("type", "boolean"),
                        "sourceIndexes", Map.of(
                            "type", "array",
                            "items", Map.of("type", "integer", "minimum", 1)
                        )
                    ),
                    "required", List.of("answer", "found", "grounded", "sourceIndexes"),
                    "additionalProperties", false
                )
            )
        );
    }

    private URI chatCompletionsUri() {
        return URI.create(normalizeBaseUrl(properties.ai().baseUrl()) + "/chat/completions");
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? "http://localhost:1234/v1" : baseUrl;
        return value.replaceAll("/+$", "");
    }

    private int tokenUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.has("total_tokens")) {
            return usage.path("total_tokens").asInt();
        }
        if (usage.has("prompt_tokens") || usage.has("completion_tokens")) {
            return usage.path("prompt_tokens").asInt(0) + usage.path("completion_tokens").asInt(0);
        }
        return 0;
    }

    private int completionTokens(JsonNode root) {
        return Math.max(0, root.path("usage").path("completion_tokens").asInt(0));
    }

    private String finishReason(JsonNode root) {
        JsonNode finishReason = root.path("choices").path(0).path("finish_reason");
        String value = finishReason.asText("");
        if (value.isBlank()) {
            return "unknown";
        }
        return switch (value) {
            case "stop", "length", "content_filter", "tool_calls" -> value;
            default -> "other";
        };
    }
}
