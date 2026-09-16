package com.example.ragknowledgebase.admin;

import java.util.UUID;

public record DepartmentResponse(
    UUID id,
    UUID parentId,
    String code,
    String name,
    String status
) {
}
