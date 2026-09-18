package com.example.ragknowledgebase.indexing;

import com.example.ragknowledgebase.document.DocumentContentSnapshot;
import java.time.Instant;
import java.util.UUID;

public record IndexTaskRecord(
    UUID id,
    UUID tenantId,
    UUID knowledgeBaseId,
    UUID documentId,
    UUID requestedBy,
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
    DocumentContentSnapshot rollbackContent,
    Instant createdAt
) {
}
