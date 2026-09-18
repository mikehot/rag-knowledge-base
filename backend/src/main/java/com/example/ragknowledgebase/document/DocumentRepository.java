package com.example.ragknowledgebase.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    List<KnowledgeDocument> findByTenantIdAndKnowledgeBaseIdAndDeletedAtIsNullAndDisabledAtIsNull(
        UUID tenantId,
        UUID knowledgeBaseId
    );

    Optional<KnowledgeDocument> findByTenantIdAndKnowledgeBaseIdAndChecksumAndDeletedAtIsNull(
        UUID tenantId,
        UUID knowledgeBaseId,
        String checksum
    );

    @Query(value = """
        SELECT d.*
        FROM document d
        WHERE d.tenant_id = :tenantId
          AND d.deleted_at IS NULL
          AND d.disabled_at IS NULL
          AND (
            d.user_id = :userId
            OR EXISTS (
              SELECT 1 FROM user_role ur
              JOIN app_role r ON r.id = ur.role_id
              WHERE ur.user_id = :userId
                AND r.tenant_id = :tenantId
                AND r.code = 'SYSTEM_ADMIN'
            )
            OR EXISTS (
              SELECT 1 FROM knowledge_base_membership m
              WHERE m.knowledge_base_id = d.knowledge_base_id
                AND m.tenant_id = :tenantId
                AND m.permission IN ('READ', 'MANAGE')
                AND (
                  (m.principal_type = 'USER' AND m.principal_id = :userId)
                  OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
                    SELECT u.department_id FROM app_user u
                    WHERE u.id = :userId AND u.tenant_id = :tenantId
                  ))
                  OR (m.principal_type = 'ROLE' AND EXISTS (
                    SELECT 1 FROM user_role ur
                    WHERE ur.user_id = :userId AND ur.role_id = m.principal_id
                  ))
                )
            )
            OR EXISTS (
              SELECT 1 FROM document_acl a
              WHERE a.document_id = d.id
                AND a.tenant_id = :tenantId
                AND a.permission IN ('READ', 'MANAGE')
                AND (
                  (a.principal_type = 'USER' AND a.principal_id = :userId)
                  OR (a.principal_type = 'DEPARTMENT' AND a.principal_id = (
                    SELECT u.department_id FROM app_user u
                    WHERE u.id = :userId AND u.tenant_id = :tenantId
                  ))
                  OR (a.principal_type = 'ROLE' AND EXISTS (
                    SELECT 1 FROM user_role ur
                    WHERE ur.user_id = :userId AND ur.role_id = a.principal_id
                  ))
                )
            )
          )
        ORDER BY d.created_at DESC
        """, nativeQuery = true)
    List<KnowledgeDocument> findAccessible(
        @Param("tenantId") UUID tenantId,
        @Param("userId") UUID userId
    );

    @Query(value = """
        SELECT visible.* FROM (
          SELECT d.*
          FROM document d
          WHERE d.tenant_id = :tenantId
            AND d.deleted_at IS NULL
            AND d.disabled_at IS NULL
            AND (
              d.user_id = :userId
              OR EXISTS (
                SELECT 1 FROM user_role ur
                JOIN app_role r ON r.id = ur.role_id
                WHERE ur.user_id = :userId
                  AND r.tenant_id = :tenantId
                  AND r.code = 'SYSTEM_ADMIN'
              )
              OR EXISTS (
                SELECT 1 FROM knowledge_base_membership m
                WHERE m.knowledge_base_id = d.knowledge_base_id
                  AND m.tenant_id = :tenantId
                  AND m.permission IN ('READ', 'MANAGE')
                  AND (
                    (m.principal_type = 'USER' AND m.principal_id = :userId)
                    OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
                      SELECT u.department_id FROM app_user u
                      WHERE u.id = :userId AND u.tenant_id = :tenantId
                    ))
                    OR (m.principal_type = 'ROLE' AND EXISTS (
                      SELECT 1 FROM user_role ur
                      WHERE ur.user_id = :userId AND ur.role_id = m.principal_id
                    ))
                  )
              )
              OR EXISTS (
                SELECT 1 FROM document_acl a
                WHERE a.document_id = d.id
                  AND a.tenant_id = :tenantId
                  AND a.permission IN ('READ', 'MANAGE')
                  AND (
                    (a.principal_type = 'USER' AND a.principal_id = :userId)
                    OR (a.principal_type = 'DEPARTMENT' AND a.principal_id = (
                      SELECT u.department_id FROM app_user u
                      WHERE u.id = :userId AND u.tenant_id = :tenantId
                    ))
                    OR (a.principal_type = 'ROLE' AND EXISTS (
                      SELECT 1 FROM user_role ur
                      WHERE ur.user_id = :userId AND ur.role_id = a.principal_id
                    ))
                  )
              )
            )
        ) visible
        WHERE visible.id = :id
        """, nativeQuery = true)
    Optional<KnowledgeDocument> findAccessibleById(
        @Param("id") UUID id,
        @Param("tenantId") UUID tenantId,
        @Param("userId") UUID userId
    );
}
