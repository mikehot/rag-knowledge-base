package com.example.ragknowledgebase.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.storage.FileStorageService;
import java.util.Optional;
import java.security.MessageDigest;
import java.util.HexFormat;
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

    @Mock
    private AuditService auditService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
            documentRepository,
            properties(),
            accessControlService,
            chunkRepository,
            fileStorageService,
            eventPublisher,
            auditService
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
        verify(auditService).recordDenied(
            USER,
            "DOCUMENT_DELETE",
            "DOCUMENT",
            DOCUMENT_ID,
            "MISSING_DOCUMENT_MANAGE"
        );
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
        verify(auditService).recordDenied(
            USER,
            "DOCUMENT_UPLOAD",
            "KNOWLEDGE_BASE",
            KNOWLEDGE_BASE_ID,
            "MISSING_KNOWLEDGE_BASE_MANAGE"
        );
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

    @Test
    void replacesContentWithoutDeletingOldChunksBeforeNewIndexIsReady() throws Exception {
        String oldChecksum = checksum("old content");
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "faq-v2.md",
            "text/markdown",
            "new content".getBytes()
        );
        KnowledgeDocument document = new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "faq.md",
            "md",
            "/tmp/faq.md",
            oldChecksum
        );
        document.markReady(2);
        DocumentContentSnapshot previous = document.contentSnapshot();
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(true);
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(documentRepository.findByTenantIdAndKnowledgeBaseIdAndChecksumAndDeletedAtIsNull(
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            checksum("new content")
        )).thenReturn(Optional.empty());
        when(fileStorageService.storeVersion(DOCUMENT_ID, 2, file))
            .thenReturn(new FileStorageService.StoredFile("faq-v2.md", "md", "/tmp/faq-v2.md"));

        DocumentReplaceResponse response = documentService.replace(USER, DOCUMENT_ID, file);

        assertThat(response.status()).isEqualTo("processing");
        assertThat(response.contentVersion()).isEqualTo(2);
        assertThat(response.unchanged()).isFalse();
        assertThat(document.getFilename()).isEqualTo("faq-v2.md");
        assertThat(document.getFilePath()).isEqualTo("/tmp/faq-v2.md");
        assertThat(document.getChecksum()).isEqualTo(checksum("new content"));
        verify(chunkRepository, never()).deleteByDocumentId(DOCUMENT_ID);
        verify(documentRepository).save(document);
        verify(eventPublisher).publishEvent(new DocumentCreatedEvent(DOCUMENT_ID, previous));
    }

    @Test
    void sameChecksumReplacementIsAnUnchangedIdempotentResult() throws Exception {
        String checksum = checksum("same content");
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "faq.md",
            "text/markdown",
            "same content".getBytes()
        );
        KnowledgeDocument document = new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "faq.md",
            "md",
            "/tmp/faq.md",
            checksum
        );
        document.markReady(2);
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(true);
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(documentRepository.findByTenantIdAndKnowledgeBaseIdAndChecksumAndDeletedAtIsNull(
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            checksum
        )).thenReturn(Optional.of(document));

        DocumentReplaceResponse response = documentService.replace(USER, DOCUMENT_ID, file);

        assertThat(response.unchanged()).isTrue();
        assertThat(response.contentVersion()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("ready");
        verify(fileStorageService, never()).storeVersion(any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(documentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsReplacementWithoutDocumentManagePermission() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "faq.md",
            "text/markdown",
            "new content".getBytes()
        );
        when(accessControlService.canManageDocument(USER, DOCUMENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> documentService.replace(USER, DOCUMENT_ID, file))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(404);

        verify(auditService).recordDenied(
            USER,
            "DOCUMENT_REPLACE",
            "DOCUMENT",
            DOCUMENT_ID,
            "MISSING_DOCUMENT_MANAGE"
        );
        verify(fileStorageService, never()).storeVersion(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private String checksum(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
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
