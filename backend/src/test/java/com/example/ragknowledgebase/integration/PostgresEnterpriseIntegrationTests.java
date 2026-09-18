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
import com.example.ragknowledgebase.audit.AuditQueryService;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.document.DocumentRepository;
import com.example.ragknowledgebase.document.DocumentService;
import com.example.ragknowledgebase.indexing.IndexTaskRepository;
import com.example.ragknowledgebase.indexing.IndexTaskService;
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
import org.springframework.mock.web.MockMultipartFile;
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
    private static final UUID SALES_DEPARTMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000101");
    private static final UUID FINANCE_DEPARTMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000102");
    private static final UUID EMPLOYEE_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final UUID SECOND_TENANT_ID = UUID.fromString("40000000-0000-0000-0000-000000000101");
    private static final UUID SECOND_USER_ID = UUID.fromString("40000000-0000-0000-0000-000000000102");
    private static final UUID SECOND_KNOWLEDGE_BASE_ID = UUID.fromString("40000000-0000-0000-0000-000000000103");
    private static final UUID SECOND_DOCUMENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000104");

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
    private IndexTaskRepository indexTaskRepository;

    @Autowired
    private IndexTaskService indexTaskService;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private AdminService adminService;

    @Autowired
    private AuditQueryService auditQueryService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.indexing.enabled", () -> "false");
    }

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM audit_event WHERE user_id IN (?, ?, ?, ?)", OWNER_ID, READER_ID, OUTSIDER_ID, SECOND_USER_ID);
        jdbcTemplate.update(
            "DELETE FROM knowledge_base_membership WHERE principal_id IN (?, ?, ?, ?, ?)",
            OWNER_ID,
            READER_ID,
            OUTSIDER_ID,
            SALES_DEPARTMENT_ID,
            FINANCE_DEPARTMENT_ID
        );
        jdbcTemplate.update("DELETE FROM document_acl WHERE document_id IN (?, ?)", DOCUMENT_ID, SECOND_DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id IN (?, ?)", DOCUMENT_ID, SECOND_DOCUMENT_ID);
        jdbcTemplate.update("DELETE FROM document WHERE id IN (?, ?)", DOCUMENT_ID, SECOND_DOCUMENT_ID);
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
        jdbcTemplate.update("DELETE FROM knowledge_base_membership WHERE knowledge_base_id = ?", SECOND_KNOWLEDGE_BASE_ID);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", SECOND_KNOWLEDGE_BASE_ID);
        jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", SECOND_USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", SECOND_USER_ID);
        jdbcTemplate.update("DELETE FROM app_role WHERE tenant_id = ?", SECOND_TENANT_ID);
        jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", SECOND_TENANT_ID);
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
        Integer askObservabilityColumnCount = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'ask_log'
                  AND column_name IN (
                    'tenant_id', 'request_id', 'result_status', 'failure_reason', 'latency_ms',
                    'embedding_latency_ms', 'retrieval_latency_ms', 'generation_latency_ms',
                    'model_id', 'provider', 'top_k', 'similarity_threshold'
                  )
                """,
            Integer.class
        );

        assertThat(successfulMigrations).isEqualTo(7);
        assertThat(embeddingType).isEqualTo("vector(768)");
        assertThat(roleCount).isEqualTo(4);
        assertThat(knowledgeBaseCount).isEqualTo(1);
        assertThat(defaultAccessCount).isGreaterThanOrEqualTo(1);
        assertThat(askObservabilityColumnCount).isEqualTo(12);
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
    void departmentAndRolePrincipalsScopeDocumentAccessInPostgres() {
        insertDepartment(SALES_DEPARTMENT_ID, "MANAGED-SALES-ACL");
        insertDepartment(FINANCE_DEPARTMENT_ID, "MANAGED-FINANCE-ACL");
        insertUser(OWNER_ID, "owner-it");
        insertUser(READER_ID, "sales-reader-it", TENANT_ID, SALES_DEPARTMENT_ID);
        insertUser(OUTSIDER_ID, "finance-outsider-it", TENANT_ID, FINANCE_DEPARTMENT_ID);
        insertDocument();

        grantKnowledgeBase("DEPARTMENT", SALES_DEPARTMENT_ID, "READ", TENANT_ID, KNOWLEDGE_BASE_ID);

        assertThat(documentRepository.findAccessible(TENANT_ID, READER_ID))
            .extracting("id")
            .containsExactly(DOCUMENT_ID);
        assertThat(documentRepository.findAccessible(TENANT_ID, OUTSIDER_ID)).isEmpty();

        assignRole(OUTSIDER_ID, "EMPLOYEE");
        grantDocumentAcl("ROLE", EMPLOYEE_ROLE_ID, "MANAGE", TENANT_ID, DOCUMENT_ID);
        AuthenticatedUser roleManager = new AuthenticatedUser(OUTSIDER_ID, TENANT_ID, "finance-outsider-it");

        assertThat(documentRepository.findAccessible(TENANT_ID, OUTSIDER_ID))
            .extracting("id")
            .containsExactly(DOCUMENT_ID);
        assertThat(accessControlService.canManageDocument(roleManager, DOCUMENT_ID)).isTrue();
    }

    @Test
    void tenantBoundaryPreventsCrossTenantDocumentVisibilityInPostgres() {
        insertUser(OWNER_ID, "default-tenant-user");
        insertDocument();
        insertSecondTenantGraph();

        assertThat(documentRepository.findAccessible(TENANT_ID, OWNER_ID))
            .extracting("id")
            .containsExactly(DOCUMENT_ID);
        assertThat(documentRepository.findAccessible(TENANT_ID, SECOND_USER_ID)).isEmpty();
        assertThat(documentRepository.findAccessible(SECOND_TENANT_ID, SECOND_USER_ID))
            .extracting("id")
            .containsExactly(SECOND_DOCUMENT_ID);
        assertThat(documentRepository.findAccessible(SECOND_TENANT_ID, OWNER_ID)).isEmpty();
        assertThat(documentRepository.findAccessibleById(SECOND_DOCUMENT_ID, TENANT_ID, OWNER_ID)).isEmpty();
        assertThat(documentRepository.findAccessibleById(DOCUMENT_ID, SECOND_TENANT_ID, SECOND_USER_ID)).isEmpty();
    }

    @Test
    void auditReadersFilterTenantEventsWhileEmployeesAreDenied() {
        insertUser(OWNER_ID, "audit-admin-it");
        insertUser(READER_ID, "auditor-it");
        insertUser(OUTSIDER_ID, "employee-it");
        assignRole(OWNER_ID, "SYSTEM_ADMIN");
        assignRole(READER_ID, "AUDITOR");
        assignRole(OUTSIDER_ID, "EMPLOYEE");
        insertSecondTenantGraph();
        var older = java.time.Instant.parse("2026-09-17T00:00:00Z");
        var newer = java.time.Instant.parse("2026-09-17T00:01:00Z");
        insertAuditEvent(TENANT_ID, OWNER_ID, "DOCUMENT_DELETE", "DOCUMENT", DOCUMENT_ID, "DENY", older);
        insertAuditEvent(TENANT_ID, READER_ID, "USER_CREATE", "TENANT", TENANT_ID, "DENY", newer);
        insertAuditEvent(
            SECOND_TENANT_ID,
            SECOND_USER_ID,
            "DOCUMENT_DELETE",
            "DOCUMENT",
            SECOND_DOCUMENT_ID,
            "DENY",
            newer
        );

        AuthenticatedUser auditor = new AuthenticatedUser(READER_ID, TENANT_ID, "auditor-it");
        var filtered = auditQueryService.list(
            auditor,
            OWNER_ID,
            "document_delete",
            "document",
            DOCUMENT_ID,
            "deny",
            older.minusSeconds(1),
            older.plusSeconds(1),
            20
        );

        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).id()).isNotNull();
        assertThat(filtered.items().get(0).userId()).isEqualTo(OWNER_ID);
        assertThat(filtered.items().get(0).resourceId()).isEqualTo(DOCUMENT_ID);
        assertThat(filtered.hasMore()).isFalse();

        AuthenticatedUser admin = new AuthenticatedUser(OWNER_ID, TENANT_ID, "audit-admin-it");
        var firstPage = auditQueryService.list(admin, null, null, null, null, "DENY", null, null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().get(0).userId()).isEqualTo(READER_ID);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.items()).noneMatch(event -> SECOND_USER_ID.equals(event.userId()));

        AuthenticatedUser employee = new AuthenticatedUser(OUTSIDER_ID, TENANT_ID, "employee-it");
        assertThatThrownBy(() -> auditQueryService.list(
            employee,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            20
        ))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(403);
        assertThat(countDeniedAudit(OUTSIDER_ID, "AUDIT_EVENT_LIST", "TENANT", TENANT_ID)).isEqualTo(1);
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
        assertThat(reindex.taskId()).isNotNull();
        assertThat(countChunks(DOCUMENT_ID)).isEqualTo(1);

        assertThat(documentService.delete(admin, DOCUMENT_ID).deleted()).isTrue();
        assertThat(countChunks(DOCUMENT_ID)).isZero();
        assertThat(Files.exists(rawFile)).isFalse();
        assertThat(documentRepository.findAccessible(TENANT_ID, OWNER_ID)).isEmpty();
    }

    @Test
    @Transactional
    void replacementStagesNewVersionWithoutDroppingCurrentChunks() throws Exception {
        insertUser(OWNER_ID, "replacement-admin-it");
        assignRole(OWNER_ID, "SYSTEM_ADMIN");
        AuthenticatedUser admin = new AuthenticatedUser(OWNER_ID, TENANT_ID, "replacement-admin-it");
        Path oldFile = Path.of("uploads", "replace-old.md").toAbsolutePath().normalize();
        Path newFile = Path.of("uploads", DOCUMENT_ID + "-v2.md").toAbsolutePath().normalize();
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "Old searchable content");
        insertDocument(oldFile.toString());
        insertChunk();
        MockMultipartFile replacement = new MockMultipartFile(
            "file",
            "replacement.md",
            "text/markdown",
            "New replacement content".getBytes()
        );

        try {
            var response = documentService.replace(admin, DOCUMENT_ID, replacement);

            assertThat(response.status()).isEqualTo("processing");
            assertThat(response.contentVersion()).isEqualTo(2);
            assertThat(response.unchanged()).isFalse();
            assertThat(response.taskId()).isNotNull();
            assertThat(countChunks(DOCUMENT_ID)).isEqualTo(1);
            assertThat(Files.exists(oldFile)).isTrue();
            assertThat(Files.exists(newFile)).isTrue();
            documentRepository.flush();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT file_path FROM document WHERE id = ?",
                String.class,
                DOCUMENT_ID
            )).isEqualTo(newFile.toString());
        } finally {
            Files.deleteIfExists(newFile);
            Files.deleteIfExists(oldFile);
        }
    }

    @Test
    void persistentIndexTasksAreBatchCreatedIdempotentObservableAndRetryable() {
        insertUser(OWNER_ID, "index-admin-it");
        insertUser(READER_ID, "index-reader-it");
        assignRole(OWNER_ID, "SYSTEM_ADMIN");
        insertDocument();
        AuthenticatedUser admin = new AuthenticatedUser(OWNER_ID, TENANT_ID, "index-admin-it");
        AuthenticatedUser reader = new AuthenticatedUser(READER_ID, TENANT_ID, "index-reader-it");

        var batch = indexTaskService.batchReindex(admin, KNOWLEDGE_BASE_ID);

        assertThat(batch.taskCount()).isEqualTo(1);
        assertThat(batch.taskIds()).hasSize(1);
        UUID taskId = batch.taskIds().get(0);
        var pending = indexTaskService.get(admin, KNOWLEDGE_BASE_ID, taskId);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.operation()).isEqualTo("REINDEX");
        assertThat(pending.contentVersion()).isEqualTo(2);
        assertThat(pending.attemptCount()).isZero();
        assertThat(pending.createdAt()).isNotNull();

        var duplicate = indexTaskRepository.enqueue(
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            DOCUMENT_ID,
            OWNER_ID,
            "REINDEX",
            2,
            null
        );
        assertThat(duplicate.id()).isEqualTo(taskId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM index_task WHERE document_id = ? AND content_version = 2",
            Integer.class,
            DOCUMENT_ID
        )).isEqualTo(1);

        var claimed = indexTaskRepository.claimNext().orElseThrow();
        assertThat(claimed.id()).isEqualTo(taskId);
        assertThat(claimed.status()).isEqualTo("RUNNING");
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.startedAt()).isNotNull();

        indexTaskRepository.markFailed(taskId, "integration failure");
        var failed = indexTaskService.get(admin, KNOWLEDGE_BASE_ID, taskId);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorMessage()).isEqualTo("integration failure");
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(failed.durationMs()).isNotNull().isGreaterThanOrEqualTo(0);

        jdbcTemplate.update("UPDATE document SET status = 'FAILED', error_msg = 'integration failure' WHERE id = ?", DOCUMENT_ID);
        var retried = indexTaskService.retry(admin, KNOWLEDGE_BASE_ID, taskId);
        assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(retried.errorMessage()).isNull();
        assertThat(retried.maxAttempts()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM document WHERE id = ?",
            String.class,
            DOCUMENT_ID
        )).isEqualTo("PROCESSING");
        assertThat(indexTaskService.list(admin, KNOWLEDGE_BASE_ID, "pending", 20).items())
            .extracting("id")
            .containsExactly(taskId);

        assertThatThrownBy(() -> indexTaskService.list(reader, KNOWLEDGE_BASE_ID, null, 20))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(403);
        assertThat(countDeniedAudit(READER_ID, "INDEX_TASK_LIST", "KNOWLEDGE_BASE", KNOWLEDGE_BASE_ID))
            .isEqualTo(1);
    }

    private void insertUser(UUID id, String username) {
        insertUser(id, username, TENANT_ID, null);
    }

    private void insertUser(UUID id, String username, UUID tenantId, UUID departmentId) {
        jdbcTemplate.update(
            """
                INSERT INTO app_user (id, tenant_id, department_id, username, password_hash, status)
                VALUES (?, ?, ?, ?, 'not-used-in-test', 'ACTIVE')
                """,
            id,
            tenantId,
            departmentId,
            username
        );
    }

    private void insertDepartment(UUID id, String code) {
        jdbcTemplate.update(
            "INSERT INTO department (id, tenant_id, code, name) VALUES (?, ?, ?, ?)",
            id,
            TENANT_ID,
            code,
            code
        );
    }

    private void insertSecondTenantGraph() {
        jdbcTemplate.update(
            "INSERT INTO tenant (id, code, name) VALUES (?, 'integration-second', 'Integration Second Tenant')",
            SECOND_TENANT_ID
        );
        insertUser(SECOND_USER_ID, "second-tenant-user", SECOND_TENANT_ID, null);
        jdbcTemplate.update(
            """
                INSERT INTO knowledge_base (id, tenant_id, code, name, created_by)
                VALUES (?, ?, 'second-default', 'Second Default', ?)
                """,
            SECOND_KNOWLEDGE_BASE_ID,
            SECOND_TENANT_ID,
            SECOND_USER_ID
        );
        jdbcTemplate.update(
            """
                INSERT INTO document (
                    id, tenant_id, knowledge_base_id, user_id, filename, file_type, file_path,
                    checksum, status
                )
                VALUES (?, ?, ?, ?, 'second.md', 'md', '/tmp/second.md', ?, 'READY')
                """,
            SECOND_DOCUMENT_ID,
            SECOND_TENANT_ID,
            SECOND_KNOWLEDGE_BASE_ID,
            SECOND_USER_ID,
            "integration-checksum-" + SECOND_DOCUMENT_ID
        );
        grantKnowledgeBase("USER", SECOND_USER_ID, "READ", SECOND_TENANT_ID, SECOND_KNOWLEDGE_BASE_ID);
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

    private void insertAuditEvent(
        UUID tenantId,
        UUID userId,
        String action,
        String resourceType,
        UUID resourceId,
        String outcome,
        java.time.Instant createdAt
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO audit_event (
                    id, tenant_id, user_id, username, action, resource_type,
                    resource_id, outcome, reason, created_at
                )
                SELECT ?, ?, id, username, ?, ?, ?, ?, 'INTEGRATION_TEST', ?
                FROM app_user
                WHERE id = ?
                """,
            UUID.randomUUID(),
            tenantId,
            action,
            resourceType,
            resourceId,
            outcome,
            java.sql.Timestamp.from(createdAt),
            userId
        );
    }

    private String zeroVector() {
        return "[" + "0,".repeat(767) + "0]";
    }

    private void grantKnowledgeBase(String permission, UUID userId) {
        grantKnowledgeBase("USER", userId, permission, TENANT_ID, KNOWLEDGE_BASE_ID);
    }

    private void grantKnowledgeBase(
        String principalType,
        UUID principalId,
        String permission,
        UUID tenantId,
        UUID knowledgeBaseId
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO knowledge_base_membership (
                    id, tenant_id, knowledge_base_id, principal_type, principal_id, permission
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            tenantId,
            knowledgeBaseId,
            principalType,
            principalId,
            permission
        );
    }

    private void grantDocumentAcl(
        String principalType,
        UUID principalId,
        String permission,
        UUID tenantId,
        UUID documentId
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO document_acl (
                    id, tenant_id, document_id, principal_type, principal_id, permission
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            tenantId,
            documentId,
            principalType,
            principalId,
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
