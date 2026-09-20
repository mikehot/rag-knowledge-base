package com.example.ragknowledgebase.document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    public ChunkJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(List<ChunkRecord> chunks) {
        String sql = """
            INSERT INTO chunk (id, document_id, seq, locator, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?::vector)
            """;
        for (ChunkRecord chunk : chunks) {
            jdbcTemplate.update(
                sql,
                chunk.id(),
                chunk.documentId(),
                chunk.seq(),
                chunk.locator(),
                chunk.content(),
                vectorLiteral(chunk.embedding())
            );
        }
    }

    public void deleteByDocumentId(UUID documentId) {
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id = ?", documentId);
    }

    public List<ChunkSearchResult> search(UUID tenantId, UUID userId, float[] embedding, int topK) {
        return search(tenantId, userId, embedding, topK, null);
    }

    public List<ChunkSearchResult> search(
        UUID tenantId,
        UUID userId,
        float[] embedding,
        int topK,
        UUID knowledgeBaseId
    ) {
        String vector = vectorLiteral(embedding);
        String knowledgeBaseFilter = knowledgeBaseId == null ? "" : " AND d.knowledge_base_id = ?\n";
        String sql = """
            SELECT c.id, c.document_id, d.filename, c.locator, c.content,
                   (1 - (c.embedding <=> ?::vector)) AS similarity
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE d.tenant_id = ?
            """ + knowledgeBaseFilter + """
              AND d.status = 'READY'
              AND d.deleted_at IS NULL
              AND d.disabled_at IS NULL
              AND (
                d.user_id = ?
                OR EXISTS (
                  SELECT 1 FROM user_role ur
                  JOIN app_role r ON r.id = ur.role_id
                  WHERE ur.user_id = ?
                    AND r.tenant_id = ?
                    AND r.code = 'SYSTEM_ADMIN'
                )
                OR EXISTS (
                  SELECT 1 FROM knowledge_base_membership m
                  WHERE m.knowledge_base_id = d.knowledge_base_id
                    AND m.tenant_id = ?
                    AND m.permission IN ('READ', 'MANAGE')
                    AND (
                      (m.principal_type = 'USER' AND m.principal_id = ?)
                      OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
                        SELECT u.department_id FROM app_user u
                        WHERE u.id = ? AND u.tenant_id = ?
                      ))
                      OR (m.principal_type = 'ROLE' AND EXISTS (
                        SELECT 1 FROM user_role ur
                        WHERE ur.user_id = ? AND ur.role_id = m.principal_id
                      ))
                    )
                )
                OR EXISTS (
                  SELECT 1 FROM document_acl a
                  WHERE a.document_id = d.id
                    AND a.tenant_id = ?
                    AND a.permission IN ('READ', 'MANAGE')
                    AND (
                      (a.principal_type = 'USER' AND a.principal_id = ?)
                      OR (a.principal_type = 'DEPARTMENT' AND a.principal_id = (
                        SELECT u.department_id FROM app_user u
                        WHERE u.id = ? AND u.tenant_id = ?
                      ))
                      OR (a.principal_type = 'ROLE' AND EXISTS (
                        SELECT 1 FROM user_role ur
                        WHERE ur.user_id = ? AND ur.role_id = a.principal_id
                      ))
                    )
                )
              )
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """;
        List<Object> args = new ArrayList<>();
        args.add(vector);
        args.add(tenantId);
        if (knowledgeBaseId != null) {
            args.add(knowledgeBaseId);
        }
        args.add(userId);
        args.add(userId);
        args.add(tenantId);
        args.add(tenantId);
        args.add(userId);
        args.add(userId);
        args.add(tenantId);
        args.add(userId);
        args.add(tenantId);
        args.add(userId);
        args.add(userId);
        args.add(tenantId);
        args.add(userId);
        args.add(vector);
        args.add(topK);
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new ChunkSearchResult(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("filename"),
                rs.getString("locator"),
                rs.getString("content"),
                rs.getDouble("similarity")
            ),
            args.toArray()
        );
    }

    private String vectorLiteral(float[] values) {
        List<String> parts = new ArrayList<>(values.length);
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding 包含非法数值");
            }
            parts.add(Float.toString(value));
        }
        return "[" + String.join(",", parts) + "]";
    }
}
