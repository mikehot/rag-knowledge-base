package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ask-logs")
public class AskLogAdminController {
    private final AskLogQueryService askLogQueryService;

    public AskLogAdminController(AskLogQueryService askLogQueryService) {
        this.askLogQueryService = askLogQueryService;
    }

    @GetMapping
    public ApiResponse<AskLogSummaryListResponse> list(
        Authentication authentication,
        @RequestParam(required = false) String resultStatus,
        @RequestParam(required = false) String failureReason,
        @RequestParam(required = false) String rating,
        @RequestParam(defaultValue = "false") boolean needsReview,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(askLogQueryService.list(
            currentUser(authentication),
            resultStatus,
            failureReason,
            rating,
            needsReview,
            limit
        ));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
