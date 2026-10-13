package com.example.ragknowledgebase.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        boolean contextSelectionExperimentEnabled,
        String citationSupportMode
    ) {
        /** The record has two constructors, so Spring Boot is told which one binds configuration. */
        @ConstructorBinding
        public Rag {
        }

        /** Keeps call sites that predate the citation-support mode compiling; the mode defaults to REPAIR. */
        public Rag(
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
            this(
                chunkSize, chunkOverlap, topK, similarityThreshold, embeddingDim, hybridExperimentEnabled,
                hybridCandidateK, hybridKeywordWeight, hybridRrfK, contextSelectionExperimentEnabled, "REPAIR"
            );
        }
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
