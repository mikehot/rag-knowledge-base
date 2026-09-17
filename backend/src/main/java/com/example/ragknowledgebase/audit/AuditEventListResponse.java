package com.example.ragknowledgebase.audit;

import java.util.List;

public record AuditEventListResponse(
    List<AuditEventResponse> items,
    int limit,
    boolean hasMore
) {
}
