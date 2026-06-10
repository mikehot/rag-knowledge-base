package com.example.ragknowledgebase.document;

public record ChunkDraft(
    int seq,
    String locator,
    String content
) {
}
