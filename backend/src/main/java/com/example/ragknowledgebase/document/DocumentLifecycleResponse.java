package com.example.ragknowledgebase.document;

import java.util.UUID;

public record DocumentLifecycleResponse(
    UUID documentId,
    String status,
    int contentVersion,
    int permissionVersion,
    boolean disabled,
    boolean deleted,
    UUID taskId
) {
    public static DocumentLifecycleResponse from(KnowledgeDocument document) {
        return new DocumentLifecycleResponse(
            document.getId(),
            document.getStatus().apiValue(),
            document.getContentVersion(),
            document.getPermissionVersion(),
            document.getDisabledAt() != null,
            document.getDeletedAt() != null,
            null
        );
    }

    public static DocumentLifecycleResponse from(KnowledgeDocument document, UUID taskId) {
        return new DocumentLifecycleResponse(
            document.getId(),
            document.getStatus().apiValue(),
            document.getContentVersion(),
            document.getPermissionVersion(),
            document.getDisabledAt() != null,
            document.getDeletedAt() != null,
            taskId
        );
    }
}
