package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.common.BusinessException;
import java.util.Locale;

public enum RetrievalMode {
    VECTOR,
    KEYWORD_RRF;

    public static RetrievalMode fromHeader(String value) {
        if (value == null || value.isBlank()) {
            return VECTOR;
        }
        try {
            return value.trim().toUpperCase(Locale.ROOT).equals("KEYWORD-RRF")
                ? KEYWORD_RRF
                : value.trim().toUpperCase(Locale.ROOT).equals("VECTOR")
                    ? VECTOR
                    : throwUnsupported();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "检索模式不支持");
        }
    }

    private static RetrievalMode throwUnsupported() {
        throw new IllegalArgumentException("unsupported retrieval mode");
    }
}
