package com.example.ragknowledgebase.agent;

import java.util.Map;

public record AgentToolDefinitionResponse(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
}
