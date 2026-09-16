package com.example.ragknowledgebase.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.document.DocumentRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresEnterpriseIntegrationTests {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private static final UUID READER_ID = UUID.fromString("10000000-0000-0000-0000-000000000102");
    private static final UUID OUTSIDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000103");
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000101");

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("rag_integration")
        .withUsername("rag")
        .withPassword("rag");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private AccessControlService accessControlService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update(
            "DELETE FROM knowledge_base_membership WHERE principal_id IN (?, ?, ?)",
            OWNER_ID,
            READER_ID,
            OUTSIDER_ID
        );
        jdbcTemplate.update("DELETE FROM document_acl WHERE document_id = ?", DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id = ?", DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?)", OWNER_ID, READER_ID, OUTSIDER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", OWNER_ID, READER_ID, OUTSIDER_ID);
    }

    @Test
    void flywayCreatesPgvectorSchemaAndEnterpriseDefaults() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true",
            Integer.class
        );
        String embeddingType = jdbcTemplate.queryForObject(
            """
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname = 'chunk'
                  AND a.attname = 'embedding'
                """,
            String.class
        );
        Integer roleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM app_role", Integer.class);
        Integer knowledgeBaseCount = jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_base", Integer.class);
        Integer defaultAccessCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM user_role ur JOIN app_role r ON r.id = ur.role_id WHERE r.code = 'SYSTEM_ADMIN'",
            Integer.class
        );

        assertThat(successfulMigrations).isEqualTo(2);
        assertThat(embeddingType).isEqualTo("vector(768)");
        assertThat(roleCount).isEqualTo(4);
        assertThat(knowledgeBaseCount).isEqualTo(1);
        assertThat(defaultAccessCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aclControlsDocumentVisibilityAndManagePermissionInPostgres() {
        insertUser(OWNER_ID, "owner-it");
        insertUser(READER_ID, "reader-it");
        insertUser(OUTSIDER_ID, "outsider-it");
        insertDocument();

        AuthenticatedUser reader = new AuthenticatedUser(READER_ID, TENANT_ID, "reader-it");
        AuthenticatedUser outsider = new AuthenticatedUser(OUTSIDER_ID, TENANT_ID, "outsider-it");

        assertThat(documentRepository.findAccessible(TENANT_ID, READER_ID)).isEmpty();
        assertThat(documentRepository.findAccessible(TENANT_ID, OUTSIDER_ID)).isEmpty();
        assertThat(accessControlService.canManageDocument(reader, DOCUMENT_ID)).isFalse();

        grantKnowledgeBase("READ", READER_ID);

        assertThat(documentRepository.findAccessible(TENANT_ID, READER_ID))
            .extracting("id")
            .containsExactly(DOCUMENT_ID);
        assertThat(documentRepository.findAccessibleById(DOCUMENT_ID, TENANT_ID, READER_ID)).isPresent();
        assertThat(accessControlService.canManageDocument(reader, DOCUMENT_ID)).isFalse();
        assertThat(documentRepository.findAccessible(TENANT_ID, OUTSIDER_ID)).isEmpty();

        grantKnowledgeBase("MANAGE", READER_ID);

        assertThat(accessControlService.canManageDocument(reader, DOCUMENT_ID)).isTrue();
        assertThat(accessControlService.canManageKnowledgeBase(reader, KNOWLEDGE_BASE_ID)).isTrue();
        assertThat(accessControlService.canManageKnowledgeBase(outsider, KNOWLEDGE_BASE_ID)).isFalse();
    }

    private void insertUser(UUID id, String username) {
        jdbcTemplate.update(
            """
                INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                VALUES (?, ?, ?, 'not-used-in-test', 'ACTIVE')
                """,
            id,
            TENANT_ID,
            username
        );
    }

    private void insertDocument() {
        jdbcTemplate.update(
            """
                INSERT INTO document (
                    id, tenant_id, knowledge_base_id, user_id, filename, file_type, file_path, status
                )
                VALUES (?, ?, ?, ?, 'acl.md', 'md', '/tmp/acl.md', 'READY')
                """,
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            OWNER_ID
        );
    }

    private void grantKnowledgeBase(String permission, UUID userId) {
        jdbcTemplate.update(
            """
                INSERT INTO knowledge_base_membership (
                    id, tenant_id, knowledge_base_id, principal_type, principal_id, permission
                )
                VALUES (?, ?, ?, 'USER', ?, ?)
                """,
            UUID.randomUUID(),
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            userId,
            permission
        );
    }
}
