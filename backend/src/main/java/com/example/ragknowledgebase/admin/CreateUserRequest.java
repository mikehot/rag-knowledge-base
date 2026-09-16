package com.example.ragknowledgebase.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateUserRequest(
    @NotBlank
    @Size(max = 255)
    String username,

    @NotBlank
    @Size(min = 8, max = 128)
    String password,

    @Size(max = 255)
    String displayName,

    UUID departmentId,

    List<String> roleCodes
) {
    public List<String> normalizedRoleCodes() {
        if (roleCodes == null) {
            return List.of("EMPLOYEE");
        }
        return roleCodes.stream()
            .filter(code -> code != null && !code.isBlank())
            .map(code -> code.trim().toUpperCase())
            .distinct()
            .toList();
    }
}
