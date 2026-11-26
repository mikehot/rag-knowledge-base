package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AskLogQueryService {
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public AskLogQueryService(
        JdbcTemplate jdbcTemplate,
        AccessControlService accessControlService,
        AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    public AskLogSummaryListResponse list(
        AuthenticatedUser user,
        String resultStatus,
        String failureReason,
        String rating,
        boolean needsReview,
        int limit
    ) {
        if (!accessControlService.canReadAuditEvents(user)) {
            auditService.recordDenied(
                user,
                "ASK_LOG_LIST",
                "TENANT",
                user.tenantId(),
                "MISSING_AUDIT_READER"
            );
            throw new BusinessException(403, "需要 SYSTEM_ADMIN 或 AUDITOR 权限");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(400, "limit 必须在 1 到 200 之间");
        }

        StringBuilder sql = new StringBuilder("""
            SELECT
                al.request_id,
                al.user_id,
                al.result_status,
                al.failure_reason,
                al.found,
                COALESCE(al.token_usage, 0) AS token_usage,
                al.latency_ms,
                al.model_id,
                al.retrieval_mode,
                af.rating AS feedback_rating,
                af.reason AS feedback_reason,
                al.created_at
            FROM ask_log al
            LEFT JOIN ask_feedback af ON af.ask_log_id = al.id
            WHERE al.tenant_id = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(user.tenantId());

        if (needsReview) {
            sql.append(" AND (al.result_status <> 'ANSWERED' OR af.rating = 'NOT_HELPFUL')");
        }
        if (resultStatus != null && !resultStatus.isBlank()) {
            sql.append(" AND al.result_status = ?");
            params.add(resultStatus.trim().toUpperCase(Locale.ROOT));
        }
        if (failureReason != null && !failureReason.isBlank()) {
            sql.append(" AND al.failure_reason = ?");
            params.add(failureReason.trim().toUpperCase(Locale.ROOT));
        }
        if (rating != null && !rating.isBlank()) {
            sql.append(" AND af.rating = ?");
            params.add(rating.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY al.created_at DESC LIMIT ?");
        params.add(limit);

        List<AskLogSummaryListResponse.AskLogSummaryItem> items = jdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("created_at");
                return new AskLogSummaryListResponse.AskLogSummaryItem(
                    UUID.fromString(rs.getString("request_id")),
                    UUID.fromString(rs.getString("user_id")),
                    rs.getString("result_status"),
                    rs.getString("failure_reason"),
                    rs.getBoolean("found"),
                    rs.getInt("token_usage"),
                    rs.getLong("latency_ms"),
                    rs.getString("model_id"),
                    rs.getString("retrieval_mode"),
                    rs.getString("feedback_rating"),
                    rs.getString("feedback_reason"),
                    ts != null ? ts.toInstant() : null
                );
            },
            params.toArray()
        );
        return new AskLogSummaryListResponse(items);
    }
}
