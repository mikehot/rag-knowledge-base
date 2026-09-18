package com.example.ragknowledgebase.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000301");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Test
    void recordsAclDenialMetricOnlyAfterAuditWrite() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry, 0);
        AuditService service = new AuditService(jdbcTemplate, metrics);
        AuthenticatedUser user = new AuthenticatedUser(USER_ID, TENANT_ID, "audit-test");

        service.recordDenied(user, "DOCUMENT_DELETE", "DOCUMENT", UUID.randomUUID(), "MISSING_MANAGE");

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
        assertThat(registry.get("rag.acl.denied")
            .tags("action", "document_delete", "resource", "document")
            .counter()
            .count()).isEqualTo(1);
    }
}
