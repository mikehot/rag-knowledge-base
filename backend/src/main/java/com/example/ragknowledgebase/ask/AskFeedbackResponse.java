package com.example.ragknowledgebase.ask;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AskFeedbackResponse(
    UUID requestId,
    AskFeedbackRating rating,
    String reason,
    OffsetDateTime updatedAt
) {
}
