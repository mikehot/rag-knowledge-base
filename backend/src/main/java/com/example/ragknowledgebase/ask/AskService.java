package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.ai.AiProvider;
import com.example.ragknowledgebase.ai.AiProviderResponse;
import com.example.ragknowledgebase.ai.EmbeddingProvider;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.common.RequestIdContext;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.document.ChunkJdbcRepository;
import com.example.ragknowledgebase.document.ChunkSearchResult;
import com.example.ragknowledgebase.observability.OperationalMetrics;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AskService {
    private static final String HANDOFF = "未找到相关信息，建议转人工。";
    private static final String TEMPORARY_UNAVAILABLE = "暂时无法检索资料，建议转人工。";

    private final AppProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkJdbcRepository chunkRepository;
    private final AiProvider aiProvider;
    private final AskLogRepository askLogRepository;
    private final OperationalMetrics operationalMetrics;

    public AskService(
        AppProperties properties,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository,
        AiProvider aiProvider,
        AskLogRepository askLogRepository,
        OperationalMetrics operationalMetrics
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.aiProvider = aiProvider;
        this.askLogRepository = askLogRepository;
        this.operationalMetrics = operationalMetrics;
    }

    @Transactional
    public AskResponse ask(AuthenticatedUser user, AskRequest request) {
        String question = request.question().trim();
        enforceDailyLimit(user.userId());
        UUID requestId = RequestIdContext.currentOrNew();
        long totalStarted = System.nanoTime();
        long embeddingMs = 0;
        long retrievalMs = 0;
        long generationMs = 0;
        Stage stage = Stage.EMBEDDING;
        long stageStarted = System.nanoTime();
        AskResponse response;
        AskResultStatus resultStatus;
        try {
            List<float[]> questionEmbeddings = embeddingProvider.embed(List.of(question));
            embeddingMs = elapsedMs(stageStarted);
            if (questionEmbeddings.isEmpty()) {
                throw new IllegalStateException("Embedding 返回为空");
            }

            stage = Stage.RETRIEVAL;
            stageStarted = System.nanoTime();
            List<ChunkSearchResult> hits = chunkRepository.search(
                user.tenantId(),
                user.userId(),
                questionEmbeddings.get(0),
                properties.rag().topK()
            );
            retrievalMs = elapsedMs(stageStarted);
            if (hits.isEmpty() || hits.get(0).similarity() < properties.rag().similarityThreshold()) {
                response = response(
                    requestId,
                    totalStarted,
                    HANDOFF,
                    false,
                    List.of(),
                    0,
                    AskFailureReason.RETRIEVAL_MISS,
                    embeddingMs,
                    retrievalMs,
                    generationMs
                );
                resultStatus = AskResultStatus.NOT_FOUND;
            } else {
                String prompt = buildPrompt(question, hits);
                stage = Stage.GENERATION;
                stageStarted = System.nanoTime();
                AiProviderResponse providerResponse = aiProvider.generate(prompt);
                generationMs = elapsedMs(stageStarted);
                if (isHandoff(providerResponse.text())) {
                    response = response(
                        requestId,
                        totalStarted,
                        HANDOFF,
                        false,
                        List.of(),
                        providerResponse.tokenUsage(),
                        AskFailureReason.INSUFFICIENT_CONTEXT,
                        embeddingMs,
                        retrievalMs,
                        generationMs
                    );
                    resultStatus = AskResultStatus.NOT_FOUND;
                } else {
                    response = response(
                        requestId,
                        totalStarted,
                        providerResponse.text(),
                        true,
                        hits.stream().map(this::sourceOf).toList(),
                        providerResponse.tokenUsage(),
                        null,
                        embeddingMs,
                        retrievalMs,
                        generationMs
                    );
                    resultStatus = AskResultStatus.ANSWERED;
                }
            }
        } catch (Exception ex) {
            long failedStageMs = elapsedMs(stageStarted);
            AskFailureReason failureReason = switch (stage) {
                case EMBEDDING -> {
                    embeddingMs = failedStageMs;
                    yield AskFailureReason.EMBEDDING_ERROR;
                }
                case RETRIEVAL -> {
                    retrievalMs = failedStageMs;
                    yield AskFailureReason.RETRIEVAL_ERROR;
                }
                case GENERATION -> {
                    generationMs = failedStageMs;
                    yield AskFailureReason.GENERATION_ERROR;
                }
            };
            response = response(
                requestId,
                totalStarted,
                TEMPORARY_UNAVAILABLE,
                false,
                List.of(),
                0,
                failureReason,
                embeddingMs,
                retrievalMs,
                generationMs
            );
            resultStatus = AskResultStatus.FAILED;
        }
        return record(user, question, response, resultStatus);
    }

    private void enforceDailyLimit(UUID userId) {
        int dailyLimit = properties.ai().dailyLimit();
        if (dailyLimit <= 0) {
            return;
        }
        OffsetDateTime startOfDay = LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toOffsetDateTime();
        long used = askLogRepository.countByUserIdAndCreatedAtAfter(userId, startOfDay);
        if (used >= dailyLimit) {
            throw new BusinessException(429, "今日提问次数已达上限，请明天再试");
        }
    }

    private AskResponse record(
        AuthenticatedUser user,
        String question,
        AskResponse response,
        AskResultStatus resultStatus
    ) {
        askLogRepository.save(new AskLog(
            UUID.randomUUID(),
            user.tenantId(),
            response.requestId(),
            user.userId(),
            question,
            response.found(),
            response.tokenUsage(),
            resultStatus,
            response.failureReason(),
            response.latencyMs(),
            response.timings().embeddingMs(),
            response.timings().retrievalMs(),
            response.timings().generationMs(),
            properties.ai().modelId(),
            properties.ai().provider(),
            properties.rag().topK(),
            properties.rag().similarityThreshold()
        ));
        operationalMetrics.recordAsk(response, resultStatus);
        return response;
    }

    private AskResponse response(
        UUID requestId,
        long totalStarted,
        String answer,
        boolean found,
        List<AskSourceResponse> sources,
        int tokenUsage,
        AskFailureReason failureReason,
        long embeddingMs,
        long retrievalMs,
        long generationMs
    ) {
        return new AskResponse(
            answer,
            found,
            sources,
            requestId,
            elapsedMs(totalStarted),
            tokenUsage,
            failureReason,
            new AskTimingsResponse(embeddingMs, retrievalMs, generationMs)
        );
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String buildPrompt(String question, List<ChunkSearchResult> hits) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是知识库问答助手。只能根据下面提供的资料片段回答用户问题，不得编造。\n")
            .append("若资料不足以回答，请直接回复：\"未找到相关信息，建议转人工。\"\n\n")
            .append("【资料片段】\n");
        for (int i = 0; i < hits.size(); i++) {
            ChunkSearchResult hit = hits.get(i);
            builder.append("[")
                .append(i + 1)
                .append("] (来源：")
                .append(hit.filename())
                .append(" ")
                .append(hit.locator())
                .append(") ")
                .append(hit.content())
                .append("\n\n");
        }
        builder.append("【用户问题】\n")
            .append(question)
            .append("\n\n请用中文简洁回答，并在末尾不要重复来源（来源由系统单独展示）。");
        return builder.toString();
    }

    private boolean isHandoff(String answer) {
        return answer != null && answer.contains(HANDOFF);
    }

    private AskSourceResponse sourceOf(ChunkSearchResult hit) {
        return new AskSourceResponse(
            hit.documentId(),
            hit.filename(),
            hit.locator(),
            snippet(hit.content())
        );
    }

    private String snippet(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 360) {
            return compact;
        }
        return compact.substring(0, 360) + "...";
    }

    private enum Stage {
        EMBEDDING,
        RETRIEVAL,
        GENERATION
    }
}
