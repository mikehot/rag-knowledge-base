package com.example.ragknowledgebase.audit;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(
        AuthenticatedUser user,
        String action,
        String resourceType,
        UUID resourceId,
        String reason
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO audit_event (
                    id, tenant_id, user_id, username, action, resource_type, resource_id, outcome, reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DENY', ?)
                """,
            UUID.randomUUID(),
            user.tenantId(),
            user.userId(),
            user.username(),
            action,
            resourceType,
            resourceId,
            reason
        );
    }
}
