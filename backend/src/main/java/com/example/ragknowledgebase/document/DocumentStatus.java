package com.example.ragknowledgebase.document;

public enum DocumentStatus {
    PROCESSING,
    READY,
    FAILED;

    public String apiValue() {
        return name().toLowerCase();
    }
}
