package com.example.ragknowledgebase.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentToolCallResponse(
    String name,
    boolean success,
    JsonNode data,
    AgentToolFailureReason failureReason
) {
}
