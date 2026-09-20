package com.example.ragknowledgebase.document;

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
        return get(user.tenantId(), aclId);
    }

    @Transactional
    public DeleteDocumentAclResponse revoke(
        AuthenticatedUser user,
        UUID documentId,
        UUID aclId
    ) {
        requireManage(user, documentId, "DOCUMENT_ACL_REVOKE");
        int deleted = jdbcTemplate.update(
            "DELETE FROM document_acl WHERE id = ? AND tenant_id = ? AND document_id = ?",
            aclId,
            user.tenantId(),
            documentId
        );
        if (deleted == 0) {
            throw new BusinessException(404, "文档权限不存在");
        }
        bumpPermissionVersion(user.tenantId(), documentId);
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
}
