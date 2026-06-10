package com.example.ragknowledgebase.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "请输入用户名")
    String username,
    @NotBlank(message = "请输入密码")
    String password
) {
}
