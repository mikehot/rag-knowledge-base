package com.example.ragknowledgebase.agent;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.ApiResponse;
import com.example.ragknowledgebase.common.BusinessException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/tools")
public class AgentToolController {
    private final AgentToolService agentToolService;

    public AgentToolController(AgentToolService agentToolService) {
        this.agentToolService = agentToolService;
    }

    @GetMapping
    public ApiResponse<List<AgentToolDefinitionResponse>> definitions(Authentication authentication) {
        currentUser(authentication);
        return ApiResponse.ok(agentToolService.definitions());
    }

    @PostMapping("/execute")
    public ApiResponse<AgentToolExecutionResponse> execute(
        Authentication authentication,
        @Valid @RequestBody AgentToolExecutionRequest request
    ) {
        return ApiResponse.ok(agentToolService.execute(currentUser(authentication), request));
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
