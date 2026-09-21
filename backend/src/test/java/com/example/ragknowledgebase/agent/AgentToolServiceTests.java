package com.example.ragknowledgebase.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.ai.AiCallException;
import com.example.ragknowledgebase.ai.EmbeddingProvider;
import com.example.ragknowledgebase.audit.AuditService;
import com.example.ragknowledgebase.auth.AccessControlService;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.document.ChunkJdbcRepository;
import com.example.ragknowledgebase.document.ChunkSearchResult;
import com.example.ragknowledgebase.document.DocumentListResponse;
import com.example.ragknowledgebase.document.DocumentResponse;
import com.example.ragknowledgebase.document.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentToolServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000201");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000201");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "agent-test");

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private ChunkJdbcRepository chunkRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditService auditService;

    private AgentToolService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AgentToolService(
            properties(),
            embeddingProvider,
            chunkRepository,
            documentService,
            accessControlService,
            auditService,
            objectMapper
        );
    }

    @Test
    void exposesOnlyTheThreeReadOnlyToolsWithClosedSchemas() {
        assertThat(service.definitions())
            .extracting(AgentToolDefinitionResponse::name)
            .containsExactly("search_knowledge", "list_documents", "get_document_status");
        assertThat(service.definitions())
            .allSatisfy(definition -> assertThat(definition.inputSchema())
                .containsEntry("additionalProperties", false));
    }

    @Test
    void searchesOnlyWithinAclVisibleKnowledgeBaseAndAuditsAllow() {
        float[] embedding = new float[] {0.1f, 0.2f};
        ChunkSearchResult hit = new ChunkSearchResult(
            UUID.randomUUID(),
            DOCUMENT_ID,
            "faq.md",
            "chunk#1",
            "报销需要提前提交申请。",
            0.92
        );
        when(accessControlService.canReadKnowledgeBase(USER, KNOWLEDGE_BASE_ID)).thenReturn(true);
        when(embeddingProvider.embed(List.of("报销规则？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5, KNOWLEDGE_BASE_ID))
            .thenReturn(List.of(hit));

        AgentToolExecutionResponse response = service.execute(USER, request(call(
            "search_knowledge",
            object("query", "报销规则？", "knowledgeBaseId", KNOWLEDGE_BASE_ID.toString())
        )));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.failureReason()).isNull();
            assertThat(result.data().path("found").asBoolean()).isTrue();
            assertThat(result.data().path("sources")).hasSize(1);
        });
        verify(auditService).recordAllowed(
            eq(USER),
            eq("AGENT_TOOL_SEARCH_KNOWLEDGE"),
            eq("AGENT_TOOL"),
            isNull(),
            eq("TOOL_EXECUTED")
        );
    }

    @Test
    void refusesCrossTenantOrUnreadableKnowledgeBaseBeforeEmbedding() {
        when(accessControlService.canReadKnowledgeBase(USER, KNOWLEDGE_BASE_ID)).thenReturn(false);

        AgentToolExecutionResponse response = service.execute(USER, request(call(
            "search_knowledge",
            object("query", "不应该被检索", "knowledgeBaseId", KNOWLEDGE_BASE_ID.toString())
        )));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).isEqualTo(AgentToolFailureReason.PERMISSION_DENIED);
        });
        verify(embeddingProvider, never()).embed(any());
        verify(auditService).recordDenied(
            eq(USER),
            eq("AGENT_TOOL_SEARCH_KNOWLEDGE"),
            eq("AGENT_TOOL"),
            isNull(),
            eq("PERMISSION_DENIED")
        );
    }

    @Test
    void treatsEmptySearchAsAValidNoResultAndRejectsUnknownArguments() {
        float[] embedding = new float[] {0.1f, 0.2f};
        when(embeddingProvider.embed(List.of("没有命中？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5, null)).thenReturn(List.of());

        AgentToolExecutionResponse emptyResponse = service.execute(USER, request(call(
            "search_knowledge",
            object("query", "没有命中？")
        )));
        assertThat(emptyResponse.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.data().path("found").asBoolean()).isFalse();
            assertThat(result.data().path("sources")).isEmpty();
        });

        AgentToolExecutionResponse invalidResponse = service.execute(USER, request(call(
            "search_knowledge",
            object("query", "合法查询", "tenantId", TENANT_ID.toString())
        )));
        assertThat(invalidResponse.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).isEqualTo(AgentToolFailureReason.INVALID_ARGUMENTS);
        });
        verify(embeddingProvider).embed(List.of("没有命中？"));
    }

    @Test
    void classifiesEmbeddingTimeoutAsToolTimeout() {
        when(embeddingProvider.embed(List.of("会超时吗？")))
            .thenThrow(AiCallException.timeout("embedding timeout", new RuntimeException("timeout")));

        AgentToolExecutionResponse response = service.execute(USER, request(call(
            "search_knowledge",
            object("query", "会超时吗？")
        )));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.failureReason()).isEqualTo(AgentToolFailureReason.TIMEOUT);
        });
        verify(auditService).recordError(
            eq(USER),
            eq("AGENT_TOOL_SEARCH_KNOWLEDGE"),
            eq("AGENT_TOOL"),
            isNull(),
            eq("TIMEOUT")
        );
    }

    @Test
    void listsAccessibleDocumentsWithStatusAndKnowledgeBaseFilters() {
        DocumentResponse visible = document(DOCUMENT_ID, KNOWLEDGE_BASE_ID, "ready");
        DocumentResponse otherStatus = document(
            UUID.randomUUID(),
            KNOWLEDGE_BASE_ID,
            "failed"
        );
        when(accessControlService.canReadKnowledgeBase(USER, KNOWLEDGE_BASE_ID)).thenReturn(true);
        when(documentService.list(USER)).thenReturn(new DocumentListResponse(List.of(visible, otherStatus)));

        AgentToolExecutionResponse response = service.execute(USER, request(call(
            "list_documents",
            object("status", "ready", "knowledgeBaseId", KNOWLEDGE_BASE_ID.toString())
        )));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.data().path("items")).hasSize(1);
            assertThat(result.data().path("items").path(0).path("documentId").asText())
                .isEqualTo(DOCUMENT_ID.toString());
        });
        verify(documentService).list(USER);
    }

    @Test
    void readsDocumentStatusWithoutExposingFileContent() {
        DocumentResponse visible = document(DOCUMENT_ID, KNOWLEDGE_BASE_ID, "processing");
        when(documentService.get(USER, DOCUMENT_ID)).thenReturn(visible);

        AgentToolExecutionResponse response = service.execute(USER, request(call(
            "get_document_status",
            object("documentId", DOCUMENT_ID.toString())
        )));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.data().path("status").asText()).isEqualTo("processing");
            assertThat(result.data().has("filePath")).isFalse();
        });
        verify(documentService).get(USER, DOCUMENT_ID);
    }

    @Test
    void turnsUnreadableDocumentAndUnknownToolIntoStableFailures() {
        when(documentService.get(USER, DOCUMENT_ID)).thenThrow(new BusinessException(404, "文档不存在"));

        AgentToolExecutionResponse denied = service.execute(USER, request(call(
            "get_document_status",
            object("documentId", DOCUMENT_ID.toString())
        )));
        assertThat(denied.results()).singleElement()
            .extracting(AgentToolCallResponse::failureReason)
            .isEqualTo(AgentToolFailureReason.PERMISSION_DENIED);

        AgentToolExecutionResponse unknown = service.execute(USER, request(call(
            "delete_document",
            object("documentId", DOCUMENT_ID.toString())
        )));
        assertThat(unknown.results()).singleElement()
            .extracting(AgentToolCallResponse::failureReason)
            .isEqualTo(AgentToolFailureReason.TOOL_NOT_FOUND);
        verify(documentService, never()).delete(any(), any());
    }

    @Test
    void enforcesThreeCallBudget() {
        AgentToolCall call = call("search_knowledge", object("query", "q"));
        AgentToolExecutionRequest request = new AgentToolExecutionRequest(List.of(call, call, call, call));

        assertThatThrownBy(() -> service.execute(USER, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("单次最多调用 3 个只读工具")
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(400);
    }

    private AgentToolExecutionRequest request(AgentToolCall call) {
        return new AgentToolExecutionRequest(List.of(call));
    }

    private AgentToolCall call(String name, ObjectNode arguments) {
        return new AgentToolCall(name, arguments);
    }

    private ObjectNode object(String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    private ObjectNode object(String firstKey, String firstValue, String secondKey, String secondValue) {
        ObjectNode node = object(firstKey, firstValue);
        node.put(secondKey, secondValue);
        return node;
    }

    private DocumentResponse document(UUID documentId, UUID knowledgeBaseId, String status) {
        return new DocumentResponse(
            documentId,
            knowledgeBaseId,
            "document.md",
            "md",
            status,
            2,
            null,
            "checksum",
            1,
            1,
            false,
            OffsetDateTime.now()
        );
    }

    private AppProperties properties() {
        return new AppProperties(
            null,
            null,
            new AppProperties.Rag(700, 100, 5, 0.35, 768, false, 50, 2.0, 60),
            null,
            null,
            null
        );
    }
}
