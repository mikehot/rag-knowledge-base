package com.example.ragknowledgebase.auth;

import java.util.UUID;

public record AuthenticatedUser(
    UUID userId,
    String username
) {
}
