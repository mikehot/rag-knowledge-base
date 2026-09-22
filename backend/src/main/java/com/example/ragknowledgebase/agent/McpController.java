package com.example.ragknowledgebase.agent;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mcp")
public class McpController {
    private final McpAdapterService mcpAdapterService;
    private final ObjectMapper objectMapper;

    public McpController(McpAdapterService mcpAdapterService, ObjectMapper objectMapper) {
        this.mcpAdapterService = mcpAdapterService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode handle(
        Authentication authentication,
        @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
        @RequestHeader(value = "Mcp-Method", required = false) String method,
        @RequestHeader(value = "Mcp-Name", required = false) String name,
        @RequestBody(required = false) String body
    ) {
        AuthenticatedUser user = currentUser(authentication);
        JsonNode request;
        try {
            request = body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            return mcpAdapterService.parseError();
        }
        return mcpAdapterService.handle(user, request, protocolVersion, method, name);
    }

    private AuthenticatedUser currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return user;
    }
}
