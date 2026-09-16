package com.example.ragknowledgebase.document;

import java.util.UUID;

public record DocumentUploadResponse(
    UUID documentId,
    String status,
    boolean duplicated
) {
}
