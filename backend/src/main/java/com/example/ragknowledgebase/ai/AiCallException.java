package com.example.ragknowledgebase.ai;

public class AiCallException extends RuntimeException {
    private final boolean timeout;

    public AiCallException(String message) {
        this(message, null, false);
    }

    public AiCallException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private AiCallException(String message, Throwable cause, boolean timeout) {
        super(message, cause);
        this.timeout = timeout;
    }

    public static AiCallException timeout(String message, Throwable cause) {
        return new AiCallException(message, cause, true);
    }

    public boolean isTimeout() {
        return timeout;
    }
}
