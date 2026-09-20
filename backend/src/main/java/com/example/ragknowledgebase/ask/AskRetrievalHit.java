package com.example.ragknowledgebase.ask;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AskRetrievalHit(
    UUID id,
    int rank,
    UUID chunkId,
    UUID documentId,
    String filename,
    String locator,
    double similarity,
    OffsetDateTime createdAt
) {
}
