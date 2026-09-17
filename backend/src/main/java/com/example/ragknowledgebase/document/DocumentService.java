package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.storage.FileStorageService;
import com.example.ragknowledgebase.storage.FileStorageService.StoredFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final AppProperties properties;
    private final AccessControlService accessControlService;
    private final ChunkJdbcRepository chunkRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public DocumentService(
        DocumentRepository documentRepository,
        AppProperties properties,
        AccessControlService accessControlService,
        ChunkJdbcRepository chunkRepository,
        FileStorageService fileStorageService,
        ApplicationEventPublisher eventPublisher,
        AuditService auditService
    ) {
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.accessControlService = accessControlService;
        this.chunkRepository = chunkRepository;
        this.fileStorageService = fileStorageService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public DocumentUploadResponse upload(AuthenticatedUser user, MultipartFile file) {
        UUID knowledgeBaseId = properties.enterprise().defaultKnowledgeBaseId();
        if (!accessControlService.canManageKnowledgeBase(user, knowledgeBaseId)) {
            auditService.recordDenied(
                user,
                "DOCUMENT_UPLOAD",
                "KNOWLEDGE_BASE",
                knowledgeBaseId,
                "MISSING_KNOWLEDGE_BASE_MANAGE"
            );
            throw new BusinessException(403, "无权向该知识库上传文档");
        }
        String checksum = checksumOf(file);
        var existing = documentRepository.findByTenantIdAndKnowledgeBaseIdAndChecksumAndDeletedAtIsNull(
            user.tenantId(),
            knowledgeBaseId,
            checksum
        );
        if (existing.isPresent()) {
            return new DocumentUploadResponse(
                existing.get().getId(),
                existing.get().getStatus().apiValue(),
                true
            );
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
            stored.path(),
            checksum
        );
        documentRepository.save(document);
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId));
        return new DocumentUploadResponse(documentId, DocumentStatus.PROCESSING.apiValue(), false);
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
            auditService.recordDenied(
                user,
                "DOCUMENT_DELETE",
                "DOCUMENT",
                documentId,
                "MISSING_DOCUMENT_MANAGE"
            );
            throw new BusinessException(404, "文档不存在");
        }
        KnowledgeDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
        chunkRepository.deleteByDocumentId(document.getId());
        document.softDelete();
        documentRepository.save(document);
        fileStorageService.delete(document.getFilePath());
        return new DeleteDocumentResponse(true);
    }

    @Transactional
    public DocumentLifecycleResponse disable(AuthenticatedUser user, UUID documentId) {
        KnowledgeDocument document = manageableDocument(user, documentId, "DOCUMENT_DISABLE");
        document.disable();
        documentRepository.save(document);
        return DocumentLifecycleResponse.from(document);
    }

    @Transactional
    public DocumentLifecycleResponse enable(AuthenticatedUser user, UUID documentId) {
        KnowledgeDocument document = manageableDocument(user, documentId, "DOCUMENT_ENABLE");
        document.enable();
        documentRepository.save(document);
        return DocumentLifecycleResponse.from(document);
    }

    @Transactional
    public DocumentLifecycleResponse reindex(AuthenticatedUser user, UUID documentId) {
        KnowledgeDocument document = manageableDocument(user, documentId, "DOCUMENT_REINDEX");
        if (document.getDeletedAt() != null) {
            throw new BusinessException(404, "文档不存在");
        }
        if (document.getDisabledAt() != null) {
            throw new BusinessException(409, "文档已停用，启用后再重建索引");
        }
        chunkRepository.deleteByDocumentId(document.getId());
        document.bumpContentVersion();
        document.markProcessing();
        documentRepository.save(document);
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId));
        return DocumentLifecycleResponse.from(document);
    }

    @Transactional
    public DocumentReplaceResponse replace(AuthenticatedUser user, UUID documentId, MultipartFile file) {
        KnowledgeDocument document = manageableDocument(user, documentId, "DOCUMENT_REPLACE");
        if (document.getDisabledAt() != null) {
            throw new BusinessException(409, "文档已停用，启用后再替换");
        }
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw new BusinessException(409, "文档正在处理中，请稍后再替换");
        }

        String checksum = checksumOf(file);
        var checksumMatch = documentRepository.findByTenantIdAndKnowledgeBaseIdAndChecksumAndDeletedAtIsNull(
            user.tenantId(),
            document.getKnowledgeBaseId(),
            checksum
        );
        if (checksumMatch.isPresent()) {
            if (checksumMatch.get().getId().equals(documentId)) {
                return DocumentReplaceResponse.from(document, true);
            }
            throw new BusinessException(409, "相同内容已存在于当前知识库");
        }

        DocumentContentSnapshot rollbackContent = document.contentSnapshot();
        StoredFile stored = fileStorageService.storeVersion(documentId, document.getContentVersion() + 1, file);
        deleteStoredFileOnRollback(stored.path());
        document.replaceContent(stored.originalName(), stored.extension(), stored.path(), checksum);
        documentRepository.save(document);
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId, rollbackContent));
        return DocumentReplaceResponse.from(document, false);
    }

    private KnowledgeDocument manageableDocument(AuthenticatedUser user, UUID documentId, String action) {
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
        return documentRepository.findById(documentId)
            .filter(document -> document.getDeletedAt() == null)
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
    }

    private String checksumOf(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new BusinessException(400, "文件读取失败，请重试");
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(500, "文件校验失败，请重试");
        }
    }

    private void deleteStoredFileOnRollback(String storedPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        fileStorageService.delete(storedPath);
                    } catch (RuntimeException ignored) {
                        // The original transaction failure remains the primary error.
                    }
                }
            }
        });
    }

}
