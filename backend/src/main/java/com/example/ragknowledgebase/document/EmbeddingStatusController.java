package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/embeddings")
public class EmbeddingStatusController {
    private final EmbeddingStatusService embeddingStatusService;

    public EmbeddingStatusController(EmbeddingStatusService embeddingStatusService) {
        this.embeddingStatusService = embeddingStatusService;
    }

    @GetMapping
    public ApiResponse<EmbeddingStatusResponse> get(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return ApiResponse.ok(embeddingStatusService.get(user));
    }
}
