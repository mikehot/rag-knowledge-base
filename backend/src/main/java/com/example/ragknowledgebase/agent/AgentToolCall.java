package com.example.ragknowledgebase.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentToolCall(
    String name,
    JsonNode arguments
) {
}
