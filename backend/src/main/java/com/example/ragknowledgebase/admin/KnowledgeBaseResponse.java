package com.example.ragknowledgebase.admin;

import java.util.UUID;

public record KnowledgeBaseResponse(
    UUID id,
    String code,
    String name,
    String description,
    String status
) {
}
