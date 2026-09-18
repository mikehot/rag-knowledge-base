package com.example.ragknowledgebase.indexing;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks")
public class IndexTaskController {
    private final IndexTaskService taskService;

    public IndexTaskController(IndexTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/batch-reindex")
    public ApiResponse<BatchReindexResponse> batchReindex(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId
    ) {
        return ApiResponse.ok(taskService.batchReindex(currentUser(authentication), knowledgeBaseId));
    }

    @GetMapping
    public ApiResponse<IndexTaskListResponse> list(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(taskService.list(currentUser(authentication), knowledgeBaseId, status, limit));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<IndexTaskResponse> get(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId,
        @PathVariable UUID taskId
    ) {
        return ApiResponse.ok(taskService.get(currentUser(authentication), knowledgeBaseId, taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<IndexTaskResponse> retry(
        Authentication authentication,
        @PathVariable UUID knowledgeBaseId,
        @PathVariable UUID taskId
    ) {
        return ApiResponse.ok(taskService.retry(currentUser(authentication), knowledgeBaseId, taskId));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
