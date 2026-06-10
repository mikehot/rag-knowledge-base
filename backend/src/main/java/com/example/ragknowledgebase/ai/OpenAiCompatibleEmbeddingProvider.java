package com.example.ragknowledgebase.ai;

import com.example.ragknowledgebase.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingProvider(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(properties.embedding().timeoutSeconds()))
            .build();
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        List<float[]> result = new ArrayList<>();
        int batchSize = Math.max(1, properties.embedding().batchSize());
        for (int start = 0; start < inputs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, inputs.size());
            result.addAll(embedBatch(inputs.subList(start, end)));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> inputs) {
        Exception last = null;
        int attempts = Math.max(1, properties.embedding().maxRetries() + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                return request(inputs);
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new AiCallException("Embedding 调用失败，请检查模型服务配置", last);
    }

    private List<float[]> request(List<String> inputs) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "model", properties.embedding().modelId(),
            "input", inputs
        ));
        HttpRequest.Builder builder = HttpRequest.newBuilder(embeddingsUri())
            .timeout(Duration.ofSeconds(properties.embedding().timeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (properties.embedding().apiKey() != null && !properties.embedding().apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.embedding().apiKey());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiCallException("Embedding 接口调用失败，HTTP " + response.statusCode());
        }
        JsonNode data = objectMapper.readTree(response.body()).path("data");
        if (!data.isArray() || data.size() != inputs.size()) {
            throw new AiCallException("Embedding 接口返回数量不匹配");
        }
        List<float[]> embeddings = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode vector = item.path("embedding");
            if (!vector.isArray()) {
                throw new AiCallException("Embedding 接口返回格式不正确");
            }
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            if (values.length != properties.rag().embeddingDim()) {
                throw new AiCallException("Embedding 维度不匹配，当前配置为 " + properties.rag().embeddingDim());
            }
            embeddings.add(values);
        }
        return embeddings;
    }

    private URI embeddingsUri() {
        String baseUrl = properties.embedding().baseUrl();
        String normalized = (baseUrl == null || baseUrl.isBlank() ? "http://localhost:1234/v1" : baseUrl)
            .replaceAll("/+$", "");
        return URI.create(normalized + "/embeddings");
    }
}
