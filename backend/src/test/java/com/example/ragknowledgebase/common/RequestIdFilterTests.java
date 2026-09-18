package com.example.ragknowledgebase.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void preservesValidCallerRequestIdAndClearsMdc() throws Exception {
        UUID requestId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER, requestId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> requestContextId = new AtomicReference<>();

        filter.doFilter(
            request,
            response,
            (servletRequest, servletResponse) -> requestContextId.set(RequestIdContext.currentOrNew())
        );

        assertThat(response.getHeader(RequestIdContext.HEADER)).isEqualTo(requestId.toString());
        assertThat(requestContextId.get()).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdContext.MDC_KEY)).isNull();
    }

    @Test
    void replacesInvalidCallerRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdContext.HEADER, "unsafe-value-with-newline\n");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(UUID.fromString(response.getHeader(RequestIdContext.HEADER))).isNotNull();
        assertThat(response.getHeader(RequestIdContext.HEADER)).doesNotContain("unsafe-value");
    }
}
