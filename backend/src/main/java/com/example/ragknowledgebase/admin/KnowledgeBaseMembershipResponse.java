package com.example.ragknowledgebase.admin;

import java.util.UUID;

public record KnowledgeBaseMembershipResponse(
    UUID id,
    String principalType,
    UUID principalId,
    String permission
) {
}
