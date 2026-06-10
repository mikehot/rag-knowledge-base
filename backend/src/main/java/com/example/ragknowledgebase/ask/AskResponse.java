package com.example.ragknowledgebase.ask;

import java.util.List;

public record AskResponse(
    String answer,
    boolean found,
    List<AskSourceResponse> sources,
    int tokenUsage
) {
}
