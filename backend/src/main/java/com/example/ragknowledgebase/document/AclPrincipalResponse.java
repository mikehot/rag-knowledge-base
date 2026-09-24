package com.example.ragknowledgebase.document;

import java.util.UUID;

public record AclPrincipalResponse(
    UUID id,
    String username,
    String displayName,
    String name,
    String code,
    String status
) {
}
