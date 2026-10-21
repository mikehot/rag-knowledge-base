package com.example.ragknowledgebase.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.ai.AiProvider;
import com.example.ragknowledgebase.ai.EmbeddingProvider;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.document.ChunkJdbcRepository;
import com.example.ragknowledgebase.document.ChunkRecord;
import com.example.ragknowledgebase.document.ChunkSearchResult;
import com.example.ragknowledgebase.document.EmbeddingStatusResponse;
import com.example.ragknowledgebase.document.EmbeddingStatusService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EmbeddingModelIsolationIntegrationTests {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-0000000000e1");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-0000000000e2");
    private static final UUID PLAIN_USER_ID = UUID.fromString("10000000-0000-0000-0000-0000000000e3");
    private static final UUID DOC_CURRENT = UUID.fromString("20000000-0000-0000-0000-0000000000e1");
    private static final UUID DOC_OTHER_MODEL = UUID.fromString("20000000-0000-0000-0000-0000000000e2");
    private static final String OTHER_MODEL = "text-embedding-bge-m3";  // differs from the pinned fixture model

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("rag_embedding_isolation")
        .withUsername("rag")
        .withPassword("rag");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChunkJdbcRepository chunkRepository;

    @Autowired
    private EmbeddingStatusService embeddingStatusService;

    @MockitoBean
    private AiProvider aiProvider;

    @MockitoBean
    private EmbeddingProvider embeddingProvider;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // These tests build 768-dimension fixtures; pin the fixture model so they do not follow the shipped default.
        registry.add("app.embedding.model-id", () -> "text-embedding-nomic-embed-text-v1.5");
        registry.add("spring.flyway.placeholders.embedding_model", () -> "text-embedding-nomic-embed-text-v1.5");
        registry.add("app.indexing.enabled", () -> "false");
        registry.add("app.auth.jwt-secret", () -> "integration-only-jwt-secret-with-at-least-32-bytes");
        registry.add("app.auth.default-password", () -> "integration-password-123");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id IN (?, ?)", DOC_CURRENT, DOC_OTHER_MODEL);
        jdbcTemplate.update("DELETE FROM document WHERE id IN (?, ?)", DOC_CURRENT, DOC_OTHER_MODEL);
        jdbcTemplate.update("DELETE FROM audit_event WHERE user_id IN (?, ?, ?)", OWNER_ID, ADMIN_ID, PLAIN_USER_ID);
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id IN (?, ?)", ADMIN_ID, PLAIN_USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", OWNER_ID, ADMIN_ID, PLAIN_USER_ID);
        insertUser(OWNER_ID, "embedding-owner");
        insertUser(ADMIN_ID, "embedding-admin");
        insertUser(PLAIN_USER_ID, "embedding-plain");
        UUID adminRole = jdbcTemplate.queryForObject(
            "SELECT id FROM app_role WHERE tenant_id = ? AND code = 'SYSTEM_ADMIN'", UUID.class, TENANT_ID
        );
        jdbcTemplate.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", ADMIN_ID, adminRole);
        insertDocument(DOC_CURRENT, "current.md");
        insertDocument(DOC_OTHER_MODEL, "other.md");
    }

    @Test
    void migrationAddsGeneratedDimensionAndAttributesLegacyInsertsToTheConfiguredModel() {
        jdbcTemplate.update(
            "INSERT INTO chunk (id, document_id, seq, locator, content, embedding) VALUES (?, ?, 1, 'c1', 'legacy', ?::vector)",
            UUID.randomUUID(), DOC_CURRENT, literal(768, 0)
        );

        var row = jdbcTemplate.queryForMap(
            "SELECT embedding_model, embedding_dim FROM chunk WHERE document_id = ?", DOC_CURRENT
        );

        assertThat(row.get("embedding_model")).isEqualTo(chunkRepository.embeddingModel());
        assertThat(((Number) row.get("embedding_dim")).intValue()).isEqualTo(768);
    }

    @Test
    void insertAllRecordsTheConfiguredModelAndTheVectorDimension() {
        chunkRepository.insertAll(List.of(record(DOC_CURRENT, 1, "alpha", vector(1024, 0))));

        var row = jdbcTemplate.queryForMap(
            "SELECT embedding_model, embedding_dim FROM chunk WHERE document_id = ?", DOC_CURRENT
        );

        assertThat(row.get("embedding_model")).isEqualTo(chunkRepository.embeddingModel());
        assertThat(((Number) row.get("embedding_dim")).intValue()).isEqualTo(1024);
    }

    @Test
    void searchComparesOnlyVectorsFromTheConfiguredModel() {
        chunkRepository.insertAll(List.of(
            record(DOC_CURRENT, 1, "current-best", vector(768, 0)),
            record(DOC_CURRENT, 2, "current-second", vector(768, 1))
        ));
        // Same dimension and an exact match for the query, but produced by another model.
        insertWithModel(DOC_OTHER_MODEL, 1, "foreign-exact-match", vector(768, 0), OTHER_MODEL);

        List<ChunkSearchResult> hits = chunkRepository.search(TENANT_ID, OWNER_ID, vector(768, 0), 5);

        assertThat(hits).extracting(ChunkSearchResult::content)
            .containsExactly("current-best", "current-second");
    }

    @Test
    void vectorsOfDifferentDimensionsCoexistWithoutErrors() {
        chunkRepository.insertAll(List.of(record(DOC_CURRENT, 1, "d768", vector(768, 0))));
        insertWithModel(DOC_OTHER_MODEL, 1, "d1024", vector(1024, 0), OTHER_MODEL);

        List<ChunkSearchResult> smallQuery = chunkRepository.search(TENANT_ID, OWNER_ID, vector(768, 0), 5);
        List<ChunkSearchResult> largeQuery = chunkRepository.search(TENANT_ID, OWNER_ID, vector(1024, 0), 5);

        assertThat(smallQuery).extracting(ChunkSearchResult::content).containsExactly("d768");
        // The configured model has no 1024-dimension vectors; the foreign ones must not be compared.
        assertThat(largeQuery).isEmpty();
    }

    @Test
    void searchRejectsUnsupportedDimensions() {
        // Spring's repository exception translation wraps the IllegalArgumentException.
        assertThatThrownBy(() -> chunkRepository.search(TENANT_ID, OWNER_ID, new float[0], 5))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("维度不受支持");
        assertThatThrownBy(() -> chunkRepository.search(TENANT_ID, OWNER_ID, new float[2001], 5))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("维度不受支持");
    }

    @Test
    void partialHnswIndexesMatchTheExpressionAndPredicateUsedBySearch() {
        List<String> plan = jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                List<String> lines = new ArrayList<>();
                for (int dim : new int[] {768, 1024}) {
                    try (var rs = statement.executeQuery(
                        "EXPLAIN SELECT c.id FROM chunk c WHERE c.embedding_dim = " + dim
                            + " ORDER BY (c.embedding::vector(" + dim + ")) <=> '" + literal(dim, 0)
                            + "'::vector(" + dim + ") LIMIT 5"
                    )) {
                        StringBuilder text = new StringBuilder();
                        while (rs.next()) {
                            text.append(rs.getString(1)).append('\n');
                        }
                        lines.add(text.toString());
                    }
                }
                statement.execute("RESET enable_seqscan");
                return lines;
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });

        assertThat(plan.get(0)).contains("idx_chunk_embedding_768");
        assertThat(plan.get(1)).contains("idx_chunk_embedding_1024");
    }

    @Test
    void statusReportsModelsAndRebuildProgress() {
        chunkRepository.insertAll(List.of(record(DOC_CURRENT, 1, "current", vector(768, 0))));
        insertWithModel(DOC_OTHER_MODEL, 1, "foreign", vector(1024, 0), OTHER_MODEL);
        AuthenticatedUser admin = new AuthenticatedUser(ADMIN_ID, TENANT_ID, "embedding-admin");

        EmbeddingStatusResponse before = embeddingStatusService.get(admin);

        assertThat(before.configuredModel()).isEqualTo(chunkRepository.embeddingModel());
        assertThat(before.models()).extracting(EmbeddingStatusResponse.ModelUsage::model)
            .contains(chunkRepository.embeddingModel(), OTHER_MODEL);
        assertThat(before.models()).filteredOn(EmbeddingStatusResponse.ModelUsage::configured)
            .extracting(EmbeddingStatusResponse.ModelUsage::model)
            .containsExactly(chunkRepository.embeddingModel());
        assertThat(before.indexedDocuments()).isEqualTo(2);
        assertThat(before.documentsOnConfiguredModel()).isEqualTo(1);
        assertThat(before.documentsWaitingForRebuild()).isEqualTo(1);
        assertThat(before.rebuildComplete()).isFalse();

        // Rebuilding the document with the configured model completes the switch.
        chunkRepository.deleteByDocumentId(DOC_OTHER_MODEL);
        chunkRepository.insertAll(List.of(record(DOC_OTHER_MODEL, 1, "rebuilt", vector(768, 0))));

        EmbeddingStatusResponse after = embeddingStatusService.get(admin);

        assertThat(after.documentsWaitingForRebuild()).isZero();
        assertThat(after.rebuildComplete()).isTrue();
    }

    @Test
    void statusRequiresAnAuditReader() {
        AuthenticatedUser plain = new AuthenticatedUser(PLAIN_USER_ID, TENANT_ID, "embedding-plain");

        assertThatThrownBy(() -> embeddingStatusService.get(plain))
            .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(403));
    }

    private void insertUser(UUID id, String username) {
        jdbcTemplate.update(
            "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES (?, ?, ?, 'not-used', 'ACTIVE')",
            id, TENANT_ID, username
        );
    }

    private void insertDocument(UUID id, String filename) {
        jdbcTemplate.update(
            """
                INSERT INTO document (id, tenant_id, knowledge_base_id, user_id, filename, file_type, file_path,
                                      checksum, status)
                VALUES (?, ?, ?, ?, ?, 'md', ?, ?, 'READY')
                """,
            id, TENANT_ID, KNOWLEDGE_BASE_ID, OWNER_ID, filename, "/tmp/" + filename, "embedding-" + id
        );
    }

    private void insertWithModel(UUID documentId, int seq, String content, float[] vector, String model) {
        jdbcTemplate.update(
            """
                INSERT INTO chunk (id, document_id, seq, locator, content, embedding, embedding_model)
                VALUES (?, ?, ?, ?, ?, ?::vector, ?)
                """,
            UUID.randomUUID(), documentId, seq, "chunk#" + seq, content, literal(vector), model
        );
    }

    private ChunkRecord record(UUID documentId, int seq, String content, float[] vector) {
        return new ChunkRecord(UUID.randomUUID(), documentId, seq, "chunk#" + seq, content, vector);
    }

    private float[] vector(int dimensions, int hotIndex) {
        float[] values = new float[dimensions];
        values[hotIndex] = 1f;
        return values;
    }

    private String literal(int dimensions, int hotIndex) {
        return literal(vector(dimensions, hotIndex));
    }

    private String literal(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.append(']').toString();
    }
}
