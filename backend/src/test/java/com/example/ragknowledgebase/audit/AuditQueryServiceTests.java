package com.example.ragknowledgebase.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000201");
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000202");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "audit-test");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(jdbcTemplate, accessControlService, auditService);
    }

    @Test
    void employeeIsDeniedAndTheAttemptIsAudited() {
        when(accessControlService.canReadAuditEvents(USER)).thenReturn(false);

        assertThatThrownBy(() -> list(100, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(403);

        verify(auditService).recordDenied(
            USER,
            "AUDIT_EVENT_LIST",
            "TENANT",
            TENANT_ID,
            "MISSING_AUDIT_READER"
        );
        verify(jdbcTemplate, never()).query(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<AuditEventResponse>>any(),
            org.mockito.ArgumentMatchers.<Object[]>any()
        );
    }

    @Test
    void invalidLimitIsRejectedBeforeQuerying() {
        when(accessControlService.canReadAuditEvents(USER)).thenReturn(true);

        assertThatThrownBy(() -> list(201, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(400);

        verify(jdbcTemplate, never()).query(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<AuditEventResponse>>any(),
            org.mockito.ArgumentMatchers.<Object[]>any()
        );
    }

    @Test
    void reversedTimeRangeIsRejectedBeforeQuerying() {
        when(accessControlService.canReadAuditEvents(USER)).thenReturn(true);
        Instant from = Instant.parse("2026-09-17T00:01:00Z");
        Instant to = Instant.parse("2026-09-17T00:00:00Z");

        assertThatThrownBy(() -> list(100, from, to))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(400);

        verify(jdbcTemplate, never()).query(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<AuditEventResponse>>any(),
            org.mockito.ArgumentMatchers.<Object[]>any()
        );
    }

    private AuditEventListResponse list(int limit, Instant from, Instant to) {
        return service.list(USER, null, null, null, null, null, from, to, limit);
    }
}
