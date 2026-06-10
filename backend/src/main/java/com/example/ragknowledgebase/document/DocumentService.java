package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.common.BusinessException;
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
    private final ChunkJdbcRepository chunkRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(
        DocumentRepository documentRepository,
        ChunkJdbcRepository chunkRepository,
        FileStorageService fileStorageService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.fileStorageService = fileStorageService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DocumentUploadResponse upload(UUID userId, MultipartFile file) {
        UUID documentId = UUID.randomUUID();
        StoredFile stored = fileStorageService.store(documentId, file);
        KnowledgeDocument document = new KnowledgeDocument(
            documentId,
            userId,
            stored.originalName(),
            stored.extension(),
            stored.path()
        );
        documentRepository.save(document);
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId));
        return new DocumentUploadResponse(documentId, DocumentStatus.PROCESSING.apiValue());
    }

    @Transactional(readOnly = true)
    public DocumentListResponse list(UUID userId) {
        List<DocumentResponse> items = documentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(DocumentResponse::from)
            .toList();
        return new DocumentListResponse(items);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID userId, UUID documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
            .map(DocumentResponse::from)
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
    }

    @Transactional
    public DeleteDocumentResponse delete(UUID userId, UUID documentId) {
        KnowledgeDocument document = documentRepository.findByIdAndUserId(documentId, userId)
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
        chunkRepository.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
        return new DeleteDocumentResponse(true);
    }
}
