package com.example.ragknowledgebase.agent;

import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Minimal stateless MCP 2026-07-28 adapter over the existing application-owned tool boundary.
 * It intentionally does not implement a model loop, sessions, tasks, resources, prompts, or writes.
 */
@Service
public class McpAdapterService {
    public static final String PROTOCOL_VERSION = "2026-07-28";

    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final Set<String> CALL_FIELDS = Set.of("name", "arguments", "_meta");

    private final AgentToolService agentToolService;
    private final ObjectMapper objectMapper;

    public McpAdapterService(AgentToolService agentToolService, ObjectMapper objectMapper) {
        this.agentToolService = agentToolService;
        this.objectMapper = objectMapper;
    }

    public JsonNode handle(
        AuthenticatedUser user,
        JsonNode request,
        String protocolVersionHeader,
        String methodHeader,
        String nameHeader
    ) {
        JsonNode id = request == null ? null : request.get("id");
        try {
            validateTransport(protocolVersionHeader, methodHeader, nameHeader);
            validateRequest(request);
            String method = request.get("method").asText();
            if (!methodHeader.equals(method)) {
                return error(id, INVALID_REQUEST, "Mcp-Method 与请求方法不一致");
            }
            return switch (method) {
                case "server/discover" -> discover(id);
                case "tools/list" -> listTools(id, request.get("params"));
                case "tools/call" -> callTool(user, id, request.get("params"), nameHeader);
                default -> error(id, METHOD_NOT_FOUND, "MCP 方法不支持");
            };
        } catch (McpRequestException ex) {
            return error(id, ex.code(), ex.getMessage());
        } catch (Exception ex) {
            return error(id, INTERNAL_ERROR, "MCP 请求处理失败");
        }
    }

    public JsonNode parseError() {
        return error(null, -32700, "JSON-RPC 请求无法解析");
    }

    private JsonNode discover(JsonNode id) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode response = response(id, result);
        ObjectNode meta = response.putObject("_meta");
        meta.putObject("io.modelcontextprotocol/serverInfo")
            .put("name", "rag-knowledge-base")
            .put("version", "0.1");
        return response;
    }

    private JsonNode listTools(JsonNode id, JsonNode params) {
        validateListParams(params);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (AgentToolDefinitionResponse definition : agentToolService.definitions()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.set("inputSchema", objectMapper.valueToTree(definition.inputSchema()));
        }
        result.put("ttlMs", 0);
        result.put("cacheScope", "private");
        return response(id, result);
    }

    private JsonNode callTool(AuthenticatedUser user, JsonNode id, JsonNode params, String nameHeader) {
        if (params == null || !params.isObject()) {
            throw new McpRequestException(INVALID_PARAMS, "tools/call 参数必须是对象");
        }
        rejectUnknownFields(params, CALL_FIELDS);
        JsonNode name = params.get("name");
        if (name == null || !name.isTextual() || name.asText().isBlank()) {
            throw new McpRequestException(INVALID_PARAMS, "工具名称不能为空");
        }
        if (!nameHeader.equals(name.asText().trim())) {
            return error(id, INVALID_REQUEST, "Mcp-Name 与工具名称不一致");
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = objectMapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            throw new McpRequestException(INVALID_PARAMS, "工具 arguments 必须是对象");
        }

        AgentToolExecutionResponse execution = agentToolService.execute(
            user,
            new AgentToolExecutionRequest(List.of(new AgentToolCall(name.asText().trim(), arguments)))
        );
        if (execution.results().size() != 1) {
            return error(id, INTERNAL_ERROR, "工具执行结果数量不正确");
        }
        return response(id, toToolResult(execution.results().get(0)));
    }

    private ObjectNode toToolResult(AgentToolCallResponse call) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        if (!call.success()) {
            result.put("isError", true);
            content.addObject()
                .put("type", "text")
                .put("text", "工具执行失败：" + call.failureReason().name());
            return result;
        }
        result.put("isError", false);
        content.addObject()
            .put("type", "text")
            .put("text", "工具执行成功，详细结果见 structuredContent。");
        result.set("structuredContent", call.data() == null ? objectMapper.createObjectNode() : call.data());
        return result;
    }

    private void validateTransport(String protocolVersionHeader, String methodHeader, String nameHeader) {
        if (!PROTOCOL_VERSION.equals(protocolVersionHeader)) {
            throw new McpRequestException(INVALID_REQUEST, "MCP-Protocol-Version 不支持");
        }
        if (methodHeader == null || methodHeader.isBlank()) {
            throw new McpRequestException(INVALID_REQUEST, "缺少 Mcp-Method");
        }
        if ("tools/call".equals(methodHeader) && (nameHeader == null || nameHeader.isBlank())) {
            throw new McpRequestException(INVALID_REQUEST, "tools/call 缺少 Mcp-Name");
        }
    }

    private void validateRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new McpRequestException(INVALID_REQUEST, "JSON-RPC 请求必须是对象");
        }
        JsonNode version = request.get("jsonrpc");
        JsonNode id = request.get("id");
        JsonNode method = request.get("method");
        if (version == null || !"2.0".equals(version.asText()) || id == null || id.isObject() || id.isArray()
            || method == null || !method.isTextual() || method.asText().isBlank()) {
            throw new McpRequestException(INVALID_REQUEST, "JSON-RPC 请求格式不正确");
        }
    }

    private void validateListParams(JsonNode params) {
        if (params == null || params.isNull()) {
            return;
        }
        if (!params.isObject()) {
            throw new McpRequestException(INVALID_PARAMS, "tools/list 参数必须是对象");
        }
        JsonNode cursor = params.get("cursor");
        if (cursor != null && !cursor.isNull()) {
            throw new McpRequestException(INVALID_PARAMS, "暂不支持 tools/list 分页");
        }
        rejectUnknownFields(params, Set.of("cursor", "_meta"));
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowedFields) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new McpRequestException(INVALID_PARAMS, "MCP 参数包含不支持的字段");
            }
        });
    }

    private ObjectNode response(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.deepCopy());
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id.deepCopy());
        }
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static final class McpRequestException extends RuntimeException {
        private final int code;

        private McpRequestException(int code, String message) {
            super(message);
            this.code = code;
        }

        private int code() {
            return code;
        }
    }
}
