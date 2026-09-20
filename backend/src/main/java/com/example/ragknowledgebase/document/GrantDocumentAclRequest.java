package com.example.ragknowledgebase.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GrantDocumentAclRequest(
    @NotBlank
    String principalType,

    @NotNull
    UUID principalId,

    @NotBlank
    String permission
) {
    public GrantDocumentAclRequest {
        if (principalType != null) {
            principalType = principalType.trim().toUpperCase();
        }
        if (permission != null) {
            permission = permission.trim().toUpperCase();
        }
    }
}
