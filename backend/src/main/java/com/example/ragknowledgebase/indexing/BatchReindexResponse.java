package com.example.ragknowledgebase.indexing;

import java.util.List;
import java.util.UUID;

public record BatchReindexResponse(
    UUID knowledgeBaseId,
    int taskCount,
    List<UUID> taskIds
) {
}
