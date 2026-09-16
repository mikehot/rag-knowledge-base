package com.example.ragknowledgebase.admin;

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

    public AdminService(
        JdbcTemplate jdbcTemplate,
        PasswordEncoder passwordEncoder,
        AccessControlService accessControlService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.accessControlService = accessControlService;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(AuthenticatedUser operator) {
        requireSystemAdmin(operator);
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
        requireSystemAdmin(operator);
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
        requireSystemAdmin(operator);
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

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases(AuthenticatedUser operator) {
        requireSystemAdmin(operator);
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

    @Transactional(readOnly = true)
    public List<KnowledgeBaseMembershipResponse> listMemberships(
        AuthenticatedUser operator,
        UUID knowledgeBaseId
    ) {
        requireKnowledgeBaseManager(operator, knowledgeBaseId);
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
        requireKnowledgeBaseManager(operator, knowledgeBaseId);
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
        requireKnowledgeBaseManager(operator, knowledgeBaseId);
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

    private void requireSystemAdmin(AuthenticatedUser operator) {
        if (!accessControlService.isSystemAdmin(operator)) {
            throw new BusinessException(403, "需要 SYSTEM_ADMIN 权限");
        }
    }

    private void requireKnowledgeBaseManager(AuthenticatedUser operator, UUID knowledgeBaseId) {
        if (!accessControlService.canManageKnowledgeBase(operator, knowledgeBaseId)) {
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

    private void validatePermission(String permission) {
        if (!"READ".equals(permission) && !"MANAGE".equals(permission)) {
            throw new BusinessException(400, "permission 仅支持 READ、MANAGE");
        }
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
