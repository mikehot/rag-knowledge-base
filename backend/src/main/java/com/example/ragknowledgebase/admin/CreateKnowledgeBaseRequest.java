package com.example.ragknowledgebase.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
    @NotBlank
    @Size(max = 64)
    String code,

    @NotBlank
    @Size(max = 255)
    String name,

    String description
) {
    public CreateKnowledgeBaseRequest {
        if (code != null) {
            code = code.trim().toLowerCase();
        }
    }
}
