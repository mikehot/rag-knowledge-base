package com.example.ragknowledgebase.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Upgrades a database that already holds data at schema V14 (vector(768), one HNSW index) to V15
 * and checks that vectors survive, rows are attributed to the configured model, and the old index
 * is replaced by the per-dimension partial indexes.
 */
@Testcontainers(disabledWithoutDocker = true)
class EmbeddingModelMigrationTests {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("rag_migration")
        .withUsername("rag")
        .withPassword("rag");

    @Test
    void upgradeKeepsVectorsAndAttributesThemToTheConfiguredModel() throws SQLException {
        Map<String, String> placeholders = Map.of("embedding_dim", "768", "embedding_model", "legacy-embedder");
        migrateTo("14", placeholders);

        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String vector = "[1," + "0,".repeat(766) + "0]";
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(
                "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES ('" + userId
                    + "', '" + TENANT_ID + "', 'migration-user', 'x', 'ACTIVE')"
            );
            statement.execute(
                "INSERT INTO document (id, tenant_id, knowledge_base_id, user_id, filename, file_type, file_path,"
                    + " checksum, status) VALUES ('" + documentId + "', '" + TENANT_ID + "', '" + KNOWLEDGE_BASE_ID
                    + "', '" + userId + "', 'old.md', 'md', '/tmp/old.md', 'migration-checksum', 'READY')"
            );
            statement.execute(
                "INSERT INTO chunk (id, document_id, seq, locator, content, embedding) VALUES ('"
                    + UUID.randomUUID() + "', '" + documentId + "', 1, 'chunk#1', 'before upgrade', '" + vector
                    + "'::vector)"
            );
        }

        migrateTo(null, placeholders);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                "SELECT embedding_model, embedding_dim, embedding::text AS v FROM chunk WHERE content = 'before upgrade'"
            )) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("embedding_model")).isEqualTo("legacy-embedder");
                assertThat(rs.getInt("embedding_dim")).isEqualTo(768);
                assertThat(rs.getString("v")).isEqualTo(vector);
            }
            try (ResultSet rs = statement.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'chunk' AND indexname LIKE 'idx_chunk_embedding%'"
            )) {
                java.util.Set<String> names = new java.util.HashSet<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertThat(names).contains("idx_chunk_embedding_768", "idx_chunk_embedding_1024", "idx_chunk_embedding_model");
                assertThat(names).doesNotContain("idx_chunk_embedding");
            }
            // A 1024-d vector can now be stored next to the migrated 768-d one.
            statement.execute(
                "INSERT INTO chunk (id, document_id, seq, locator, content, embedding, embedding_model) VALUES ('"
                    + UUID.randomUUID() + "', '" + documentId + "', 2, 'chunk#2', 'after upgrade', '[1,"
                    + "0,".repeat(1022) + "0]'::vector, 'text-embedding-bge-m3')"
            );
            try (ResultSet rs = statement.executeQuery(
                "SELECT count(*) FROM chunk WHERE embedding_dim = 1024 AND embedding_model = 'text-embedding-bge-m3'"
            )) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private void migrateTo(String version, Map<String, String> placeholders) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(placeholders);
        if (version != null) {
            configuration.target(version);
        }
        configuration.load().migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
