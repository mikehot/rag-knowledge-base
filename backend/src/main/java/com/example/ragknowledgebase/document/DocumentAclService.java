package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentAclService {
    private final JdbcTemplate jdbcTemplate;
    private final AccessControlService accessControlService;
    private final AuditService auditService;

    public DocumentAclService(
        JdbcTemplate jdbcTemplate,
        AccessControlService accessControlService,
        AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DocumentAclResponse> list(AuthenticatedUser user, UUID documentId) {
        requireManage(user, documentId, "DOCUMENT_ACL_LIST");
        return jdbcTemplate.query(
            """
                SELECT id, document_id, principal_type, principal_id, permission
                FROM document_acl
                WHERE tenant_id = ? AND document_id = ?
                ORDER BY created_at, id
                """,
            (rs, rowNum) -> response(rs),
            user.tenantId(),
            documentId
        );
    }

    @Transactional(readOnly = true)
    public List<AclPrincipalResponse> listPrincipals(
        AuthenticatedUser user,
        UUID documentId,
        String principalType
    ) {
        requireManage(user, documentId, "DOCUMENT_ACL_PRINCIPAL_LIST");
        String normalizedType = principalType == null
            ? ""
            : principalType.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedType) {
            case "USER" -> jdbcTemplate.query(
                """
                    SELECT id, username, display_name,
                           NULL::varchar AS name, NULL::varchar AS code, status
                    FROM app_user
                    WHERE tenant_id = ? AND status = 'ACTIVE'
                    ORDER BY display_name NULLS LAST, username, id
                    """,
                this::principalResponse,
                user.tenantId()
            );
            case "DEPARTMENT" -> jdbcTemplate.query(
                """
                    SELECT id, NULL::varchar AS username, NULL::varchar AS display_name,
                           name, code, status
                    FROM department
                    WHERE tenant_id = ? AND status = 'ACTIVE'
                    ORDER BY name, code, id
                    """,
                this::principalResponse,
                user.tenantId()
            );
            case "ROLE" -> jdbcTemplate.query(
                """
                    SELECT id, NULL::varchar AS username, NULL::varchar AS display_name,
                           name, code, NULL::varchar AS status
                    FROM app_role
                    WHERE tenant_id = ?
                    ORDER BY code, id
                    """,
                this::principalResponse,
                user.tenantId()
            );
            default -> throw new BusinessException(400, "principalType 仅支持 USER、DEPARTMENT、ROLE");
        };
    }

    @Transactional
    public DocumentAclResponse grant(
        AuthenticatedUser user,
        UUID documentId,
        GrantDocumentAclRequest request
    ) {
        requireManage(user, documentId, "DOCUMENT_ACL_GRANT");
        validatePrincipal(user.tenantId(), request.principalType(), request.principalId());
        validatePermission(request.permission());
        UUID aclId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO document_acl (
                        id, tenant_id, document_id, principal_type, principal_id, permission
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                aclId,
                user.tenantId(),
                documentId,
                request.principalType(),
                request.principalId(),
                request.permission()
            );
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "文档权限已存在");
        }
        bumpPermissionVersion(user.tenantId(), documentId);
        auditService.recordPermissionChange(
            user,
            "DOCUMENT_ACL_GRANT",
            "DOCUMENT",
            documentId,
            grantDetail(request.principalType(), request.principalId(), request.permission())
        );
        return get(user.tenantId(), aclId);
    }

    @Transactional
    public DeleteDocumentAclResponse revoke(
        AuthenticatedUser user,
        UUID documentId,
        UUID aclId
    ) {
        requireManage(user, documentId, "DOCUMENT_ACL_REVOKE");
        List<String> revoked = jdbcTemplate.query(
            """
                DELETE FROM document_acl
                WHERE id = ? AND tenant_id = ? AND document_id = ?
                RETURNING principal_type, principal_id, permission
                """,
            (rs, rowNum) -> grantDetail(
                rs.getString("principal_type"),
                rs.getObject("principal_id", UUID.class),
                rs.getString("permission")
            ),
            aclId,
            user.tenantId(),
            documentId
        );
        if (revoked.isEmpty()) {
            throw new BusinessException(404, "文档权限不存在");
        }
        bumpPermissionVersion(user.tenantId(), documentId);
        auditService.recordPermissionChange(user, "DOCUMENT_ACL_REVOKE", "DOCUMENT", documentId, revoked.get(0));
        return new DeleteDocumentAclResponse(true);
    }

    private void requireManage(AuthenticatedUser user, UUID documentId, String action) {
        if (!accessControlService.canManageDocument(user, documentId)) {
            auditService.recordDenied(
                user,
                action,
                "DOCUMENT",
                documentId,
                "MISSING_DOCUMENT_MANAGE"
            );
            throw new BusinessException(404, "文档不存在");
        }
    }

    private void validatePrincipal(UUID tenantId, String principalType, UUID principalId) {
        String table = switch (principalType) {
            case "USER" -> "app_user";
            case "DEPARTMENT" -> "department";
            case "ROLE" -> "app_role";
            default -> throw new BusinessException(400, "principalType 仅支持 USER、DEPARTMENT、ROLE");
        };
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND id = ?",
            Integer.class,
            tenantId,
            principalId
        );
        if (count == null || count == 0) {
            throw new BusinessException(400, "授权主体不存在");
        }
    }

    private void validatePermission(String permission) {
        if (!"READ".equals(permission) && !"MANAGE".equals(permission)) {
            throw new BusinessException(400, "permission 仅支持 READ、MANAGE");
        }
    }

    private static String grantDetail(String principalType, UUID principalId, String permission) {
        return principalType + ":" + principalId + ":" + permission;
    }

    private void bumpPermissionVersion(UUID tenantId, UUID documentId) {
        jdbcTemplate.update(
            "UPDATE document SET permission_version = permission_version + 1 WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
            documentId,
            tenantId
        );
    }

    private DocumentAclResponse get(UUID tenantId, UUID aclId) {
        return jdbcTemplate.queryForObject(
            """
                SELECT id, document_id, principal_type, principal_id, permission
                FROM document_acl
                WHERE id = ? AND tenant_id = ?
                """,
            (rs, rowNum) -> response(rs),
            aclId,
            tenantId
        );
    }

    private DocumentAclResponse response(ResultSet rs) throws SQLException {
        return new DocumentAclResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            rs.getString("principal_type"),
            rs.getObject("principal_id", UUID.class),
            rs.getString("permission")
        );
    }

    private AclPrincipalResponse principalResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AclPrincipalResponse(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("name"),
            rs.getString("code"),
            rs.getString("status")
        );
    }
}
