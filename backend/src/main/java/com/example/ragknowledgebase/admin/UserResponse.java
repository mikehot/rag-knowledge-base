package com.example.ragknowledgebase.admin;

import java.util.List;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String username,
    String displayName,
    String status,
    UUID departmentId,
    List<String> roleCodes
) {
}
