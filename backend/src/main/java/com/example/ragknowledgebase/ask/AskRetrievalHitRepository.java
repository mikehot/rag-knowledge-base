package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AskRetrievalHitRepository {
    private final JdbcTemplate jdbcTemplate;

    public AskRetrievalHitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(UUID askLogId, UUID tenantId, List<ChunkSearchResult> hits) {
        if (hits.isEmpty()) {
            return;
        }
        OffsetDateTime createdAt = OffsetDateTime.now();
        for (int index = 0; index < hits.size(); index++) {
            ChunkSearchResult hit = hits.get(index);
            jdbcTemplate.update(
                """
                    INSERT INTO ask_retrieval_hit (
                        id, ask_log_id, tenant_id, rank, chunk_id, document_id,
                        filename, locator, similarity, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                UUID.randomUUID(),
                askLogId,
                tenantId,
                index + 1,
                hit.chunkId(),
                hit.documentId(),
                hit.filename(),
                hit.locator(),
                hit.similarity(),
                Timestamp.from(createdAt.toInstant())
            );
        }
    }

    public Optional<AskRetrievalDiagnostics> findByRequestId(UUID tenantId, UUID requestId) {
        List<AskRetrievalDiagnostics> diagnostics = jdbcTemplate.query(
            """
                SELECT id, request_id, created_at, result_status, failure_reason,
                       found, retrieval_mode, top_k, similarity_threshold
                FROM ask_log
                WHERE tenant_id = ? AND request_id = ?
                """,
            (rs, rowNum) -> {
                UUID askLogId = rs.getObject("id", UUID.class);
                return new AskRetrievalDiagnostics(
                    askLogId,
                    rs.getObject("request_id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class),
                    AskResultStatus.valueOf(rs.getString("result_status")),
                    rs.getString("failure_reason") == null
                        ? null
                        : AskFailureReason.valueOf(rs.getString("failure_reason")),
                    rs.getBoolean("found"),
                    RetrievalMode.valueOf(rs.getString("retrieval_mode")),
                    (Integer) rs.getObject("top_k"),
                    (Double) rs.getObject("similarity_threshold"),
                    findHits(askLogId)
                );
            },
            tenantId,
            requestId
        );
        return diagnostics.stream().findFirst();
    }

    private List<AskRetrievalHit> findHits(UUID askLogId) {
        return jdbcTemplate.query(
            """
                SELECT id, rank, chunk_id, document_id, filename, locator, similarity, created_at
                FROM ask_retrieval_hit
                WHERE ask_log_id = ?
                ORDER BY rank ASC
                """,
            (rs, rowNum) -> new AskRetrievalHit(
                rs.getObject("id", UUID.class),
                rs.getInt("rank"),
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("filename"),
                rs.getString("locator"),
                rs.getDouble("similarity"),
                rs.getObject("created_at", OffsetDateTime.class)
            ),
            askLogId
        );
    }
}
