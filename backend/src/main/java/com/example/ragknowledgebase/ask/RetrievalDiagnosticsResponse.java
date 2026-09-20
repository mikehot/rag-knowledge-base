package com.example.ragknowledgebase.ask;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RetrievalDiagnosticsResponse(
    UUID requestId,
    OffsetDateTime createdAt,
    AskResultStatus resultStatus,
    AskFailureReason failureReason,
    boolean found,
    int candidateCount,
    Double topSimilarity,
    Integer topK,
    Double similarityThreshold,
    List<RetrievalDiagnosticHitResponse> hits
) {
    public static RetrievalDiagnosticsResponse from(AskRetrievalDiagnostics diagnostics) {
        List<RetrievalDiagnosticHitResponse> hits = diagnostics.hits().stream()
            .map(hit -> new RetrievalDiagnosticHitResponse(
                hit.rank(),
                hit.chunkId(),
                hit.documentId(),
                hit.filename(),
                hit.locator(),
                hit.similarity()
            ))
            .toList();
        return new RetrievalDiagnosticsResponse(
            diagnostics.requestId(),
            diagnostics.createdAt(),
            diagnostics.resultStatus(),
            diagnostics.failureReason(),
            diagnostics.found(),
            hits.size(),
            hits.isEmpty() ? null : hits.get(0).similarity(),
            diagnostics.topK(),
            diagnostics.similarityThreshold(),
            hits
        );
    }
}
