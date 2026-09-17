package com.example.ragknowledgebase.audit;

import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditQueryService {
    private static final int MAX_LIMIT = 200;
    private static final Set<String> OUTCOMES = Set.of("ALLOW", "DENY", "ERROR");

    private final JdbcTemplate jdbcTemplate;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public AuditQueryService(
        JdbcTemplate jdbcTemplate,
        AccessControlService accessControlService,
        AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    public AuditEventListResponse list(
        AuthenticatedUser user,
        UUID userId,
        String action,
        String resourceType,
        UUID resourceId,
        String outcome,
        Instant from,
        Instant to,
        int limit
    ) {
        if (!accessControlService.canReadAuditEvents(user)) {
            auditService.recordDenied(
                user,
                "AUDIT_EVENT_LIST",
                "TENANT",
                user.tenantId(),
                "MISSING_AUDIT_READER"
            );
            throw new BusinessException(403, "需要 SYSTEM_ADMIN 或 AUDITOR 权限");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(400, "limit 必须在 1 到 200 之间");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(400, "from 不能晚于 to");
        }

        String normalizedAction = normalize(action, 128, "action");
        String normalizedResourceType = normalize(resourceType, 64, "resourceType");
        String normalizedOutcome = normalize(outcome, 32, "outcome");
        if (normalizedOutcome != null && !OUTCOMES.contains(normalizedOutcome)) {
            throw new BusinessException(400, "outcome 仅支持 ALLOW、DENY、ERROR");
        }

        StringBuilder sql = new StringBuilder("""
            SELECT id, user_id, username, action, resource_type, resource_id, outcome, reason, created_at
            FROM audit_event
            WHERE tenant_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(user.tenantId());
        append(sql, args, "user_id = ?", userId);
        append(sql, args, "action = ?", normalizedAction);
        append(sql, args, "resource_type = ?", normalizedResourceType);
        append(sql, args, "resource_id = ?", resourceId);
        append(sql, args, "outcome = ?", normalizedOutcome);
        append(sql, args, "created_at >= ?", from == null ? null : Timestamp.from(from));
        append(sql, args, "created_at <= ?", to == null ? null : Timestamp.from(to));
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);

        List<AuditEventResponse> rows = jdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> new AuditEventResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                rs.getString("outcome"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()
            ),
            args.toArray()
        );
        boolean hasMore = rows.size() > limit;
        List<AuditEventResponse> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new AuditEventListResponse(items, limit, hasMore);
    }

    private String normalize(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > maxLength) {
            throw new BusinessException(400, field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private void append(StringBuilder sql, List<Object> args, String condition, Object value) {
        if (value != null) {
            sql.append(" AND ").append(condition);
            args.add(value);
        }
    }
}
