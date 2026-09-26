package com.example.ragknowledgebase.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Auth auth,
    Upload upload,
    Rag rag,
    Enterprise enterprise,
    Ai ai,
    Embedding embedding
) {
    public record Auth(
        String defaultUsername,
        String defaultPassword,
        String jwtSecret,
        long tokenExpiresSeconds
    ) {
    }

    public record Upload(
        String storageDir,
        long maxFileSizeBytes,
        List<String> allowedExtensions
    ) {
    }

    public record Rag(
        int chunkSize,
        int chunkOverlap,
        int topK,
        double similarityThreshold,
        int embeddingDim,
        boolean hybridExperimentEnabled,
        int hybridCandidateK,
        double hybridKeywordWeight,
        int hybridRrfK,
        boolean contextSelectionExperimentEnabled
    ) {
    }

    public record Enterprise(
        java.util.UUID defaultTenantId,
        java.util.UUID defaultKnowledgeBaseId,
        boolean initializeDefaultAccess
    ) {
    }

    public record Ai(
        String provider,
        String baseUrl,
        String apiKey,
        String modelId,
        long maxTokens,
        long complexMaxTokens,
        boolean complexRoutingEnabled,
        long timeoutSeconds,
        int maxRetries,
        int structuredOutputRetries,
        int dailyLimit,
        String responseFormat
    ) {
    }

    public record Embedding(
        String provider,
        String baseUrl,
        String apiKey,
        String modelId,
        int batchSize,
        long timeoutSeconds,
        int maxRetries
    ) {
    }
}
