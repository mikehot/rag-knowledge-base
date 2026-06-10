package com.example.ragknowledgebase.auth;

public record LoginResponse(
    String token,
    long expiresIn
) {
}
