package com.example.ragknowledgebase.agent;

import com.example.ragknowledgebase.ai.AiCallException;
import com.example.ragknowledgebase.ai.EmbeddingProvider;
import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.common.RequestIdContext;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.document.ChunkJdbcRepository;
import com.example.ragknowledgebase.document.ChunkSearchResult;
import com.example.ragknowledgebase.document.DocumentListResponse;
import com.example.ragknowledgebase.document.DocumentResponse;
import com.example.ragknowledgebase.document.DocumentService;
import com.example.ragknowledgebase.document.DocumentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentToolService {
    private static final int MAX_TOOL_CALLS = 3;
    private static final int MAX_QUERY_LENGTH = 1000;
    private static final Set<String> SEARCH_ARGUMENTS = Set.of("query", "knowledgeBaseId");
    private static final Set<String> LIST_ARGUMENTS = Set.of("status", "knowledgeBaseId");
    private static final Set<String> STATUS_ARGUMENTS = Set.of("documentId");

    private final AppProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkJdbcRepository chunkRepository;
    private final DocumentService documentService;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AgentToolService(
        AppProperties properties,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository,
        DocumentService documentService,
        AccessControlService accessControlService,
        AuditService auditService,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.documentService = documentService;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public List<AgentToolDefinitionResponse> definitions() {
        return List.of(
            new AgentToolDefinitionResponse(
                "search_knowledge",
                "Search ACL-filtered knowledge chunks without generating an answer.",
                schema(
                    Map.of(
                        "query", Map.of("type", "string", "maxLength", MAX_QUERY_LENGTH),
                        "knowledgeBaseId", Map.of("type", "string", "format", "uuid")
                    ),
                    List.of("query")
                )
            ),
            new AgentToolDefinitionResponse(
                "list_documents",
                "List ACL-filtered document metadata and processing status.",
                schema(
                    Map.of(
                        "status", Map.of("type", "string", "enum", List.of("processing", "ready", "failed")),
                        "knowledgeBaseId", Map.of("type", "string", "format", "uuid")
                    ),
                    List.of()
                )
            ),
            new AgentToolDefinitionResponse(
                "get_document_status",
                "Read the status of one ACL-visible document without returning file content.",
                schema(
                    Map.of("documentId", Map.of("type", "string", "format", "uuid")),
                    List.of("documentId")
                )
            )
        );
    }

    public AgentToolExecutionResponse execute(
        AuthenticatedUser user,
        AgentToolExecutionRequest request
    ) {
        if (request == null || request.calls() == null || request.calls().isEmpty()) {
            throw new BusinessException(400, "至少提供一个工具调用");
        }
        if (request.calls().size() > MAX_TOOL_CALLS) {
            throw new BusinessException(400, "单次最多调用 3 个只读工具");
        }
        UUID requestId = RequestIdContext.currentOrNew();
        List<AgentToolCallResponse> results = new ArrayList<>();
        for (AgentToolCall call : request.calls()) {
            results.add(executeOne(user, call));
        }
        return new AgentToolExecutionResponse(requestId, List.copyOf(results));
    }

    private AgentToolCallResponse executeOne(AuthenticatedUser user, AgentToolCall call) {
        String toolName = call == null || call.name() == null ? "" : call.name().trim();
        String action = action(toolName);
        try {
            if (!Set.of("search_knowledge", "list_documents", "get_document_status").contains(toolName)) {
                auditService.recordError(user, action, "AGENT_TOOL", null, "TOOL_NOT_FOUND");
                return failure(toolName, AgentToolFailureReason.TOOL_NOT_FOUND);
            }
            Object data = switch (toolName) {
                case "search_knowledge" -> searchKnowledge(user, call.arguments());
                case "list_documents" -> listDocuments(user, call.arguments());
                case "get_document_status" -> getDocumentStatus(user, call.arguments());
                default -> throw new IllegalStateException("unreachable");
            };
            auditService.recordAllowed(user, action, "AGENT_TOOL", null, "TOOL_EXECUTED");
            return new AgentToolCallResponse(toolName, true, objectMapper.valueToTree(data), null);
        } catch (ToolInputException ex) {
            auditService.recordError(user, action, "AGENT_TOOL", null, "INVALID_ARGUMENTS");
            return failure(toolName, AgentToolFailureReason.INVALID_ARGUMENTS);
        } catch (BusinessException ex) {
            if (ex.code() == 403 || ex.code() == 404) {
                auditService.recordDenied(user, action, "AGENT_TOOL", null, "PERMISSION_DENIED");
                return failure(toolName, AgentToolFailureReason.PERMISSION_DENIED);
            }
            auditService.recordError(user, action, "AGENT_TOOL", null, "BUSINESS_ERROR");
            return failure(toolName, AgentToolFailureReason.INVALID_ARGUMENTS);
        } catch (AiCallException ex) {
            AgentToolFailureReason reason = AiCallException.isTimeout(ex)
                ? AgentToolFailureReason.TIMEOUT
                : AgentToolFailureReason.PROVIDER_ERROR;
            auditService.recordError(user, action, "AGENT_TOOL", null, reason.name());
            return failure(toolName, reason);
        } catch (Exception ex) {
            auditService.recordError(user, action, "AGENT_TOOL", null, "INTERNAL_ERROR");
            return failure(toolName, AgentToolFailureReason.INTERNAL_ERROR);
        }
    }

    private AgentKnowledgeSearchResponse searchKnowledge(AuthenticatedUser user, JsonNode arguments) {
        JsonNode object = objectArguments(arguments, SEARCH_ARGUMENTS);
        String query = requiredText(object, "query", MAX_QUERY_LENGTH);
        UUID knowledgeBaseId = optionalUuid(object, "knowledgeBaseId");
        requireKnowledgeBaseRead(user, knowledgeBaseId);

        List<float[]> embeddings = embeddingProvider.embed(List.of(query));
        if (embeddings.isEmpty()) {
            throw new IllegalStateException("Embedding 返回为空");
        }
        int resultLimit = Math.max(1, Math.min(5, properties.rag().topK()));
        List<ChunkSearchResult> hits = chunkRepository.search(
            user.tenantId(),
            user.userId(),
            embeddings.get(0),
            resultLimit,
            knowledgeBaseId
        );
        List<AgentKnowledgeSource> sources = hits.stream()
            .map(hit -> new AgentKnowledgeSource(
                hit.documentId(),
                hit.filename(),
                hit.locator(),
                snippet(hit.content()),
                hit.similarity()
            ))
            .toList();
        return new AgentKnowledgeSearchResponse(!sources.isEmpty(), sources);
    }

    private DocumentListResponse listDocuments(AuthenticatedUser user, JsonNode arguments) {
        JsonNode object = objectArguments(arguments, LIST_ARGUMENTS);
        UUID knowledgeBaseId = optionalUuid(object, "knowledgeBaseId");
        requireKnowledgeBaseRead(user, knowledgeBaseId);
        DocumentStatus status = optionalStatus(object, "status");
        List<DocumentResponse> items = documentService.list(user).items().stream()
            .filter(item -> knowledgeBaseId == null || knowledgeBaseId.equals(item.knowledgeBaseId()))
            .filter(item -> status == null || item.status().equals(status.apiValue()))
            .toList();
        return new DocumentListResponse(items);
    }

    private DocumentResponse getDocumentStatus(AuthenticatedUser user, JsonNode arguments) {
        JsonNode object = objectArguments(arguments, STATUS_ARGUMENTS);
        UUID documentId = requiredUuid(object, "documentId");
        return documentService.get(user, documentId);
    }

    private void requireKnowledgeBaseRead(AuthenticatedUser user, UUID knowledgeBaseId) {
        if (knowledgeBaseId != null && !accessControlService.canReadKnowledgeBase(user, knowledgeBaseId)) {
            throw new BusinessException(404, "知识库不存在");
        }
    }

    private JsonNode objectArguments(JsonNode arguments, Set<String> allowedFields) {
        if (arguments == null || !arguments.isObject()) {
            throw new ToolInputException("arguments 必须是 JSON 对象");
        }
        Iterator<String> fields = arguments.fieldNames();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next())) {
                throw new ToolInputException("arguments 包含不支持的字段");
            }
        }
        return arguments;
    }

    private String requiredText(JsonNode object, String field, int maxLength) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolInputException(field + " 必须是非空字符串");
        }
        String normalized = value.asText().trim();
        if (normalized.length() > maxLength) {
            throw new ToolInputException(field + " 超过长度限制");
        }
        return normalized;
    }

    private UUID requiredUuid(JsonNode object, String field) {
        UUID value = optionalUuid(object, field);
        if (value == null) {
            throw new ToolInputException(field + " 必须是 UUID");
        }
        return value;
    }

    private UUID optionalUuid(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ToolInputException(field + " 必须是 UUID 字符串");
        }
        try {
            return UUID.fromString(value.asText().trim());
        } catch (IllegalArgumentException ex) {
            throw new ToolInputException(field + " 必须是 UUID 字符串");
        }
    }

    private DocumentStatus optionalStatus(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(value.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolInputException(field + " 仅支持 processing、ready、failed");
        }
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false
        );
    }

    private String action(String toolName) {
        String normalized = toolName.isBlank() ? "UNKNOWN" : toolName.toUpperCase(Locale.ROOT);
        return "AGENT_TOOL_" + normalized;
    }

    private AgentToolCallResponse failure(String toolName, AgentToolFailureReason reason) {
        return new AgentToolCallResponse(toolName, false, null, reason);
    }

    private String snippet(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 360 ? compact : compact.substring(0, 360) + "...";
    }

    private static final class ToolInputException extends RuntimeException {
        private ToolInputException(String message) {
            super(message);
        }
    }
}
