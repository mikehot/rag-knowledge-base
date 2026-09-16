package com.example.ragknowledgebase.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
    UUID documentId,
    String filename,
    String fileType,
    String status,
    int chunkCount,
    String errorMsg,
    String checksum,
    int contentVersion,
    int permissionVersion,
    boolean disabled,
    OffsetDateTime createdAt
) {
    public static DocumentResponse from(KnowledgeDocument document) {
        return new DocumentResponse(
            document.getId(),
            document.getFilename(),
            document.getFileType(),
            document.getStatus().apiValue(),
            document.getChunkCount(),
            document.getErrorMsg(),
            document.getChecksum(),
            document.getContentVersion(),
            document.getPermissionVersion(),
            document.getDisabledAt() != null,
            document.getCreatedAt()
        );
    }
}
