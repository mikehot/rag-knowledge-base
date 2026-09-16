package com.example.ragknowledgebase.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GrantKnowledgeBaseMembershipRequest(
    @NotBlank
    String principalType,

    @NotNull
    UUID principalId,

    @NotBlank
    String permission
) {
    public GrantKnowledgeBaseMembershipRequest {
        if (principalType != null) {
            principalType = principalType.trim().toUpperCase();
        }
        if (permission != null) {
            permission = permission.trim().toUpperCase();
        }
    }
}
