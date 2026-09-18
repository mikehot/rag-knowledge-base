package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AskFeedbackService {
    private final AskLogRepository askLogRepository;
    private final AskFeedbackRepository askFeedbackRepository;

    public AskFeedbackService(
        AskLogRepository askLogRepository,
        AskFeedbackRepository askFeedbackRepository
    ) {
        this.askLogRepository = askLogRepository;
        this.askFeedbackRepository = askFeedbackRepository;
    }

    @Transactional
    public AskFeedbackResponse submit(
        AuthenticatedUser user,
        UUID requestId,
        AskFeedbackRequest request
    ) {
        AskLog askLog = askLogRepository
            .findByRequestIdAndTenantIdAndUserId(requestId, user.tenantId(), user.userId())
            .orElseThrow(() -> new BusinessException(404, "问答记录不存在"));
        String reason = sanitize(request.reason());
        AskFeedback feedback = askFeedbackRepository.findByAskLogId(askLog.getId())
            .map(existing -> {
                existing.update(request.rating(), reason);
                return existing;
            })
            .orElseGet(() -> new AskFeedback(
                UUID.randomUUID(),
                askLog.getId(),
                user.tenantId(),
                user.userId(),
                request.rating(),
                reason
            ));
        AskFeedback saved = askFeedbackRepository.save(feedback);
        return new AskFeedbackResponse(requestId, saved.getRating(), saved.getReason(), saved.getUpdatedAt());
    }

    private String sanitize(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String sanitized = reason
            .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return sanitized.isEmpty() ? null : sanitized;
    }
}
