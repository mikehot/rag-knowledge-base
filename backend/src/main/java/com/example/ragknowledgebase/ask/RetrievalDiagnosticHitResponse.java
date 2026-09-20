package com.example.ragknowledgebase.ask;

import java.util.UUID;

public record RetrievalDiagnosticHitResponse(
    int rank,
    UUID chunkId,
    UUID documentId,
    String filename,
    String locator,
    double similarity
) {
}
