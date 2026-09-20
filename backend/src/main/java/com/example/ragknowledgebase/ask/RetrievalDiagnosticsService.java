package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RetrievalDiagnosticsService {
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final AskRetrievalHitRepository retrievalHitRepository;

    public RetrievalDiagnosticsService(
        AccessControlService accessControlService,
        AuditService auditService,
        AskRetrievalHitRepository retrievalHitRepository
    ) {
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.retrievalHitRepository = retrievalHitRepository;
    }

    public RetrievalDiagnosticsResponse get(AuthenticatedUser user, UUID requestId) {
        if (!accessControlService.canReadAuditEvents(user)) {
            auditService.recordDenied(
                user,
                "RETRIEVAL_DIAGNOSTIC_GET",
                "ASK_LOG",
                requestId,
                "MISSING_AUDIT_READER"
            );
            throw new BusinessException(403, "需要 SYSTEM_ADMIN 或 AUDITOR 权限");
        }
        return retrievalHitRepository.findByRequestId(user.tenantId(), requestId)
            .map(RetrievalDiagnosticsResponse::from)
            .orElseThrow(() -> new BusinessException(404, "问答记录不存在"));
    }
}
