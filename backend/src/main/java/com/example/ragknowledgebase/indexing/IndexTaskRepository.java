package com.example.ragknowledgebase.indexing;

import com.example.ragknowledgebase.document.DocumentContentSnapshot;
import com.example.ragknowledgebase.document.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IndexTaskRepository {
    private final JdbcTemplate jdbcTemplate;

    public IndexTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public IndexTaskRecord enqueue(
        UUID tenantId,
        UUID knowledgeBaseId,
        UUID documentId,
        UUID requestedBy,
        String operation,
        int contentVersion,
        DocumentContentSnapshot rollbackContent
    ) {
        String idempotencyKey = documentId + ":" + contentVersion;
        UUID taskId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                INSERT INTO index_task (
                    id, tenant_id, knowledge_base_id, document_id, requested_by,
                    operation, content_version, idempotency_key,
                    rollback_filename, rollback_file_type, rollback_file_path,
                    rollback_checksum, rollback_content_version, rollback_status,
                    rollback_chunk_count, rollback_error_msg
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
            taskId,
            tenantId,
            knowledgeBaseId,
            documentId,
            requestedBy,
            operation,
            contentVersion,
            idempotencyKey,
            rollbackContent == null ? null : rollbackContent.filename(),
            rollbackContent == null ? null : rollbackContent.fileType(),
            rollbackContent == null ? null : rollbackContent.filePath(),
            rollbackContent == null ? null : rollbackContent.checksum(),
            rollbackContent == null ? null : rollbackContent.contentVersion(),
            rollbackContent == null ? null : rollbackContent.status().name(),
            rollbackContent == null ? null : rollbackContent.chunkCount(),
            rollbackContent == null ? null : rollbackContent.errorMsg()
        );
        return findByIdempotencyKey(idempotencyKey).orElseThrow();
    }

    @Transactional
    public Optional<IndexTaskRecord> claimNext() {
        List<IndexTaskRecord> rows = jdbcTemplate.query(
            """
                WITH candidate AS (
                    SELECT id
                    FROM index_task
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE index_task task
                SET status = 'RUNNING',
                    attempt_count = task.attempt_count + 1,
                    started_at = COALESCE(task.started_at, now()),
                    updated_at = now()
                FROM candidate
                WHERE task.id = candidate.id
                RETURNING task.*
                """,
            (rs, rowNum) -> map(rs)
        );
        return rows.stream().findFirst();
    }

    public Optional<IndexTaskRecord> find(UUID tenantId, UUID knowledgeBaseId, UUID taskId) {
        return jdbcTemplate.query(
            "SELECT * FROM index_task WHERE id = ? AND tenant_id = ? AND knowledge_base_id = ?",
            (rs, rowNum) -> map(rs),
            taskId,
            tenantId,
            knowledgeBaseId
        ).stream().findFirst();
    }

    public List<IndexTaskRecord> list(UUID tenantId, UUID knowledgeBaseId, String status, int limit) {
        if (status == null) {
            return jdbcTemplate.query(
                """
                    SELECT * FROM index_task
                    WHERE tenant_id = ? AND knowledge_base_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """,
                (rs, rowNum) -> map(rs),
                tenantId,
                knowledgeBaseId,
                limit
            );
        }
        return jdbcTemplate.query(
            """
                SELECT * FROM index_task
                WHERE tenant_id = ? AND knowledge_base_id = ? AND status = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
            (rs, rowNum) -> map(rs),
            tenantId,
            knowledgeBaseId,
            status,
            limit
        );
    }

    @Transactional
    public void markSucceeded(UUID taskId) {
        jdbcTemplate.update(
            """
                UPDATE index_task
                SET status = 'SUCCEEDED', error_msg = NULL, finished_at = now(),
                    duration_ms = (extract(epoch FROM (now() - started_at)) * 1000)::bigint,
                    updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """,
            taskId
        );
    }

    @Transactional
    public void scheduleRetry(UUID taskId, String errorMessage, long delaySeconds) {
        jdbcTemplate.update(
            """
                UPDATE index_task
                SET status = 'PENDING', error_msg = ?,
                    next_attempt_at = now() + (? * interval '1 second'),
                    updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """,
            errorMessage,
            delaySeconds,
            taskId
        );
    }

    @Transactional
    public void markSuperseded(UUID taskId) {
        jdbcTemplate.update(
            """
                UPDATE index_task
                SET status = 'SUCCEEDED', error_msg = 'SUPERSEDED_BY_NEWER_VERSION', finished_at = now(),
                    duration_ms = (extract(epoch FROM (now() - started_at)) * 1000)::bigint,
                    updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """,
            taskId
        );
    }

    @Transactional
    public boolean markFailed(UUID taskId, String errorMessage) {
        return jdbcTemplate.update(
            """
                UPDATE index_task
                SET status = 'FAILED', error_msg = ?, finished_at = now(),
                    duration_ms = (extract(epoch FROM (now() - started_at)) * 1000)::bigint,
                    updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """,
            errorMessage,
            taskId
        ) == 1;
    }

    @Transactional
    public boolean retryFailed(UUID tenantId, UUID knowledgeBaseId, UUID taskId) {
        return jdbcTemplate.update(
            """
                UPDATE index_task
                SET status = 'PENDING', error_msg = NULL, next_attempt_at = now(),
                    finished_at = NULL, duration_ms = NULL,
                    max_attempts = attempt_count + 3, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND knowledge_base_id = ? AND status = 'FAILED'
                """,
            taskId,
            tenantId,
            knowledgeBaseId
        ) == 1;
    }

    @Transactional
    public int recoverStaleRunningTasks() {
        return jdbcTemplate.update(
            """
                UPDATE index_task
                SET status = 'PENDING', next_attempt_at = now(),
                    error_msg = 'RECOVERED_STALE_RUNNING_TASK', updated_at = now()
                WHERE status = 'RUNNING'
                  AND updated_at < now() - interval '10 minutes'
                """
        );
    }

    private Optional<IndexTaskRecord> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query(
            "SELECT * FROM index_task WHERE idempotency_key = ?",
            (rs, rowNum) -> map(rs),
            idempotencyKey
        ).stream().findFirst();
    }

    private IndexTaskRecord map(ResultSet rs) throws SQLException {
        Integer rollbackVersion = rs.getObject("rollback_content_version", Integer.class);
        DocumentContentSnapshot rollbackContent = rollbackVersion == null ? null : new DocumentContentSnapshot(
            rs.getString("rollback_filename"),
            rs.getString("rollback_file_type"),
            rs.getString("rollback_file_path"),
            rs.getString("rollback_checksum"),
            rollbackVersion,
            DocumentStatus.valueOf(rs.getString("rollback_status")),
            rs.getInt("rollback_chunk_count"),
            rs.getString("rollback_error_msg")
        );
        return new IndexTaskRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("knowledge_base_id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getObject("requested_by", UUID.class),
            rs.getString("operation"),
            rs.getInt("content_version"),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            rs.getString("error_msg"),
            instant(rs, "next_attempt_at"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            rs.getObject("duration_ms", Long.class),
            rollbackContent,
            instant(rs, "created_at")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
