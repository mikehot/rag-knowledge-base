package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkJdbcRepository {
    /** pgvector HNSW indexes support at most this many dimensions. */
    private static final int MAX_DIMENSIONS = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final String embeddingModel;

    public ChunkJdbcRepository(JdbcTemplate jdbcTemplate, AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = properties.embedding().modelId();
    }

    /** The embedding model whose vectors this repository writes and searches. */
    public String embeddingModel() {
        return embeddingModel;
    }

    public void insertAll(List<ChunkRecord> chunks) {
        String sql = """
            INSERT INTO chunk (id, document_id, seq, locator, content, embedding, embedding_model)
            VALUES (?, ?, ?, ?, ?, ?::vector, ?)
            """;
        for (ChunkRecord chunk : chunks) {
            jdbcTemplate.update(
                sql,
                chunk.id(),
                chunk.documentId(),
                chunk.seq(),
                chunk.locator(),
                chunk.content(),
                vectorLiteral(chunk.embedding()),
                embeddingModel
            );
        }
    }

    public void deleteByDocumentId(UUID documentId) {
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id = ?", documentId);
    }

    public List<ChunkSearchResult> search(UUID tenantId, UUID userId, float[] embedding, int topK) {
        return search(tenantId, userId, embedding, topK, null);
    }

    /**
     * Chunk rows only exist for a committed index version: DocumentProcessor
     * replaces them in one transaction after all embeddings succeed. The
     * document status is therefore not a retrieval filter, so a document keeps
     * answering from its previous version while a reindex or replacement is
     * processing or after it fails.
     *
     * <p>Only vectors produced by the configured embedding model, with the query's dimension, are
     * compared: vectors from different models live in different spaces. Chunks written by another
     * model are invisible until their document is reindexed (see GET /api/admin/embeddings).
     */
    public List<ChunkSearchResult> search(
        UUID tenantId,
        UUID userId,
        float[] embedding,
        int topK,
        UUID knowledgeBaseId
    ) {
        if (embedding.length < 1 || embedding.length > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Embedding 维度不受支持: " + embedding.length);
        }
        String vector = vectorLiteral(embedding);
        String knowledgeBaseFilter = knowledgeBaseId == null ? "" : " AND d.knowledge_base_id = ?\n";
        String sql = """
            SELECT c.id, c.document_id, d.filename, c.locator, c.content,
                   (1 - ((c.embedding::{{VT}}) <=> ?::{{VT}})) AS similarity
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE d.tenant_id = ?
            """ + knowledgeBaseFilter + """
              AND c.embedding_model = ?
              AND c.embedding_dim = {{DIM}}
              AND d.deleted_at IS NULL
              AND d.disabled_at IS NULL
              AND EXISTS (
                SELECT 1 FROM knowledge_base kb
                WHERE kb.id = d.knowledge_base_id AND kb.status = 'ACTIVE'
              )
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
            ORDER BY (c.embedding::{{VT}}) <=> ?::{{VT}}
            LIMIT ?
            """;
        // The dimension is an int taken from the query vector, never from text.
        sql = sql.replace("{{VT}}", "vector(" + embedding.length + ")")
            .replace("{{DIM}}", Integer.toString(embedding.length));
        List<Object> args = new ArrayList<>();
        args.add(vector);
        args.add(tenantId);
        if (knowledgeBaseId != null) {
            args.add(knowledgeBaseId);
        }
        args.add(embeddingModel);
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

    /**
     * Returns only the current user's visible searchable chunks for the gated
     * retrieval experiment. The ACL predicates intentionally mirror search().
     */
    public List<ChunkSearchResult> findVisibleReadyChunks(UUID tenantId, UUID userId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("visible chunk limit must be positive");
        }
        String sql = """
            SELECT c.id, c.document_id, d.filename, c.locator, c.content
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE d.tenant_id = ?
              AND c.embedding_model = ?
              AND d.deleted_at IS NULL
              AND d.disabled_at IS NULL
              AND EXISTS (
                SELECT 1 FROM knowledge_base kb
                WHERE kb.id = d.knowledge_base_id AND kb.status = 'ACTIVE'
              )
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
            ORDER BY d.filename, c.seq, c.id
            LIMIT ?
            """;
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new ChunkSearchResult(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("filename"),
                rs.getString("locator"),
                rs.getString("content"),
                0.0
            ),
            tenantId,
            embeddingModel,
            userId,
            userId,
            tenantId,
            tenantId,
            userId,
            userId,
            tenantId,
            userId,
            tenantId,
            userId,
            userId,
            tenantId,
            userId,
            limit
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
