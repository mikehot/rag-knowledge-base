package com.example.ragknowledgebase.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.admin.AdminService;
import com.example.ragknowledgebase.admin.AssignUserRoleRequest;
import com.example.ragknowledgebase.admin.CreateDepartmentRequest;
import com.example.ragknowledgebase.admin.CreateKnowledgeBaseRequest;
import com.example.ragknowledgebase.admin.CreateUserRequest;
import com.example.ragknowledgebase.admin.GrantKnowledgeBaseMembershipRequest;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.document.DocumentRepository;
import com.example.ragknowledgebase.document.DocumentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
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
    private static final UUID CREATED_USER_ID_MARKER = UUID.fromString("10000000-0000-0000-0000-000000000104");
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
    private DocumentService documentService;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private AdminService adminService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM audit_event WHERE user_id IN (?, ?, ?)", OWNER_ID, READER_ID, OUTSIDER_ID);
        jdbcTemplate.update(
            "DELETE FROM knowledge_base_membership WHERE principal_id IN (?, ?, ?)",
            OWNER_ID,
            READER_ID,
            OUTSIDER_ID
        );
        jdbcTemplate.update("DELETE FROM document_acl WHERE document_id = ?", DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id = ?", DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", DOCUMENT_ID);
        try {
            Files.deleteIfExists(Path.of("uploads", "lifecycle.md"));
        } catch (Exception ignored) {
        }
        jdbcTemplate.update("DELETE FROM knowledge_base_membership WHERE knowledge_base_id IN (SELECT id FROM knowledge_base WHERE code LIKE 'managed-%')");
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE code LIKE 'managed-%'");
        jdbcTemplate.update("DELETE FROM knowledge_base_membership WHERE principal_id IN (SELECT id FROM app_user WHERE username LIKE 'managed-%')");
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'managed-%')");
        jdbcTemplate.update("DELETE FROM app_user WHERE username LIKE 'managed-%'");
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?)", OWNER_ID, READER_ID, OUTSIDER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", OWNER_ID, READER_ID, OUTSIDER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", CREATED_USER_ID_MARKER);
        jdbcTemplate.update("DELETE FROM department WHERE code LIKE 'MANAGED-%'");
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

        assertThat(successfulMigrations).isEqualTo(4);
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
        assertThatThrownBy(() -> documentService.delete(reader, DOCUMENT_ID))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(404);
        assertThat(countDeniedAudit(READER_ID, "DOCUMENT_DELETE", "DOCUMENT", DOCUMENT_ID))
            .isEqualTo(1);
        assertThat(documentRepository.findAccessible(TENANT_ID, OUTSIDER_ID)).isEmpty();

        grantKnowledgeBase("MANAGE", READER_ID);

        assertThat(accessControlService.canManageDocument(reader, DOCUMENT_ID)).isTrue();
        assertThat(accessControlService.canManageKnowledgeBase(reader, KNOWLEDGE_BASE_ID)).isTrue();
        assertThat(accessControlService.canManageKnowledgeBase(outsider, KNOWLEDGE_BASE_ID)).isFalse();
    }

    @Test
    void adminApiServiceCreatesUsersAndManagesKnowledgeBaseMemberships() {
        insertUser(OWNER_ID, "owner-it");
        assignRole(OWNER_ID, "SYSTEM_ADMIN");
        AuthenticatedUser admin = new AuthenticatedUser(OWNER_ID, TENANT_ID, "owner-it");

        var created = adminService.createUser(
            admin,
            new CreateUserRequest(
                "managed-employee",
                "password123",
                "Managed Employee",
                null,
                null
            )
        );

        assertThat(created.username()).isEqualTo("managed-employee");
        assertThat(created.roleCodes()).containsExactly("EMPLOYEE");
        assertThat(adminService.listUsers(admin))
            .extracting("username")
            .contains("managed-employee", "owner-it");
        assertThat(adminService.listRoles(admin))
            .extracting("code")
            .contains("SYSTEM_ADMIN", "EMPLOYEE", "AUDITOR", "KNOWLEDGE_ADMIN");

        var membership = adminService.grantMembership(
            admin,
            KNOWLEDGE_BASE_ID,
            new GrantKnowledgeBaseMembershipRequest("user", created.id(), "read")
        );

        assertThat(membership.principalId()).isEqualTo(created.id());
        assertThat(membership.permission()).isEqualTo("READ");
        assertThat(adminService.listMemberships(admin, KNOWLEDGE_BASE_ID))
            .extracting("id")
            .contains(membership.id());

        assertThat(adminService.revokeMembership(admin, KNOWLEDGE_BASE_ID, membership.id()).deleted()).isTrue();
        assertThat(adminService.listMemberships(admin, KNOWLEDGE_BASE_ID))
            .extracting("id")
            .doesNotContain(membership.id());
    }

    @Test
    void nonSystemAdminCannotCreateUsersButKnowledgeBaseManagerCanGrantReadAccess() {
        insertUser(OWNER_ID, "manager-it");
        insertUser(READER_ID, "reader-it");
        grantKnowledgeBase("MANAGE", OWNER_ID);
        AuthenticatedUser manager = new AuthenticatedUser(OWNER_ID, TENANT_ID, "manager-it");

        assertThatThrownBy(() -> adminService.createUser(
            manager,
            new CreateUserRequest("managed-denied", "password123", null, null, null)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(403);
        assertThat(countDeniedAudit(OWNER_ID, "USER_CREATE", "TENANT", TENANT_ID))
            .isEqualTo(1);

        var membership = adminService.grantMembership(
            manager,
            KNOWLEDGE_BASE_ID,
            new GrantKnowledgeBaseMembershipRequest("USER", READER_ID, "READ")
        );

        assertThat(membership.permission()).isEqualTo("READ");
        assertThat(accessControlService.canManageKnowledgeBase(manager, KNOWLEDGE_BASE_ID)).isTrue();
        assertThat(adminService.listMemberships(manager, KNOWLEDGE_BASE_ID))
            .extracting("id")
            .contains(membership.id());
    }

    @Test
    void systemAdminManagesDepartmentsRolesAndKnowledgeBaseLifecycle() {
        insertUser(OWNER_ID, "admin-it");
        assignRole(OWNER_ID, "SYSTEM_ADMIN");
        AuthenticatedUser admin = new AuthenticatedUser(OWNER_ID, TENANT_ID, "admin-it");

        var department = adminService.createDepartment(
            admin,
            new CreateDepartmentRequest("managed-sales", "Managed Sales", null)
        );
        assertThat(department.code()).isEqualTo("MANAGED-SALES");
        assertThat(adminService.listDepartments(admin))
            .extracting("id")
            .contains(department.id());

        var employee = adminService.createUser(
            admin,
            new CreateUserRequest(
                "managed-role-user",
                "password123",
                "Managed Role User",
                department.id(),
                List.of("EMPLOYEE")
            )
        );

        var knowledgeAdmin = adminService.assignUserRole(
            admin,
            employee.id(),
            new AssignUserRoleRequest("knowledge_admin")
        );
        assertThat(knowledgeAdmin.roleCodes()).contains("EMPLOYEE", "KNOWLEDGE_ADMIN");

        assertThat(adminService.revokeUserRole(admin, employee.id(), "KNOWLEDGE_ADMIN").deleted()).isTrue();
        assertThat(adminService.listUsers(admin).stream()
            .filter(user -> user.id().equals(employee.id()))
            .findFirst()
            .orElseThrow()
            .roleCodes()
        ).doesNotContain("KNOWLEDGE_ADMIN");

        var knowledgeBase = adminService.createKnowledgeBase(
            admin,
            new CreateKnowledgeBaseRequest(
                "managed-kb",
                "Managed KB",
                "Managed lifecycle test knowledge base"
            )
        );
        assertThat(knowledgeBase.code()).isEqualTo("managed-kb");
        assertThat(knowledgeBase.status()).isEqualTo("ACTIVE");
        assertThat(accessControlService.canManageKnowledgeBase(admin, knowledgeBase.id())).isTrue();

        var disabled = adminService.updateKnowledgeBaseStatus(admin, knowledgeBase.id(), "DISABLED");

        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(accessControlService.canManageKnowledgeBase(admin, knowledgeBase.id())).isFalse();

        var activated = adminService.updateKnowledgeBaseStatus(admin, knowledgeBase.id(), "ACTIVE");

        assertThat(activated.status()).isEqualTo("ACTIVE");
        assertThat(accessControlService.canManageKnowledgeBase(admin, knowledgeBase.id())).isTrue();
    }

    @Test
    @Transactional
    void documentLifecycleHidesReindexesAndSoftDeletesDocuments() throws Exception {
        insertUser(OWNER_ID, "document-admin-it");
        assignRole(OWNER_ID, "SYSTEM_ADMIN");
        AuthenticatedUser admin = new AuthenticatedUser(OWNER_ID, TENANT_ID, "document-admin-it");
        Path rawFile = Path.of("uploads", "lifecycle.md").toAbsolutePath().normalize();
        Files.createDirectories(rawFile.getParent());
        Files.writeString(rawFile, "Lifecycle content");
        insertDocument(rawFile.toString());
        insertChunk();

        var disabled = documentService.disable(admin, DOCUMENT_ID);

        assertThat(disabled.disabled()).isTrue();
        assertThat(disabled.permissionVersion()).isEqualTo(2);
        assertThat(documentRepository.findAccessible(TENANT_ID, OWNER_ID)).isEmpty();
        assertThatThrownBy(() -> documentService.reindex(admin, DOCUMENT_ID))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(409);

        var enabled = documentService.enable(admin, DOCUMENT_ID);

        assertThat(enabled.disabled()).isFalse();
        assertThat(enabled.permissionVersion()).isEqualTo(3);
        assertThat(documentRepository.findAccessible(TENANT_ID, OWNER_ID))
            .extracting("id")
            .containsExactly(DOCUMENT_ID);

        var reindex = documentService.reindex(admin, DOCUMENT_ID);

        assertThat(reindex.status()).isEqualTo("processing");
        assertThat(reindex.contentVersion()).isEqualTo(2);
        assertThat(countChunks(DOCUMENT_ID)).isZero();

        insertChunk();

        assertThat(documentService.delete(admin, DOCUMENT_ID).deleted()).isTrue();
        assertThat(countChunks(DOCUMENT_ID)).isZero();
        assertThat(Files.exists(rawFile)).isFalse();
        assertThat(documentRepository.findAccessible(TENANT_ID, OWNER_ID)).isEmpty();
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
        insertDocument("/tmp/acl.md");
    }

    private void insertDocument(String filePath) {
        jdbcTemplate.update(
            """
                INSERT INTO document (
                    id, tenant_id, knowledge_base_id, user_id, filename, file_type, file_path,
                    checksum, status
                )
                VALUES (?, ?, ?, ?, 'acl.md', 'md', ?, ?, 'READY')
                """,
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            OWNER_ID,
            filePath,
            "integration-checksum-" + DOCUMENT_ID
        );
    }

    private void insertChunk() {
        jdbcTemplate.update(
            """
                INSERT INTO chunk (id, document_id, seq, locator, content, embedding)
                VALUES (?, ?, 1, 'test', 'Lifecycle content', ?::vector)
                """,
            UUID.randomUUID(),
            DOCUMENT_ID,
            zeroVector()
        );
    }

    private Integer countChunks(UUID documentId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
    }

    private Integer countDeniedAudit(UUID userId, String action, String resourceType, UUID resourceId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM audit_event
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND action = ?
                  AND resource_type = ?
                  AND resource_id = ?
                  AND outcome = 'DENY'
                """,
            Integer.class,
            TENANT_ID,
            userId,
            action,
            resourceType,
            resourceId
        );
    }

    private String zeroVector() {
        return "[" + "0,".repeat(767) + "0]";
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

    private void assignRole(UUID userId, String roleCode) {
        jdbcTemplate.update(
            """
                INSERT INTO user_role (user_id, role_id)
                SELECT ?, id
                FROM app_role
                WHERE tenant_id = ?
                  AND code = ?
                ON CONFLICT DO NOTHING
                """,
            userId,
            TENANT_ID,
            roleCode
        );
    }
}
