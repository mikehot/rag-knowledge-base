package com.example.ragknowledgebase.ai;

import java.util.List;

public interface EmbeddingProvider {
    List<float[]> embed(List<String> inputs);
}
