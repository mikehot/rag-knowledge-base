package com.example.ragknowledgebase.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateDepartmentRequest(
    @NotBlank
    @Size(max = 64)
    String code,

    @NotBlank
    @Size(max = 255)
    String name,

    UUID parentId
) {
    public CreateDepartmentRequest {
        if (code != null) {
            code = code.trim().toUpperCase();
        }
    }
}
