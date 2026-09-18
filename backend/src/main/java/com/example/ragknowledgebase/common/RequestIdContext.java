package com.example.ragknowledgebase.common;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestIdContext {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private RequestIdContext() {
    }

    public static UUID currentOrNew() {
        String value = MDC.get(MDC_KEY);
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // A filter-owned UUID is expected; fail closed to a fresh correlation id.
            }
        }
        return UUID.randomUUID();
    }
}
