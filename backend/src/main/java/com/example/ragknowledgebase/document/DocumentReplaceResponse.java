package com.example.ragknowledgebase.document;

import java.util.UUID;

public record DocumentReplaceResponse(
    UUID documentId,
    String status,
    int contentVersion,
    boolean unchanged
) {
    public static DocumentReplaceResponse from(KnowledgeDocument document, boolean unchanged) {
        return new DocumentReplaceResponse(
            document.getId(),
            document.getStatus().apiValue(),
            document.getContentVersion(),
            unchanged
        );
    }
}
