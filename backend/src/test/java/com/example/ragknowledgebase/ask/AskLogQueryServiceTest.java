package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AskLogQueryServiceTest {

    @Test
    void rejectsNonAuditReaderAndRecordsAuditEvent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccessControlService accessControl = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        AskLogQueryService service = new AskLogQueryService(jdbcTemplate, accessControl, auditService);
        AuthenticatedUser employee = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "alice");

        when(accessControl.canReadAuditEvents(employee)).thenReturn(false);

        assertThatThrownBy(() -> service.list(employee, null, null, null, true, 50))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SYSTEM_ADMIN");

        verify(auditService).recordDenied(
            employee,
            "ASK_LOG_LIST",
            "TENANT",
            employee.tenantId(),
            "MISSING_AUDIT_READER"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesTenantScopedNeedsReviewLogs() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccessControlService accessControl = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        AskLogQueryService service = new AskLogQueryService(jdbcTemplate, accessControl, auditService);
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "admin");

        when(accessControl.canReadAuditEvents(admin)).thenReturn(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());

        AskLogSummaryListResponse response = service.list(admin, "NOT_FOUND", null, null, true, 25);

        assertThat(response.items()).isEmpty();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            any(RowMapper.class),
            eq(admin.tenantId()),
            eq("NOT_FOUND"),
            eq(25)
        );
        assertThat(sqlCaptor.getValue())
            .contains("al.tenant_id = ?")
            .contains("al.result_status <> 'ANSWERED' OR af.rating = 'NOT_HELPFUL'")
            .contains("al.result_status = ?");
    }
}
