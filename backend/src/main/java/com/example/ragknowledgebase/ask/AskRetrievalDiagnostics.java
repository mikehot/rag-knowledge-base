package com.example.ragknowledgebase.ask;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AskRetrievalDiagnostics(
    UUID askLogId,
    UUID requestId,
    OffsetDateTime createdAt,
    AskResultStatus resultStatus,
    AskFailureReason failureReason,
    boolean found,
    Integer topK,
    Double similarityThreshold,
    List<AskRetrievalHit> hits
) {
}
