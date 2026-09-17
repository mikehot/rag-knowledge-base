package com.example.ragknowledgebase.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    UUID userId,
    String username,
    String action,
    String resourceType,
    UUID resourceId,
    String outcome,
    String reason,
    Instant createdAt
) {
}
