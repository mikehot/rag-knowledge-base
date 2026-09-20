package com.example.ragknowledgebase.agent;

import java.util.List;
import java.util.UUID;

public record AgentToolExecutionResponse(
    UUID requestId,
    List<AgentToolCallResponse> results
) {
}
