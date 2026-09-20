package com.example.ragknowledgebase.auth;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {
    private final JdbcTemplate jdbcTemplate;

    public AccessControlService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean canManageKnowledgeBase(AuthenticatedUser user, UUID knowledgeBaseId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM knowledge_base kb
                WHERE kb.id = ?
                  AND kb.tenant_id = ?
                  AND kb.status = 'ACTIVE'
                  AND (
                    EXISTS (
                      SELECT 1
                      FROM user_role ur
                      JOIN app_role r ON r.id = ur.role_id
                      WHERE ur.user_id = ?
                        AND r.tenant_id = ?
                        AND r.code = 'SYSTEM_ADMIN'
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM knowledge_base_membership m
                      WHERE m.knowledge_base_id = kb.id
                        AND m.tenant_id = ?
                        AND m.permission = 'MANAGE'
                        AND (
                          (m.principal_type = 'USER' AND m.principal_id = ?)
                          OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
                            SELECT u.department_id FROM app_user u
                            WHERE u.id = ? AND u.tenant_id = ?
                          ))
                          OR (m.principal_type = 'ROLE' AND EXISTS (
                            SELECT 1 FROM user_role ur
                            WHERE ur.user_id = ? AND ur.role_id = m.principal_id
                          ))
                        )
                    )
                  )
                """,
            Integer.class,
            knowledgeBaseId,
            user.tenantId(),
            user.userId(),
            user.tenantId(),
            user.tenantId(),
            user.userId(),
            user.userId(),
            user.tenantId(),
            user.userId()
        );
        return count != null && count > 0;
    }

    public boolean canReadKnowledgeBase(AuthenticatedUser user, UUID knowledgeBaseId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM knowledge_base kb
                WHERE kb.id = ?
                  AND kb.tenant_id = ?
                  AND kb.status = 'ACTIVE'
                  AND (
                    EXISTS (
                      SELECT 1
                      FROM user_role ur
                      JOIN app_role r ON r.id = ur.role_id
                      WHERE ur.user_id = ?
                        AND r.tenant_id = ?
                        AND r.code = 'SYSTEM_ADMIN'
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM knowledge_base_membership m
                      WHERE m.knowledge_base_id = kb.id
                        AND m.tenant_id = ?
                        AND m.permission IN ('READ', 'MANAGE')
                        AND (
                          (m.principal_type = 'USER' AND m.principal_id = ?)
                          OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
                            SELECT u.department_id FROM app_user u
                            WHERE u.id = ? AND u.tenant_id = ?
                          ))
                          OR (m.principal_type = 'ROLE' AND EXISTS (
                            SELECT 1 FROM user_role ur
                            WHERE ur.user_id = ? AND ur.role_id = m.principal_id
                          ))
                        )
                    )
                  )
                """,
            Integer.class,
            knowledgeBaseId,
            user.tenantId(),
            user.userId(),
            user.tenantId(),
            user.tenantId(),
            user.userId(),
            user.userId(),
            user.tenantId(),
            user.userId()
        );
        return count != null && count > 0;
    }

    public boolean isSystemAdmin(AuthenticatedUser user) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM user_role ur
                JOIN app_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                  AND r.tenant_id = ?
                  AND r.code = 'SYSTEM_ADMIN'
                """,
            Integer.class,
            user.userId(),
            user.tenantId()
        );
        return count != null && count > 0;
    }

    public boolean canReadAuditEvents(AuthenticatedUser user) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM user_role ur
                JOIN app_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                  AND r.tenant_id = ?
                  AND r.code IN ('SYSTEM_ADMIN', 'AUDITOR')
                """,
            Integer.class,
            user.userId(),
            user.tenantId()
        );
        return count != null && count > 0;
    }

    public boolean canManageDocument(AuthenticatedUser user, UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT count(*)
                FROM document d
                WHERE d.id = ?
                  AND d.tenant_id = ?
                  AND d.deleted_at IS NULL
                  AND (
                    d.user_id = ?
                    OR EXISTS (
                      SELECT 1 FROM user_role ur
                      JOIN app_role r ON r.id = ur.role_id
                      WHERE ur.user_id = ?
                        AND r.tenant_id = ?
                        AND r.code = 'SYSTEM_ADMIN'
                    )
                    OR EXISTS (
                      SELECT 1 FROM knowledge_base_membership m
                      WHERE m.knowledge_base_id = d.knowledge_base_id
                        AND m.tenant_id = ?
                        AND m.permission = 'MANAGE'
                        AND (
                          (m.principal_type = 'USER' AND m.principal_id = ?)
                          OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
                            SELECT u.department_id FROM app_user u
                            WHERE u.id = ? AND u.tenant_id = ?
                          ))
                          OR (m.principal_type = 'ROLE' AND EXISTS (
                            SELECT 1 FROM user_role ur
                            WHERE ur.user_id = ? AND ur.role_id = m.principal_id
                          ))
                        )
                    )
                    OR EXISTS (
                      SELECT 1 FROM document_acl a
                      WHERE a.document_id = d.id
                        AND a.tenant_id = ?
                        AND a.permission = 'MANAGE'
                        AND (
                          (a.principal_type = 'USER' AND a.principal_id = ?)
                          OR (a.principal_type = 'DEPARTMENT' AND a.principal_id = (
                            SELECT u.department_id FROM app_user u
                            WHERE u.id = ? AND u.tenant_id = ?
                          ))
                          OR (a.principal_type = 'ROLE' AND EXISTS (
                            SELECT 1 FROM user_role ur
                            WHERE ur.user_id = ? AND ur.role_id = a.principal_id
                          ))
                        )
                    )
                  )
                """,
            Integer.class,
            documentId,
            user.tenantId(),
            user.userId(),
            user.userId(),
            user.tenantId(),
            user.tenantId(),
            user.userId(),
            user.userId(),
            user.tenantId(),
            user.userId(),
            user.tenantId(),
            user.userId(),
            user.userId(),
            user.tenantId(),
            user.userId()
        );
        return count != null && count > 0;
    }
}
