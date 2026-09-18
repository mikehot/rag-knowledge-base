package com.example.ragknowledgebase.indexing;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.document.DocumentContentSnapshot;
import com.example.ragknowledgebase.document.DocumentRepository;
import com.example.ragknowledgebase.document.DocumentStatus;
import com.example.ragknowledgebase.document.KnowledgeDocument;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexTaskService {
    private static final int MAX_LIMIT = 200;
    private static final Set<String> STATUSES = Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED");

    private final IndexTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public IndexTaskService(
        IndexTaskRepository taskRepository,
        DocumentRepository documentRepository,
        AccessControlService accessControlService,
        AuditService auditService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public IndexTaskRecord enqueue(
        AuthenticatedUser user,
        KnowledgeDocument document,
        String operation,
        DocumentContentSnapshot rollbackContent
    ) {
        documentRepository.saveAndFlush(document);
        IndexTaskRecord task = taskRepository.enqueue(
            user.tenantId(),
            document.getKnowledgeBaseId(),
            document.getId(),
            user.userId(),
            operation,
            document.getContentVersion(),
            rollbackContent
        );
        eventPublisher.publishEvent(new IndexTaskQueuedEvent(task.id()));
        return task;
    }

    @Transactional
    public BatchReindexResponse batchReindex(AuthenticatedUser user, UUID knowledgeBaseId) {
        requireManage(user, knowledgeBaseId, "INDEX_TASK_BATCH_CREATE");
        List<UUID> taskIds = documentRepository
            .findByTenantIdAndKnowledgeBaseIdAndDeletedAtIsNullAndDisabledAtIsNull(user.tenantId(), knowledgeBaseId)
            .stream()
            .filter(document -> document.getStatus() != DocumentStatus.PROCESSING)
            .map(document -> {
                document.bumpContentVersion();
                document.markProcessing();
                return enqueue(user, document, "REINDEX", null).id();
            })
            .toList();
        return new BatchReindexResponse(knowledgeBaseId, taskIds.size(), taskIds);
    }

    @Transactional(readOnly = true)
    public IndexTaskListResponse list(
        AuthenticatedUser user,
        UUID knowledgeBaseId,
        String status,
        int limit
    ) {
        requireManage(user, knowledgeBaseId, "INDEX_TASK_LIST");
        validateLimit(limit);
        String normalizedStatus = normalizeStatus(status);
        List<IndexTaskRecord> rows = taskRepository.list(
            user.tenantId(),
            knowledgeBaseId,
            normalizedStatus,
            limit + 1
        );
        boolean hasMore = rows.size() > limit;
        List<IndexTaskResponse> items = rows.stream()
            .limit(limit)
            .map(IndexTaskResponse::from)
            .toList();
        return new IndexTaskListResponse(items, limit, hasMore);
    }

    @Transactional(readOnly = true)
    public IndexTaskResponse get(AuthenticatedUser user, UUID knowledgeBaseId, UUID taskId) {
        requireManage(user, knowledgeBaseId, "INDEX_TASK_GET");
        return taskRepository.find(user.tenantId(), knowledgeBaseId, taskId)
            .map(IndexTaskResponse::from)
            .orElseThrow(() -> new BusinessException(404, "索引任务不存在"));
    }

    @Transactional
    public IndexTaskResponse retry(AuthenticatedUser user, UUID knowledgeBaseId, UUID taskId) {
        requireManage(user, knowledgeBaseId, "INDEX_TASK_RETRY");
        IndexTaskRecord task = taskRepository.find(user.tenantId(), knowledgeBaseId, taskId)
            .orElseThrow(() -> new BusinessException(404, "索引任务不存在"));
        if (task.rollbackContent() != null) {
            throw new BusinessException(409, "替换任务已回滚，请重新上传新版本");
        }
        KnowledgeDocument document = documentRepository.findById(task.documentId())
            .filter(item -> item.getTenantId().equals(user.tenantId()))
            .filter(item -> item.getKnowledgeBaseId().equals(knowledgeBaseId))
            .orElseThrow(() -> new BusinessException(404, "文档不存在"));
        if (document.getDeletedAt() != null || document.getDisabledAt() != null) {
            throw new BusinessException(409, "文档已删除或停用，无法重试索引");
        }
        if (!taskRepository.retryFailed(user.tenantId(), knowledgeBaseId, taskId)) {
            throw new BusinessException(409, "仅失败任务可以重试");
        }
        document.markProcessing();
        documentRepository.save(document);
        eventPublisher.publishEvent(new IndexTaskQueuedEvent(taskId));
        return taskRepository.find(user.tenantId(), knowledgeBaseId, taskId)
            .map(IndexTaskResponse::from)
            .orElseThrow(() -> new BusinessException(404, "索引任务不存在"));
    }

    private void requireManage(AuthenticatedUser user, UUID knowledgeBaseId, String action) {
        if (!accessControlService.canManageKnowledgeBase(user, knowledgeBaseId)) {
            auditService.recordDenied(
                user,
                action,
                "KNOWLEDGE_BASE",
                knowledgeBaseId,
                "MISSING_KNOWLEDGE_BASE_MANAGE"
            );
            throw new BusinessException(403, "需要知识库 MANAGE 权限");
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new BusinessException(400, "status 仅支持 PENDING、RUNNING、SUCCEEDED、FAILED");
        }
        return normalized;
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(400, "limit 必须在 1 到 200 之间");
        }
    }
}
