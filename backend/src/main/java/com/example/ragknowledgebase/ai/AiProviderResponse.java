package com.example.ragknowledgebase.ai;

public record AiProviderResponse(
    String text,
    int tokenUsage,
    int completionTokens,
    String finishReason
) {
    public AiProviderResponse(String text, int tokenUsage) {
        this(text, tokenUsage, 0, "unknown");
    }
}
