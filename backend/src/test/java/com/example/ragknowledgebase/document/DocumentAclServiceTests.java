package com.example.ragknowledgebase.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DocumentAclServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000201");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000201");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "acl-admin");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    private DocumentAclService service;

    @BeforeEach
    void setUp() {
        service = new DocumentAclService(jdbcTemplate, accessControlService, auditService);
    }

    @Test
    void hidesAclManagementWhenCallerCannotManageDocument() {
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.grant(
            USER,
            DOCUMENT_ID,
            new GrantDocumentAclRequest("USER", UUID.randomUUID(), "READ")
        ))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(404);

        verify(auditService).recordDenied(
            USER,
            "DOCUMENT_ACL_GRANT",
            "DOCUMENT",
            DOCUMENT_ID,
            "MISSING_DOCUMENT_MANAGE"
        );
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsUnknownPermissionBeforeDatabaseMutation() {
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.grant(
            USER,
            DOCUMENT_ID,
            new GrantDocumentAclRequest("USER", UUID.randomUUID(), "WRITE")
        ))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(400);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }
}
