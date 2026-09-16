package com.example.ragknowledgebase.admin;

import java.util.UUID;

public record RoleResponse(
    UUID id,
    String code,
    String name
) {
}
