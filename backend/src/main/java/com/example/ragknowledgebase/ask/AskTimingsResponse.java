package com.example.ragknowledgebase.ask;

public record AskTimingsResponse(
    long embeddingMs,
    long retrievalMs,
    long generationMs
) {
}
