package com.example.ragknowledgebase.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.storage.FileStorageService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "demo");

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ChunkJdbcRepository chunkRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
            documentRepository,
            properties(),
            accessControlService,
            chunkRepository,
            fileStorageService,
            eventPublisher
        );
    }

    @Test
    void softDeletesChunksAndRawFileBeforeHidingDocument() {
        KnowledgeDocument document = new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "faq.md",
            "md",
            "/tmp/faq.md"
        );
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(true);
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

        DeleteDocumentResponse response = documentService.delete(USER, DOCUMENT_ID);

        assertThat(response.deleted()).isTrue();
        InOrder order = inOrder(chunkRepository, documentRepository, fileStorageService);
        order.verify(chunkRepository).deleteByDocumentId(DOCUMENT_ID);
        order.verify(documentRepository).save(document);
        order.verify(fileStorageService).delete("/tmp/faq.md");
        assertThat(document.getDeletedAt()).isNotNull();
    }

    @Test
    void rejectsDeleteWhenDocumentIsNotOwnedByUser() {
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> documentService.delete(USER, DOCUMENT_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessage("文档不存在")
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(404);

        verify(chunkRepository, never()).deleteByDocumentId(DOCUMENT_ID);
        verify(documentRepository, never()).save(any());
        verify(fileStorageService, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsUploadWithoutKnowledgeBaseManagePermission() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "faq.md",
            "text/markdown",
            "sample".getBytes()
        );
        when(accessControlService.canManageKnowledgeBase(USER, KNOWLEDGE_BASE_ID)).thenReturn(false);

        assertThatThrownBy(() -> documentService.upload(USER, file))
            .isInstanceOf(BusinessException.class)
            .hasMessage("无权向该知识库上传文档")
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(403);

        verify(fileStorageService, never()).store(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(documentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsExistingDocumentWhenChecksumAlreadyExists() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "faq.md",
            "text/markdown",
            "sample".getBytes()
        );
        KnowledgeDocument existing = new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "faq.md",
            "md",
            "/tmp/faq.md",
            "af2bdbe1aa9b6ec1e2adE1d694f41fc71a831d0268e9891562113d8a62add1bf".toLowerCase()
        );
        existing.markReady(2);
        when(accessControlService.canManageKnowledgeBase(USER, KNOWLEDGE_BASE_ID)).thenReturn(true);
        when(documentRepository.findByTenantIdAndKnowledgeBaseIdAndChecksumAndDeletedAtIsNull(
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            "af2bdbe1aa9b6ec1e2adE1d694f41fc71a831d0268e9891562113d8a62add1bf".toLowerCase()
        )).thenReturn(Optional.of(existing));

        DocumentUploadResponse response = documentService.upload(USER, file);

        assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(response.status()).isEqualTo("ready");
        assertThat(response.duplicated()).isTrue();
        verify(fileStorageService, never()).store(any(), any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void reindexDeletesChunksBumpsVersionAndPublishesEvent() {
        KnowledgeDocument document = new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "faq.md",
            "md",
            "/tmp/faq.md"
        );
        document.markReady(2);
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(true);
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

        DocumentLifecycleResponse response = documentService.reindex(USER, DOCUMENT_ID);

        assertThat(response.status()).isEqualTo("processing");
        assertThat(response.contentVersion()).isEqualTo(2);
        assertThat(response.permissionVersion()).isEqualTo(1);
        verify(chunkRepository).deleteByDocumentId(DOCUMENT_ID);
        verify(documentRepository).save(document);
        verify(eventPublisher).publishEvent(new DocumentCreatedEvent(DOCUMENT_ID));
    }

    private AppProperties properties() {
        return new AppProperties(
            null,
            null,
            null,
            new AppProperties.Enterprise(TENANT_ID, KNOWLEDGE_BASE_ID, true),
            null,
            null
        );
    }
}
