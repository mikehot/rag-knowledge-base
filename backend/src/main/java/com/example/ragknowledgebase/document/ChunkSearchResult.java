package com.example.ragknowledgebase.document;

import java.util.UUID;

public record ChunkSearchResult(
    UUID chunkId,
    UUID documentId,
    String filename,
    String locator,
    String content,
    double similarity
) {
}
