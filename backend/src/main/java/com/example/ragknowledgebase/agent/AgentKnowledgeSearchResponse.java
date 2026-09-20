package com.example.ragknowledgebase.agent;

import java.util.List;

public record AgentKnowledgeSearchResponse(
    boolean found,
    List<AgentKnowledgeSource> sources
) {
}
