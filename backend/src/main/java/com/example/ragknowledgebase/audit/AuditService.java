package com.example.ragknowledgebase.audit;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.observability.OperationalMetrics;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;
    private final OperationalMetrics operationalMetrics;

    public AuditService(JdbcTemplate jdbcTemplate, OperationalMetrics operationalMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationalMetrics = operationalMetrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(
        AuthenticatedUser user,
        String action,
        String resourceType,
        UUID resourceId,
        String reason
    ) {
        record(user, action, resourceType, resourceId, "DENY", reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAllowed(
        AuthenticatedUser user,
        String action,
        String resourceType,
        UUID resourceId,
        String reason
    ) {
        record(user, action, resourceType, resourceId, "ALLOW", reason);
    }

    /**
     * Records a successful permission change. Unlike denials, this joins the
     * caller's transaction so the change and its audit row commit or roll back
     * together; MANDATORY rejects calls made outside a transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPermissionChange(
        AuthenticatedUser user,
        String action,
        String resourceType,
        UUID resourceId,
        String detail
    ) {
        record(user, action, resourceType, resourceId, "ALLOW", detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(
        AuthenticatedUser user,
        String action,
        String resourceType,
        UUID resourceId,
        String reason
    ) {
        record(user, action, resourceType, resourceId, "ERROR", reason);
    }

    private void record(
        AuthenticatedUser user,
        String action,
        String resourceType,
        UUID resourceId,
        String outcome,
        String reason
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO audit_event (
                    id, tenant_id, user_id, username, action, resource_type, resource_id, outcome, reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            user.tenantId(),
            user.userId(),
            user.username(),
            action,
            resourceType,
            resourceId,
            outcome,
            reason
        );
        if ("DENY".equals(outcome)) {
            operationalMetrics.recordAclDenied(action, resourceType);
        }
    }
}
