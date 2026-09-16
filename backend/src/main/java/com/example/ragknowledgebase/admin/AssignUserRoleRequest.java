package com.example.ragknowledgebase.admin;

import jakarta.validation.constraints.NotBlank;

public record AssignUserRoleRequest(
    @NotBlank
    String roleCode
) {
    public AssignUserRoleRequest {
        if (roleCode != null) {
            roleCode = roleCode.trim().toUpperCase();
        }
    }
}
