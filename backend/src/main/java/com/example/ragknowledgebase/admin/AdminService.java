package com.example.ragknowledgebase.admin;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public AdminService(
        JdbcTemplate jdbcTemplate,
        PasswordEncoder passwordEncoder,
        AccessControlService accessControlService,
        AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(AuthenticatedUser operator) {
        requireSystemAdmin(operator, "ROLE_LIST");
        return jdbcTemplate.query(
            """
                SELECT id, code, name
                FROM app_role
                WHERE tenant_id = ?
                ORDER BY code
                """,
            (rs, rowNum) -> new RoleResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name")
            ),
            operator.tenantId()
        );
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers(AuthenticatedUser operator) {
        requireSystemAdmin(operator, "USER_LIST");
        return jdbcTemplate.query(
            """
                SELECT u.id, u.username, u.display_name, u.status, u.department_id,
                       COALESCE(array_remove(array_agg(r.code ORDER BY r.code), NULL), ARRAY[]::varchar[]) AS role_codes
                FROM app_user u
                LEFT JOIN user_role ur ON ur.user_id = u.id
                LEFT JOIN app_role r ON r.id = ur.role_id AND r.tenant_id = u.tenant_id
                WHERE u.tenant_id = ?
                GROUP BY u.id, u.username, u.display_name, u.status, u.department_id
                ORDER BY u.created_at DESC
                """,
            (rs, rowNum) -> userResponse(rs),
            operator.tenantId()
        );
    }

    @Transactional
    public UserResponse createUser(AuthenticatedUser operator, CreateUserRequest request) {
        requireSystemAdmin(operator, "USER_CREATE");
        UUID userId = UUID.randomUUID();
        validateDepartment(operator.tenantId(), request.departmentId());
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO app_user (
                        id, tenant_id, department_id, username, password_hash, display_name, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """,
                userId,
                operator.tenantId(),
                request.departmentId(),
                request.username(),
                passwordEncoder.encode(request.password()),
                request.displayName()
            );
            for (String roleCode : request.normalizedRoleCodes()) {
                int assigned = jdbcTemplate.update(
                    """
                        INSERT INTO user_role (user_id, role_id)
                        SELECT ?, id
                        FROM app_role
                        WHERE tenant_id = ?
                          AND code = ?
                        ON CONFLICT DO NOTHING
                        """,
                    userId,
                    operator.tenantId(),
                    roleCode
                );
                if (assigned == 0) {
                    throw new BusinessException(400, "角色不存在: " + roleCode);
                }
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "用户名已存在");
        }
        return getUser(operator.tenantId(), userId);
    }

    @Transactional
    public UserResponse assignUserRole(
        AuthenticatedUser operator,
        UUID userId,
        AssignUserRoleRequest request
    ) {
        requireSystemAdmin(operator, "USER_ROLE_ASSIGN");
        requireUser(operator.tenantId(), userId);
        int assigned = jdbcTemplate.update(
            """
                INSERT INTO user_role (user_id, role_id)
                SELECT ?, id
                FROM app_role
                WHERE tenant_id = ?
                  AND code = ?
                ON CONFLICT DO NOTHING
                """,
            userId,
            operator.tenantId(),
            request.roleCode()
        );
        if (assigned == 0 && !roleExists(operator.tenantId(), request.roleCode())) {
            throw new BusinessException(400, "角色不存在: " + request.roleCode());
        }
        return getUser(operator.tenantId(), userId);
    }

    @Transactional
    public DeleteUserRoleResponse revokeUserRole(
        AuthenticatedUser operator,
        UUID userId,
        String roleCode
    ) {
        requireSystemAdmin(operator, "USER_ROLE_REVOKE");
        requireUser(operator.tenantId(), userId);
        String normalizedRoleCode = normalizeCode(roleCode);
        int deleted = jdbcTemplate.update(
            """
                DELETE FROM user_role ur
                USING app_role r
                WHERE ur.role_id = r.id
                  AND ur.user_id = ?
                  AND r.tenant_id = ?
                  AND r.code = ?
                """,
            userId,
            operator.tenantId(),
            normalizedRoleCode
        );
        if (deleted == 0) {
            throw new BusinessException(404, "用户角色不存在");
        }
        return new DeleteUserRoleResponse(true);
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments(AuthenticatedUser operator) {
        requireSystemAdmin(operator, "DEPARTMENT_LIST");
        return jdbcTemplate.query(
            """
                SELECT id, parent_id, code, name, status
                FROM department
                WHERE tenant_id = ?
                ORDER BY code
                """,
            (rs, rowNum) -> departmentResponse(rs),
            operator.tenantId()
        );
    }

    @Transactional
    public DepartmentResponse createDepartment(
        AuthenticatedUser operator,
        CreateDepartmentRequest request
    ) {
        requireSystemAdmin(operator, "DEPARTMENT_CREATE");
        validateParentDepartment(operator.tenantId(), request.parentId());
        UUID departmentId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO department (id, tenant_id, parent_id, code, name, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """,
                departmentId,
                operator.tenantId(),
                request.parentId(),
                request.code(),
                request.name()
            );
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "部门编码已存在");
        }
        return getDepartment(operator.tenantId(), departmentId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases(AuthenticatedUser operator) {
        requireSystemAdmin(operator, "KNOWLEDGE_BASE_LIST");
        return jdbcTemplate.query(
            """
                SELECT id, code, name, description, status
                FROM knowledge_base
                WHERE tenant_id = ?
                ORDER BY code
                """,
            (rs, rowNum) -> new KnowledgeBaseResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status")
            ),
            operator.tenantId()
        );
    }

    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(
        AuthenticatedUser operator,
        CreateKnowledgeBaseRequest request
    ) {
        requireSystemAdmin(operator, "KNOWLEDGE_BASE_CREATE");
        UUID knowledgeBaseId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO knowledge_base (
                        id, tenant_id, code, name, description, status, created_by
                    )
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """,
                knowledgeBaseId,
                operator.tenantId(),
                request.code(),
                request.name(),
                request.description(),
                operator.userId()
            );
            jdbcTemplate.update(
                """
                    INSERT INTO knowledge_base_membership (
                        id, tenant_id, knowledge_base_id, principal_type, principal_id, permission
                    )
                    VALUES (?, ?, ?, 'USER', ?, 'MANAGE')
                    ON CONFLICT DO NOTHING
                    """,
                UUID.randomUUID(),
                operator.tenantId(),
                knowledgeBaseId,
                operator.userId()
            );
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "知识库编码已存在");
        }
        return getKnowledgeBase(operator.tenantId(), knowledgeBaseId);
    }

    @Transactional
    public KnowledgeBaseResponse updateKnowledgeBaseStatus(
        AuthenticatedUser operator,
        UUID knowledgeBaseId,
        String status
    ) {
        requireSystemAdmin(operator, "KNOWLEDGE_BASE_STATUS_UPDATE");
        validateKnowledgeBaseStatus(status);
        int updated = jdbcTemplate.update(
            """
                UPDATE knowledge_base
                SET status = ?,
                    updated_at = now()
                WHERE id = ?
                  AND tenant_id = ?
                """,
            status,
            knowledgeBaseId,
            operator.tenantId()
        );
        if (updated == 0) {
            throw new BusinessException(404, "知识库不存在");
        }
        return getKnowledgeBase(operator.tenantId(), knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseMembershipResponse> listMemberships(
        AuthenticatedUser operator,
        UUID knowledgeBaseId
    ) {
        requireKnowledgeBaseManager(operator, knowledgeBaseId, "KNOWLEDGE_BASE_MEMBERSHIP_LIST");
        return jdbcTemplate.query(
            """
                SELECT id, principal_type, principal_id, permission
                FROM knowledge_base_membership
                WHERE tenant_id = ?
                  AND knowledge_base_id = ?
                ORDER BY created_at DESC
                """,
            (rs, rowNum) -> membershipResponse(rs),
            operator.tenantId(),
            knowledgeBaseId
        );
    }

    @Transactional
    public KnowledgeBaseMembershipResponse grantMembership(
        AuthenticatedUser operator,
        UUID knowledgeBaseId,
        GrantKnowledgeBaseMembershipRequest request
    ) {
        requireKnowledgeBaseManager(operator, knowledgeBaseId, "KNOWLEDGE_BASE_MEMBERSHIP_GRANT");
        validatePrincipal(operator.tenantId(), request.principalType(), request.principalId());
        validatePermission(request.permission());
        UUID membershipId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO knowledge_base_membership (
                        id, tenant_id, knowledge_base_id, principal_type, principal_id, permission
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                membershipId,
                operator.tenantId(),
                knowledgeBaseId,
                request.principalType(),
                request.principalId(),
                request.permission()
            );
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "授权已存在");
        }
        return getMembership(operator.tenantId(), membershipId);
    }

    @Transactional
    public DeleteMembershipResponse revokeMembership(
        AuthenticatedUser operator,
        UUID knowledgeBaseId,
        UUID membershipId
    ) {
        requireKnowledgeBaseManager(operator, knowledgeBaseId, "KNOWLEDGE_BASE_MEMBERSHIP_REVOKE");
        int deleted = jdbcTemplate.update(
            """
                DELETE FROM knowledge_base_membership
                WHERE id = ?
                  AND tenant_id = ?
                  AND knowledge_base_id = ?
                """,
            membershipId,
            operator.tenantId(),
            knowledgeBaseId
        );
        if (deleted == 0) {
            throw new BusinessException(404, "授权不存在");
        }
        return new DeleteMembershipResponse(true);
    }

    private void requireSystemAdmin(AuthenticatedUser operator, String action) {
        if (!accessControlService.isSystemAdmin(operator)) {
            auditService.recordDenied(
                operator,
                action,
                "TENANT",
                operator.tenantId(),
                "MISSING_SYSTEM_ADMIN"
            );
            throw new BusinessException(403, "需要 SYSTEM_ADMIN 权限");
        }
    }

    private void requireKnowledgeBaseManager(AuthenticatedUser operator, UUID knowledgeBaseId, String action) {
        if (!accessControlService.canManageKnowledgeBase(operator, knowledgeBaseId)) {
            auditService.recordDenied(
                operator,
                action,
                "KNOWLEDGE_BASE",
                knowledgeBaseId,
                "MISSING_KNOWLEDGE_BASE_MANAGE"
            );
            throw new BusinessException(403, "需要知识库 MANAGE 权限");
        }
    }

    private void validatePrincipal(UUID tenantId, String principalType, UUID principalId) {
        Integer count = switch (principalType) {
            case "USER" -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE tenant_id = ? AND id = ?",
                Integer.class,
                tenantId,
                principalId
            );
            case "DEPARTMENT" -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM department WHERE tenant_id = ? AND id = ?",
                Integer.class,
                tenantId,
                principalId
            );
            case "ROLE" -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_role WHERE tenant_id = ? AND id = ?",
                Integer.class,
                tenantId,
                principalId
            );
            default -> throw new BusinessException(400, "principalType 仅支持 USER、DEPARTMENT、ROLE");
        };
        if (count == null || count == 0) {
            throw new BusinessException(400, "授权主体不存在");
        }
    }

    private void validateDepartment(UUID tenantId, UUID departmentId) {
        if (departmentId == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM department WHERE tenant_id = ? AND id = ?",
            Integer.class,
            tenantId,
            departmentId
        );
        if (count == null || count == 0) {
            throw new BusinessException(400, "部门不存在");
        }
    }

    private void validateParentDepartment(UUID tenantId, UUID parentId) {
        if (parentId == null) {
            return;
        }
        validateDepartment(tenantId, parentId);
    }

    private void validatePermission(String permission) {
        if (!"READ".equals(permission) && !"MANAGE".equals(permission)) {
            throw new BusinessException(400, "permission 仅支持 READ、MANAGE");
        }
    }

    private void validateKnowledgeBaseStatus(String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException(400, "知识库状态仅支持 ACTIVE、DISABLED");
        }
    }

    private void requireUser(UUID tenantId, UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_user WHERE tenant_id = ? AND id = ?",
            Integer.class,
            tenantId,
            userId
        );
        if (count == null || count == 0) {
            throw new BusinessException(404, "用户不存在");
        }
    }

    private boolean roleExists(UUID tenantId, String roleCode) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_role WHERE tenant_id = ? AND code = ?",
            Integer.class,
            tenantId,
            roleCode
        );
        return count != null && count > 0;
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "编码不能为空");
        }
        return value.trim().toUpperCase();
    }

    private UserResponse getUser(UUID tenantId, UUID userId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT u.id, u.username, u.display_name, u.status, u.department_id,
                       COALESCE(array_remove(array_agg(r.code ORDER BY r.code), NULL), ARRAY[]::varchar[]) AS role_codes
                FROM app_user u
                LEFT JOIN user_role ur ON ur.user_id = u.id
                LEFT JOIN app_role r ON r.id = ur.role_id AND r.tenant_id = u.tenant_id
                WHERE u.tenant_id = ?
                  AND u.id = ?
                GROUP BY u.id, u.username, u.display_name, u.status, u.department_id
                """,
            (rs, rowNum) -> userResponse(rs),
            tenantId,
            userId
        );
    }

    private DepartmentResponse getDepartment(UUID tenantId, UUID departmentId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT id, parent_id, code, name, status
                FROM department
                WHERE tenant_id = ?
                  AND id = ?
                """,
            (rs, rowNum) -> departmentResponse(rs),
            tenantId,
            departmentId
        );
    }

    private KnowledgeBaseResponse getKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT id, code, name, description, status
                FROM knowledge_base
                WHERE tenant_id = ?
                  AND id = ?
                """,
            (rs, rowNum) -> knowledgeBaseResponse(rs),
            tenantId,
            knowledgeBaseId
        );
    }

    private KnowledgeBaseMembershipResponse getMembership(UUID tenantId, UUID membershipId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT id, principal_type, principal_id, permission
                FROM knowledge_base_membership
                WHERE tenant_id = ?
                  AND id = ?
                """,
            (rs, rowNum) -> membershipResponse(rs),
            tenantId,
            membershipId
        );
    }

    private DepartmentResponse departmentResponse(ResultSet rs) throws SQLException {
        return new DepartmentResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("status")
        );
    }

    private KnowledgeBaseResponse knowledgeBaseResponse(ResultSet rs) throws SQLException {
        return new KnowledgeBaseResponse(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status")
        );
    }

    private UserResponse userResponse(ResultSet rs) throws SQLException {
        String[] roleCodes = (String[]) rs.getArray("role_codes").getArray();
        return new UserResponse(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("status"),
            rs.getObject("department_id", UUID.class),
            List.of(roleCodes)
        );
    }

    private KnowledgeBaseMembershipResponse membershipResponse(ResultSet rs) throws SQLException {
        return new KnowledgeBaseMembershipResponse(
            rs.getObject("id", UUID.class),
            rs.getString("principal_type"),
            rs.getObject("principal_id", UUID.class),
            rs.getString("permission")
        );
    }
}
