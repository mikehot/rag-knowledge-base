package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.storage.FileStorageService;
import com.example.ragknowledgebase.storage.FileStorageService.StoredFile;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final AppProperties properties;
    private final AccessControlService accessControlService;
    private final ChunkJdbcRepository chunkRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(
        DocumentRepository documentRepository,
        AppProperties properties,
        AccessControlService accessControlService,
        ChunkJdbcRepository chunkRepository,
        FileStorageService fileStorageService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.accessControlService = accessControlService;
        this.chunkRepository = chunkRepository;
        this.fileStorageService = fileStorageService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DocumentUploadResponse upload(AuthenticatedUser user, MultipartFile file) {
        UUID knowledgeBaseId = properties.enterprise().defaultKnowledgeBaseId();
        if (!accessControlService.canManageKnowledgeBase(user, knowledgeBaseId)) {
            throw new BusinessException(403, "无权向该知识库上传文档");
        }
        UUID documentId = UUID.randomUUID();
        StoredFile stored = fileStorageService.store(documentId, file);
        KnowledgeDocument document = new KnowledgeDocument(
            documentId,
            user.tenantId(),
            knowledgeBaseId,
            user.userId(),
            stored.originalName(),
            stored.extension(),
            stored.path()
        );
        documentRepository.save(document);
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId));
        return new DocumentUploadResponse(documentId, DocumentStatus.PROCESSING.apiValue());
    }

    @Transactional(readOnly = true)
    public DocumentListResponse list(AuthenticatedUser user) {
        List<DocumentResponse> items = documentRepository.findAccessible(user.tenantId(), user.userId()).stream()
            .map(DocumentResponse::from)
            .toList();
        return new DocumentListResponse(items);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(AuthenticatedUser user, UUID documentId) {
        return documentRepository.findAccessibleById(documentId, user.tenantId(), user.userId())
            .map(DocumentResponse::from)
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
    }

    @Transactional
    public DeleteDocumentResponse delete(AuthenticatedUser user, UUID documentId) {
        if (!accessControlService.canManageDocument(user, documentId)) {
            throw new BusinessException(404, "文档不存在");
        }
        KnowledgeDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
        chunkRepository.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
        fileStorageService.delete(document.getFilePath());
        return new DeleteDocumentResponse(true);
    }
}
