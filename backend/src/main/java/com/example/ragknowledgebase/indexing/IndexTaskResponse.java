package com.example.ragknowledgebase.indexing;

import java.time.Instant;
import java.util.UUID;

public record IndexTaskResponse(
    UUID id,
    UUID knowledgeBaseId,
    UUID documentId,
    String operation,
    int contentVersion,
    String status,
    int attemptCount,
    int maxAttempts,
    String errorMessage,
    Instant nextAttemptAt,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    Instant createdAt
) {
    public static IndexTaskResponse from(IndexTaskRecord task) {
        return new IndexTaskResponse(
            task.id(),
            task.knowledgeBaseId(),
            task.documentId(),
            task.operation(),
            task.contentVersion(),
            task.status(),
            task.attemptCount(),
            task.maxAttempts(),
            task.errorMessage(),
            task.nextAttemptAt(),
            task.startedAt(),
            task.finishedAt(),
            task.durationMs(),
            task.createdAt()
        );
    }
}
