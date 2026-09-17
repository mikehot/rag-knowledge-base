package com.example.ragknowledgebase.document;

import java.util.UUID;

public record DocumentCreatedEvent(
    UUID documentId,
    DocumentContentSnapshot rollbackContent
) {
    public DocumentCreatedEvent(UUID documentId) {
        this(documentId, null);
    }
}
