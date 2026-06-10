package com.example.ragknowledgebase.ask;

import java.util.UUID;

public record AskSourceResponse(
    UUID documentId,
    String filename,
    String locator,
    String snippet
) {
}
