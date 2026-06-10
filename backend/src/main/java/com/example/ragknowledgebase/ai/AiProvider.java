package com.example.ragknowledgebase.ai;

public interface AiProvider {
    AiProviderResponse generate(String prompt);
}
