package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only view used while switching the embedding model. Search only compares vectors produced by
 * the configured model, so documents still on another model are not retrievable until they are
 * reindexed; this reports how many are waiting.
 */
@Service
public class EmbeddingStatusService {
    private final JdbcTemplate jdbcTemplate;
    private final ChunkJdbcRepository chunkRepository;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public EmbeddingStatusService(
        JdbcTemplate jdbcTemplate,
        ChunkJdbcRepository chunkRepository,
        AccessControlService accessControlService,
        AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunkRepository = chunkRepository;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    public EmbeddingStatusResponse get(AuthenticatedUser user) {
        if (!accessControlService.canReadAuditEvents(user)) {
            auditService.recordDenied(user, "EMBEDDING_STATUS_GET", "EMBEDDING", null, "MISSING_AUDIT_READER");
            throw new BusinessException(403, "需要 SYSTEM_ADMIN 或 AUDITOR 权限");
        }
        String configured = chunkRepository.embeddingModel();
        UUID tenantId = user.tenantId();
        List<EmbeddingStatusResponse.ModelUsage> models = jdbcTemplate.query(
            """
                SELECT c.embedding_model, COALESCE(c.embedding_dim, 0) AS dimensions,
                       COUNT(*) AS chunks, COUNT(DISTINCT c.document_id) AS documents
                FROM chunk c
                JOIN document d ON d.id = c.document_id
                WHERE d.tenant_id = ? AND d.deleted_at IS NULL
                GROUP BY c.embedding_model, c.embedding_dim
                ORDER BY chunks DESC, c.embedding_model
                """,
            (rs, rowNum) -> new EmbeddingStatusResponse.ModelUsage(
                rs.getString("embedding_model"),
                rs.getInt("dimensions"),
                rs.getLong("chunks"),
                rs.getLong("documents"),
                configured.equals(rs.getString("embedding_model"))
            ),
            tenantId
        );
        long indexed = count(
            """
                SELECT COUNT(*) FROM document d
                WHERE d.tenant_id = ? AND d.deleted_at IS NULL AND d.disabled_at IS NULL
                  AND EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id)
                """,
            tenantId
        );
        long onConfigured = count(
            """
                SELECT COUNT(*) FROM document d
                WHERE d.tenant_id = ? AND d.deleted_at IS NULL AND d.disabled_at IS NULL
                  AND EXISTS (
                    SELECT 1 FROM chunk c WHERE c.document_id = d.id AND c.embedding_model = ?
                  )
                """,
            tenantId,
            configured
        );
        long waiting = Math.max(0, indexed - onConfigured);
        return new EmbeddingStatusResponse(configured, models, indexed, onConfigured, waiting, waiting == 0);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
