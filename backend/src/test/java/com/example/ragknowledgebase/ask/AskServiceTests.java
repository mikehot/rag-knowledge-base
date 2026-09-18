package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.ai.AiCallException;
import com.example.ragknowledgebase.ai.AiProvider;
import com.example.ragknowledgebase.ai.AiProviderResponse;
import com.example.ragknowledgebase.ai.EmbeddingProvider;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.document.ChunkJdbcRepository;
import com.example.ragknowledgebase.document.ChunkSearchResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AskServiceTests {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, TENANT_ID, "demo");

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private ChunkJdbcRepository chunkRepository;

    @Mock
    private AiProvider aiProvider;

    @Mock
    private AskLogRepository askLogRepository;

    private AskService askService;

    @BeforeEach
    void setUp() {
        askService = new AskService(
            properties(0),
            embeddingProvider,
            chunkRepository,
            aiProvider,
            askLogRepository
        );
    }

    @Test
    void returnsGroundedAnswerAndSourcesForRelevantHit() {
        float[] embedding = new float[] {0.1f, 0.2f};
        ChunkSearchResult hit = new ChunkSearchResult(
            UUID.randomUUID(),
            DOCUMENT_ID,
            "employee-handbook.md",
            "chunk#3",
            "年假需要提前三个工作日提交申请。",
            0.91
        );
        when(embeddingProvider.embed(List.of("年假怎么申请？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5)).thenReturn(List.of(hit));
        when(aiProvider.generate(anyString())).thenReturn(new AiProviderResponse("请提前三个工作日提交申请。", 128));

        AskResponse response = askService.ask(USER, new AskRequest("  年假怎么申请？  "));

        assertThat(response.found()).isTrue();
        assertThat(response.answer()).isEqualTo("请提前三个工作日提交申请。");
        assertThat(response.tokenUsage()).isEqualTo(128);
        assertThat(response.requestId()).isNotNull();
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.failureReason()).isNull();
        assertThat(response.timings().embeddingMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.timings().retrievalMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.timings().generationMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(source.filename()).isEqualTo("employee-handbook.md");
            assertThat(source.locator()).isEqualTo("chunk#3");
            assertThat(source.snippet()).isEqualTo("年假需要提前三个工作日提交申请。");
        });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiProvider).generate(prompt.capture());
        assertThat(prompt.getValue())
            .contains("年假怎么申请？")
            .contains("employee-handbook.md")
            .contains("年假需要提前三个工作日提交申请。");
        ArgumentCaptor<AskLog> log = ArgumentCaptor.forClass(AskLog.class);
        verify(askLogRepository).save(log.capture());
        assertThat(log.getValue().getRequestId()).isEqualTo(response.requestId());
        assertThat(log.getValue().getResultStatus()).isEqualTo(AskResultStatus.ANSWERED);
        assertThat(log.getValue().getFailureReason()).isNull();
        assertThat(log.getValue().getLatencyMs()).isEqualTo(response.latencyMs());
    }

    @Test
    void returnsHandoffWithoutCallingModelWhenSimilarityIsTooLow() {
        float[] embedding = new float[] {0.3f, 0.4f};
        ChunkSearchResult hit = new ChunkSearchResult(
            UUID.randomUUID(),
            DOCUMENT_ID,
            "faq.md",
            "chunk#1",
            "无关片段",
            0.34
        );
        when(embeddingProvider.embed(List.of("今天天气如何？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5)).thenReturn(List.of(hit));

        AskResponse response = askService.ask(USER, new AskRequest("今天天气如何？"));

        assertThat(response.found()).isFalse();
        assertThat(response.answer()).isEqualTo("未找到相关信息，建议转人工。");
        assertThat(response.sources()).isEmpty();
        assertThat(response.tokenUsage()).isZero();
        assertThat(response.failureReason()).isEqualTo(AskFailureReason.RETRIEVAL_MISS);
        verify(aiProvider, never()).generate(anyString());
        verify(askLogRepository).save(any(AskLog.class));
    }

    @Test
    void returnsHandoffWithoutSourcesWhenModelSaysRetrievedContextIsInsufficient() {
        float[] embedding = new float[] {0.5f, 0.6f};
        ChunkSearchResult hit = new ChunkSearchResult(
            UUID.randomUUID(),
            DOCUMENT_ID,
            "faq.md",
            "chunk#2",
            "智能门锁常见问题",
            0.72
        );
        when(embeddingProvider.embed(List.of("上海明天的天气？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5)).thenReturn(List.of(hit));
        when(aiProvider.generate(anyString()))
            .thenReturn(new AiProviderResponse("未找到相关信息，建议转人工。", 96));

        AskResponse response = askService.ask(USER, new AskRequest("上海明天的天气？"));

        assertThat(response.found()).isFalse();
        assertThat(response.answer()).isEqualTo("未找到相关信息，建议转人工。");
        assertThat(response.sources()).isEmpty();
        assertThat(response.tokenUsage()).isEqualTo(96);
        assertThat(response.failureReason()).isEqualTo(AskFailureReason.INSUFFICIENT_CONTEXT);
        verify(askLogRepository).save(any(AskLog.class));
    }

    @Test
    void returnsTemporaryFallbackAndRecordsAttemptWhenProviderFails() {
        when(embeddingProvider.embed(List.of("报销规则是什么？")))
            .thenThrow(new AiCallException("embedding timeout"));

        AskResponse response = askService.ask(USER, new AskRequest("报销规则是什么？"));

        assertThat(response.found()).isFalse();
        assertThat(response.answer()).isEqualTo("暂时无法检索资料，建议转人工。");
        assertThat(response.sources()).isEmpty();
        assertThat(response.failureReason()).isEqualTo(AskFailureReason.EMBEDDING_ERROR);
        assertThat(response.timings().embeddingMs()).isGreaterThanOrEqualTo(0);
        verify(chunkRepository, never()).search(any(), any(), any(), any(Integer.class));
        verify(aiProvider, never()).generate(anyString());
        verify(askLogRepository).save(any(AskLog.class));
    }

    @Test
    void classifiesRetrievalFailureWithoutCallingModel() {
        float[] embedding = new float[] {0.1f, 0.2f};
        when(embeddingProvider.embed(List.of("检索会失败吗？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5))
            .thenThrow(new IllegalStateException("database unavailable"));

        AskResponse response = askService.ask(USER, new AskRequest("检索会失败吗？"));

        assertThat(response.failureReason()).isEqualTo(AskFailureReason.RETRIEVAL_ERROR);
        assertThat(response.found()).isFalse();
        verify(aiProvider, never()).generate(anyString());
    }

    @Test
    void classifiesGenerationFailureAfterSuccessfulRetrieval() {
        float[] embedding = new float[] {0.1f, 0.2f};
        ChunkSearchResult hit = new ChunkSearchResult(
            UUID.randomUUID(),
            DOCUMENT_ID,
            "faq.md",
            "chunk#1",
            "有效资料",
            0.91
        );
        when(embeddingProvider.embed(List.of("模型会失败吗？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5)).thenReturn(List.of(hit));
        when(aiProvider.generate(anyString())).thenThrow(new AiCallException("provider timeout"));

        AskResponse response = askService.ask(USER, new AskRequest("模型会失败吗？"));

        assertThat(response.failureReason()).isEqualTo(AskFailureReason.GENERATION_ERROR);
        assertThat(response.found()).isFalse();
        assertThat(response.timings().embeddingMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.timings().retrievalMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void doesNotMisclassifyAskLogStorageFailureAsProviderFailure() {
        float[] embedding = new float[] {0.1f, 0.2f};
        ChunkSearchResult hit = new ChunkSearchResult(
            UUID.randomUUID(),
            DOCUMENT_ID,
            "faq.md",
            "chunk#1",
            "有效资料",
            0.91
        );
        when(embeddingProvider.embed(List.of("日志会失败吗？"))).thenReturn(List.of(embedding));
        when(chunkRepository.search(TENANT_ID, USER_ID, embedding, 5)).thenReturn(List.of(hit));
        when(aiProvider.generate(anyString())).thenReturn(new AiProviderResponse("资料回答", 42));
        when(askLogRepository.save(any(AskLog.class))).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> askService.ask(USER, new AskRequest("日志会失败吗？")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("database unavailable");

        verify(askLogRepository, times(1)).save(any(AskLog.class));
    }

    @Test
    void rejectsRequestBeforeProviderCallsWhenDailyLimitIsReached() {
        askService = new AskService(
            properties(2),
            embeddingProvider,
            chunkRepository,
            aiProvider,
            askLogRepository
        );
        when(askLogRepository.countByUserIdAndCreatedAtAfter(any(UUID.class), any(OffsetDateTime.class)))
            .thenReturn(2L);

        assertThatThrownBy(() -> askService.ask(USER, new AskRequest("还能提问吗？")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("今日提问次数已达上限，请明天再试")
            .extracting(ex -> ((BusinessException) ex).code())
            .isEqualTo(429);

        verify(embeddingProvider, never()).embed(any());
        verify(chunkRepository, never()).search(any(), any(), any(), any(Integer.class));
        verify(aiProvider, never()).generate(anyString());
    }

    private AppProperties properties(int dailyLimit) {
        return new AppProperties(
            null,
            null,
            new AppProperties.Rag(700, 100, 5, 0.35, 768),
            null,
            new AppProperties.Ai("openai-compatible", "", "", "", 1200, 120, 1, dailyLimit),
            null
        );
    }
}
