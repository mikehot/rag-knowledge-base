package com.example.ragknowledgebase.document;

import java.util.UUID;

public record ChunkRecord(
    UUID id,
    UUID documentId,
    int seq,
    String locator,
    String content,
    float[] embedding
) {
}
