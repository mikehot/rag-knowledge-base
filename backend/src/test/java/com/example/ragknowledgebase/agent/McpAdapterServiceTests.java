package com.example.ragknowledgebase.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpAdapterServiceTests {
    private static final AuthenticatedUser USER = new AuthenticatedUser(
        UUID.fromString("10000000-0000-0000-0000-000000000301"),
        UUID.fromString("00000000-0000-0000-0000-000000000301"),
        "mcp-test"
    );

    @Mock
    private AgentToolService agentToolService;

    private ObjectMapper objectMapper;
    private McpAdapterService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new McpAdapterService(agentToolService, objectMapper);
    }

    @Test
    void discoversStatelessProtocolAndReadOnlyToolsCapability() throws Exception {
        JsonNode response = service.handle(USER, request("server/discover", "1", null),
            McpAdapterService.PROTOCOL_VERSION, "server/discover", null);

        assertThat(response.path("result").path("protocolVersion").asText())
            .isEqualTo(McpAdapterService.PROTOCOL_VERSION);
        assertThat(response.path("result").path("capabilities").path("tools").path("listChanged").asBoolean())
            .isFalse();
    }

    @Test
    void listsOnlyTheApplicationOwnedToolsWithPrivateCache() throws Exception {
        when(agentToolService.definitions()).thenReturn(List.of(
            new AgentToolDefinitionResponse("search_knowledge", "search", Map.of("type", "object"))
        ));

        JsonNode response = service.handle(USER, request("tools/list", "2", objectMapper.createObjectNode()),
            McpAdapterService.PROTOCOL_VERSION, "tools/list", null);

        assertThat(response.path("result").path("tools")).hasSize(1);
        assertThat(response.path("result").path("tools").path(0).path("name").asText())
            .isEqualTo("search_knowledge");
        assertThat(response.path("result").path("cacheScope").asText()).isEqualTo("private");
        assertThat(response.path("result").path("ttlMs").asInt()).isZero();
    }

    @Test
    void delegatesToolCallWithoutAllowingIdentityArguments() throws Exception {
        JsonNode arguments = objectMapper.createObjectNode().put("query", "报销规则");
        when(agentToolService.execute(any(), any())).thenReturn(new AgentToolExecutionResponse(
            UUID.randomUUID(),
            List.of(new AgentToolCallResponse(
                "search_knowledge",
                true,
                objectMapper.createObjectNode().put("found", false),
                null
            ))
        ));

        JsonNode response = service.handle(USER, request("tools/call", "3",
                objectMapper.createObjectNode().put("name", "search_knowledge").set("arguments", arguments)),
            McpAdapterService.PROTOCOL_VERSION, "tools/call", "search_knowledge");

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("structuredContent").path("found").asBoolean()).isFalse();
        verify(agentToolService).execute(any(), any());
    }

    @Test
    void rejectsProtocolAndHeaderMismatchBeforeToolExecution() throws Exception {
        JsonNode request = request("tools/list", "4", null);
        JsonNode response = service.handle(USER, request, "2025-11-25", "tools/call", null);

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(response.path("error").path("message").asText()).contains("MCP-Protocol-Version");
    }

    private JsonNode request(String method, String id, JsonNode params) {
        var request = objectMapper.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        return request;
    }
}
