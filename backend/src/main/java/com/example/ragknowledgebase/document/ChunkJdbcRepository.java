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

    public List<ChunkSearchResult> search(UUID userId, float[] embedding, int topK) {
        String vector = vectorLiteral(embedding);
        String sql = """
            SELECT c.id, c.document_id, d.filename, c.locator, c.content,
                   (1 - (c.embedding <=> ?::vector)) AS similarity
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE d.user_id = ? AND d.status = 'READY'
            ORDER BY c.embedding <=> ?::vector
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
                rs.getDouble("similarity")
            ),
            vector,
            userId,
            vector,
            topK
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
