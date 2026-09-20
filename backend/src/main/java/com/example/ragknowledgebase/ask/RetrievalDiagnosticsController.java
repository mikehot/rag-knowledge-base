package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/retrieval-diagnostics")
public class RetrievalDiagnosticsController {
    private final RetrievalDiagnosticsService retrievalDiagnosticsService;

    public RetrievalDiagnosticsController(RetrievalDiagnosticsService retrievalDiagnosticsService) {
        this.retrievalDiagnosticsService = retrievalDiagnosticsService;
    }

    @GetMapping("/{requestId}")
    public ApiResponse<RetrievalDiagnosticsResponse> get(
        Authentication authentication,
        @PathVariable UUID requestId
    ) {
        return ApiResponse.ok(retrievalDiagnosticsService.get(currentUser(authentication), requestId));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
