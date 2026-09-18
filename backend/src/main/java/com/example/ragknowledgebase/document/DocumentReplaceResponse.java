package com.example.ragknowledgebase.document;

import java.util.UUID;

public record DocumentReplaceResponse(
    UUID documentId,
    String status,
    int contentVersion,
    boolean unchanged,
    UUID taskId
) {
    public static DocumentReplaceResponse from(KnowledgeDocument document, boolean unchanged, UUID taskId) {
        return new DocumentReplaceResponse(
            document.getId(),
            document.getStatus().apiValue(),
            document.getContentVersion(),
            unchanged,
            taskId
        );
    }
}
