package com.example.ragknowledgebase.ask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ask_log")
public class AskLog {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(nullable = false, length = 2000)
    private String question;

    @Column(nullable = false)
    private boolean found;

    @Column(name = "token_usage")
    private int tokenUsage;

    @Column(name = "result_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private AskResultStatus resultStatus;

    @Column(name = "failure_reason")
    @Enumerated(EnumType.STRING)
    private AskFailureReason failureReason;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "embedding_latency_ms", nullable = false)
    private long embeddingLatencyMs;

    @Column(name = "retrieval_latency_ms", nullable = false)
    private long retrievalLatencyMs;

    @Column(name = "generation_latency_ms", nullable = false)
    private long generationLatencyMs;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "provider")
    private String provider;

    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "similarity_threshold")
    private Double similarityThreshold;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AskLog() {
    }

    public AskLog(
        UUID id,
        UUID tenantId,
        UUID requestId,
        UUID userId,
        String question,
        boolean found,
        int tokenUsage,
        AskResultStatus resultStatus,
        AskFailureReason failureReason,
        long latencyMs,
        long embeddingLatencyMs,
        long retrievalLatencyMs,
        long generationLatencyMs,
        String modelId,
        String provider,
        Integer topK,
        Double similarityThreshold
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.requestId = requestId;
        this.userId = userId;
        this.question = question;
        this.found = found;
        this.tokenUsage = tokenUsage;
        this.resultStatus = resultStatus;
        this.failureReason = failureReason;
        this.latencyMs = latencyMs;
        this.embeddingLatencyMs = embeddingLatencyMs;
        this.retrievalLatencyMs = retrievalLatencyMs;
        this.generationLatencyMs = generationLatencyMs;
        this.modelId = modelId;
        this.provider = provider;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getRequestId() {
        return requestId;
    }

    public AskResultStatus getResultStatus() {
        return resultStatus;
    }

    public AskFailureReason getFailureReason() {
        return failureReason;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public long getEmbeddingLatencyMs() {
        return embeddingLatencyMs;
    }

    public long getRetrievalLatencyMs() {
        return retrievalLatencyMs;
    }

    public long getGenerationLatencyMs() {
        return generationLatencyMs;
    }
}
