package com.example.ragknowledgebase.indexing;

import java.util.List;

public record IndexTaskListResponse(
    List<IndexTaskResponse> items,
    int limit,
    boolean hasMore
) {
}
