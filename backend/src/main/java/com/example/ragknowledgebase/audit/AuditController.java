package com.example.ragknowledgebase.audit;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-events")
public class AuditController {
    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    public ApiResponse<AuditEventListResponse> list(
        Authentication authentication,
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String resourceType,
        @RequestParam(required = false) UUID resourceId,
        @RequestParam(required = false) String outcome,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(auditQueryService.list(
            currentUser(authentication),
            userId,
            action,
            resourceType,
            resourceId,
            outcome,
            from,
            to,
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
