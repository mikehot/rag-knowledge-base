package com.example.ragknowledgebase.ask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AskLogSummaryListResponse(List<AskLogSummaryItem> items) {

    public record AskLogSummaryItem(
        UUID requestId,
        UUID userId,
        String resultStatus,
        String failureReason,
        boolean found,
        int tokenUsage,
        long latencyMs,
        String modelId,
        String retrievalMode,
        String feedbackRating,
        String feedbackReason,
        Instant createdAt
    ) {
    }
}
