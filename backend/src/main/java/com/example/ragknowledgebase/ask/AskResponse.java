package com.example.ragknowledgebase.ask;

import java.util.List;
import java.util.UUID;

public record AskResponse(
    String answer,
    boolean found,
    boolean grounded,
    List<AskSourceResponse> sources,
    UUID requestId,
    long latencyMs,
    int tokenUsage,
    AskFailureReason failureReason,
    AskTimingsResponse timings
) {
    public AskResponse(
        String answer,
        boolean found,
        List<AskSourceResponse> sources,
        UUID requestId,
        long latencyMs,
        int tokenUsage,
        AskFailureReason failureReason,
        AskTimingsResponse timings
    ) {
        this(answer, found, found, sources, requestId, latencyMs, tokenUsage, failureReason, timings);
    }
}
