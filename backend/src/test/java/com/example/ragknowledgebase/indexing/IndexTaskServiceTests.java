package com.example.ragknowledgebase.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.document.DocumentContentSnapshot;
import com.example.ragknowledgebase.document.DocumentRepository;
import com.example.ragknowledgebase.document.DocumentStatus;
import com.example.ragknowledgebase.document.KnowledgeDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class IndexTaskServiceTests {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_BASE_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    @Mock
    private IndexTaskRepository taskRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private IndexTaskService service;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        service = new IndexTaskService(
            taskRepository,
            documentRepository,
            accessControlService,
            auditService,
            eventPublisher
        );
        user = new AuthenticatedUser(USER_ID, TENANT_ID, "manager");
        when(accessControlService.canManageKnowledgeBase(user, KNOWLEDGE_BASE_ID)).thenReturn(true);
    }

    @Test
    void retriesFailedReindexAndMarksDocumentProcessing() {
        KnowledgeDocument document = document();
        document.markFailed("previous failure");
        IndexTaskRecord failed = task("REINDEX", "FAILED", null, 3, 3);
        IndexTaskRecord pending = task("REINDEX", "PENDING", null, 3, 6);
        when(taskRepository.find(TENANT_ID, KNOWLEDGE_BASE_ID, TASK_ID))
            .thenReturn(Optional.of(failed), Optional.of(pending));
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(taskRepository.retryFailed(TENANT_ID, KNOWLEDGE_BASE_ID, TASK_ID)).thenReturn(true);

        var response = service.retry(user, KNOWLEDGE_BASE_ID, TASK_ID);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(documentRepository).save(document);
        verify(eventPublisher).publishEvent(new IndexTaskQueuedEvent(TASK_ID));
    }

    @Test
    void rejectsRetryAfterReplacementWasRolledBack() {
        DocumentContentSnapshot rollback = new DocumentContentSnapshot(
            "old.md",
            "md",
            "/tmp/old.md",
            "checksum",
            1,
            DocumentStatus.READY,
            2,
            null
        );
        when(taskRepository.find(TENANT_ID, KNOWLEDGE_BASE_ID, TASK_ID))
            .thenReturn(Optional.of(task("REPLACE", "FAILED", rollback, 3, 3)));

        assertThatThrownBy(() -> service.retry(user, KNOWLEDGE_BASE_ID, TASK_ID))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(409);

        verify(taskRepository, never()).retryFailed(TENANT_ID, KNOWLEDGE_BASE_ID, TASK_ID);
        verify(documentRepository, never()).findById(DOCUMENT_ID);
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "document.md",
            "md",
            "/tmp/document.md",
            "checksum"
        );
    }

    private IndexTaskRecord task(
        String operation,
        String status,
        DocumentContentSnapshot rollback,
        int attemptCount,
        int maxAttempts
    ) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        return new IndexTaskRecord(
            TASK_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            DOCUMENT_ID,
            USER_ID,
            operation,
            2,
            status,
            attemptCount,
            maxAttempts,
            null,
            now,
            now,
            null,
            null,
            rollback,
            now
        );
    }
}
