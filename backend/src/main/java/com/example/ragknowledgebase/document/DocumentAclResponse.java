package com.example.ragknowledgebase.document;

import java.util.UUID;

public record DocumentAclResponse(
    UUID id,
    UUID documentId,
    String principalType,
    UUID principalId,
    String permission
) {
}
