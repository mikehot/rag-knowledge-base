package com.example.ragknowledgebase.agent;

import java.util.UUID;

public record AgentKnowledgeSource(
    UUID documentId,
    String filename,
    String locator,
    String snippet,
    double similarity
) {
}
