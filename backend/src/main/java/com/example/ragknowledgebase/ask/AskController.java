package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AskController {
    private final AskService askService;
    private final AskFeedbackService askFeedbackService;

    public AskController(AskService askService, AskFeedbackService askFeedbackService) {
        this.askService = askService;
        this.askFeedbackService = askFeedbackService;
    }

    @PostMapping("/ask")
    public ApiResponse<AskResponse> ask(
        Authentication authentication,
        @Valid @RequestBody AskRequest request,
        @RequestHeader(value = "X-RAG-Retrieval-Mode", required = false) String retrievalMode
    ) {
        return ApiResponse.ok(askService.ask(
            currentUser(authentication),
            request,
            RetrievalMode.fromHeader(retrievalMode)
        ));
    }

    @PutMapping("/ask/{requestId}/feedback")
    public ApiResponse<AskFeedbackResponse> feedback(
        Authentication authentication,
        @PathVariable UUID requestId,
        @Valid @RequestBody AskFeedbackRequest request
    ) {
        return ApiResponse.ok(askFeedbackService.submit(currentUser(authentication), requestId, request));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
