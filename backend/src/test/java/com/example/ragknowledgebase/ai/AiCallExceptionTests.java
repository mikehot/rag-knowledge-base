package com.example.ragknowledgebase.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

class AiCallExceptionTests {
    @Test
    void recognizesDirectAndNestedTimeouts() {
        assertThat(AiCallException.isTimeout(new HttpTimeoutException("request timed out"))).isTrue();
        assertThat(AiCallException.isTimeout(new RuntimeException(
            "wrapped",
            new SocketTimeoutException("read timed out")
        ))).isTrue();
        assertThat(AiCallException.isTimeout(
            AiCallException.timeout("provider timeout", new RuntimeException("timeout"))
        )).isTrue();
    }

    @Test
    void doesNotClassifyOrdinaryProviderFailureAsTimeout() {
        assertThat(AiCallException.isTimeout(new AiCallException("HTTP 500"))).isFalse();
        assertThat(AiCallException.isTimeout(new IllegalStateException("empty response"))).isFalse();
    }
}
