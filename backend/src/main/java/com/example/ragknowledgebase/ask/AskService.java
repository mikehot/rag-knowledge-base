package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.ai.AiProvider;
import com.example.ragknowledgebase.ai.AiProviderResponse;
import com.example.ragknowledgebase.ai.AiCallException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AskService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AskService.class);
    private static final String HANDOFF = "未找到相关信息，建议转人工。";
    private static final String TEMPORARY_UNAVAILABLE = "暂时无法检索资料，建议转人工。";
    private static final String STRUCTURED_OUTPUT_FALLBACK = "暂时无法生成可验证的回答，建议转人工。";
    private static final String CITATION_FALLBACK = "回答缺少可验证引用，建议转人工。";
    private static final List<String> INCOMPLETE_ANSWER_MARKERS = List.of(
        "资料中未提及",
        "资料未提及",
        "资料中没有说明",
        "无法确认",
        "无法判断",
        "无法确定"
    );

    private final AppProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkJdbcRepository chunkRepository;
    private final AiProvider aiProvider;
    private final AskLogRepository askLogRepository;
    private final AskRetrievalHitRepository retrievalHitRepository;
    private final OperationalMetrics operationalMetrics;
    private final QuestionComplexityClassifier questionComplexityClassifier;
    private final KeywordRrfRetriever keywordRrfRetriever;
    private final DocumentDiversityRetriever documentDiversityRetriever;
    private final AdjacentChunkRetriever adjacentChunkRetriever;

    public AskService(
        AppProperties properties,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository,
        AiProvider aiProvider,
        AskLogRepository askLogRepository,
        AskRetrievalHitRepository retrievalHitRepository,
        OperationalMetrics operationalMetrics
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.aiProvider = aiProvider;
        this.askLogRepository = askLogRepository;
        this.retrievalHitRepository = retrievalHitRepository;
        this.operationalMetrics = operationalMetrics;
        this.questionComplexityClassifier = new QuestionComplexityClassifier();
        this.keywordRrfRetriever = new KeywordRrfRetriever();
        this.documentDiversityRetriever = new DocumentDiversityRetriever();
        this.adjacentChunkRetriever = new AdjacentChunkRetriever();
    }

    @Transactional
    public AskResponse ask(AuthenticatedUser user, AskRequest request) {
        return ask(user, request, RetrievalMode.VECTOR);
    }

    @Transactional
    public AskResponse ask(AuthenticatedUser user, AskRequest request, RetrievalMode retrievalMode) {
        if ((retrievalMode == RetrievalMode.KEYWORD_RRF || retrievalMode == RetrievalMode.VECTOR_DIVERSITY)
            && !properties.rag().hybridExperimentEnabled()) {
            throw new BusinessException(400, "检索实验未启用");
        }
        if (retrievalMode == RetrievalMode.VECTOR_ADJACENT
            && !properties.rag().contextSelectionExperimentEnabled()) {
            throw new BusinessException(400, "上下文选择实验未启用");
        }
        String question = request.question().trim();
        enforceDailyLimit(user.userId());
        UUID requestId = RequestIdContext.currentOrNew();
        long totalStarted = System.nanoTime();
        long embeddingMs = 0;
        long retrievalMs = 0;
        long generationMs = 0;
        List<ChunkSearchResult> retrievalHits = List.of();
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
            int vectorCandidateK = switch (retrievalMode) {
                case VECTOR_DIVERSITY -> Math.max(properties.rag().topK(), properties.rag().hybridCandidateK());
                case VECTOR_ADJACENT -> properties.rag().topK() + 2;
                default -> properties.rag().topK();
            };
            List<ChunkSearchResult> vectorHits = chunkRepository.search(
                user.tenantId(),
                user.userId(),
                questionEmbeddings.get(0),
                vectorCandidateK
            );
            int hybridCandidateK = Math.max(properties.rag().topK(), properties.rag().hybridCandidateK());
            List<ChunkSearchResult> hits = switch (retrievalMode) {
                case VECTOR -> vectorHits;
                case VECTOR_DIVERSITY -> documentDiversityRetriever.select(vectorHits, properties.rag().topK());
                case VECTOR_ADJACENT -> adjacentChunkRetriever.select(vectorHits, properties.rag().topK());
                case KEYWORD_RRF -> keywordRrfRetriever.retrieve(
                    question,
                    vectorHits,
                    chunkRepository.findVisibleReadyChunks(user.tenantId(), user.userId(), hybridCandidateK),
                    properties.rag().topK(),
                    hybridCandidateK,
                    properties.rag().hybridKeywordWeight(),
                    properties.rag().hybridRrfK()
                );
            };
            retrievalHits = hits;
            retrievalMs = elapsedMs(stageStarted);
            boolean retrievalMiss = hits.isEmpty()
                || (retrievalMode != RetrievalMode.KEYWORD_RRF
                    && hits.get(0).similarity() < properties.rag().similarityThreshold());
            if (retrievalMiss) {
                response = response(
                    requestId,
                    totalStarted,
                    HANDOFF,
                    false,
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
                AiProviderResponse providerResponse = generate(prompt, question);
                generationMs = elapsedMs(stageStarted);
                int tokenUsage = providerResponse.tokenUsage();
                StructuredAnswer structuredAnswer;
                try {
                    structuredAnswer = parseProviderAnswer(providerResponse);
                } catch (StructuredOutputException ex) {
                    logStructuredOutputFailure(requestId, 1, providerResponse);
                    StructuredOutputRetryResult retryResult = retryStructuredOutput(
                        requestId,
                        prompt,
                        question,
                        providerResponse,
                        generationMs
                    );
                    generationMs = retryResult.generationMs();
                    tokenUsage = retryResult.tokenUsage();
                    structuredAnswer = retryResult.answer();
                    if (structuredAnswer == null) {
                        response = response(
                            requestId,
                            totalStarted,
                            STRUCTURED_OUTPUT_FALLBACK,
                            false,
                            false,
                            List.of(),
                            tokenUsage,
                            AskFailureReason.STRUCTURED_OUTPUT_INVALID,
                            embeddingMs,
                            retrievalMs,
                            generationMs
                        );
                        resultStatus = AskResultStatus.FAILED;
                        return record(user, question, response, resultStatus, retrievalHits, retrievalMode);
                    }
                }
                if (!structuredAnswer.found()) {
                    response = response(
                        requestId,
                        totalStarted,
                        HANDOFF,
                        false,
                        false,
                        List.of(),
                        tokenUsage,
                        AskFailureReason.INSUFFICIENT_CONTEXT,
                        embeddingMs,
                        retrievalMs,
                        generationMs
                    );
                    resultStatus = AskResultStatus.NOT_FOUND;
                } else if (!hasUsableAnswer(structuredAnswer)) {
                    response = response(
                        requestId,
                        totalStarted,
                        HANDOFF,
                        false,
                        false,
                        List.of(),
                        tokenUsage,
                        AskFailureReason.INSUFFICIENT_CONTEXT,
                        embeddingMs,
                        retrievalMs,
                        generationMs
                    );
                    resultStatus = AskResultStatus.NOT_FOUND;
                } else if (!hasValidCitations(structuredAnswer, hits)) {
                    response = response(
                        requestId,
                        totalStarted,
                        CITATION_FALLBACK,
                        false,
                        false,
                        List.of(),
                        tokenUsage,
                        AskFailureReason.CITATION_MISSING,
                        embeddingMs,
                        retrievalMs,
                        generationMs
                    );
                    resultStatus = AskResultStatus.FAILED;
                } else {
                    response = response(
                        requestId,
                        totalStarted,
                        structuredAnswer.answer(),
                        true,
                        structuredAnswer.grounded(),
                        sourcesOf(hits, structuredAnswer.sourceIndexes()),
                        tokenUsage,
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
                    yield AiCallException.isTimeout(ex)
                        ? AskFailureReason.EMBEDDING_TIMEOUT
                        : AskFailureReason.EMBEDDING_ERROR;
                }
                case RETRIEVAL -> {
                    retrievalMs = failedStageMs;
                    yield AskFailureReason.RETRIEVAL_ERROR;
                }
                case GENERATION -> {
                    generationMs = failedStageMs;
                    yield AiCallException.isTimeout(ex)
                        ? AskFailureReason.GENERATION_TIMEOUT
                        : AskFailureReason.GENERATION_ERROR;
                }
            };
            response = response(
                requestId,
                totalStarted,
                TEMPORARY_UNAVAILABLE,
                false,
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
        return record(user, question, response, resultStatus, retrievalHits, retrievalMode);
    }

    private AiProviderResponse generate(String prompt, String question) {
        if (!properties.ai().complexRoutingEnabled()
            || !questionComplexityClassifier.isComplex(question)) {
            return aiProvider.generate(prompt);
        }
        long complexMaxTokens = Math.max(
            properties.ai().maxTokens(),
            properties.ai().complexMaxTokens()
        );
        return aiProvider.generate(prompt, complexMaxTokens);
    }

    private StructuredOutputRetryResult retryStructuredOutput(
        UUID requestId,
        String prompt,
        String question,
        AiProviderResponse firstResponse,
        long initialGenerationMs
    ) {
        int tokenUsage = firstResponse.tokenUsage();
        long generationMs = initialGenerationMs;
        int retries = Math.max(0, properties.ai().structuredOutputRetries());
        StructuredOutputParser parser = new StructuredOutputParser();
        for (int retry = 0; retry < retries; retry++) {
            int attempt = retry + 2;
            long retryStarted = System.nanoTime();
            AiProviderResponse retryResponse = generate(
                prompt + "\n\n上一次输出未通过 JSON 校验。请重新输出同一个问题的答案，必须只包含一个严格 JSON 对象，字段只能是 answer、found、grounded、sourceIndexes；不要输出 Markdown、解释或代码围栏。回答必须覆盖用户问题中的每个子问题。",
                question
            );
            tokenUsage += retryResponse.tokenUsage();
            generationMs += elapsedMs(retryStarted);
            try {
                return new StructuredOutputRetryResult(
                    parseProviderAnswer(retryResponse, parser),
                    tokenUsage,
                    generationMs
                );
            } catch (StructuredOutputException ignored) {
                logStructuredOutputFailure(requestId, attempt, retryResponse);
                // Keep the bounded retry fail-closed; the caller returns a stable fallback.
            }
        }
        return new StructuredOutputRetryResult(null, tokenUsage, generationMs);
    }

    private StructuredAnswer parseProviderAnswer(AiProviderResponse response) {
        return parseProviderAnswer(response, new StructuredOutputParser());
    }

    private StructuredAnswer parseProviderAnswer(AiProviderResponse response, StructuredOutputParser parser) {
        String finishReason = response.finishReason();
        if (finishReason != null && !finishReason.equals("stop") && !finishReason.equals("unknown")) {
            throw new StructuredOutputException("Provider did not finish the answer normally");
        }
        return parser.parse(response.text());
    }

    private void logStructuredOutputFailure(UUID requestId, int attempt, AiProviderResponse response) {
        String output = response.text();
        LOGGER.warn(
            "Structured answer validation failed; requestId={}, attempt={}, finishReason={}, completionTokens={}, outputChars={}",
            requestId,
            attempt,
            response.finishReason(),
            response.completionTokens(),
            output == null ? 0 : output.length()
        );
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
        AskResultStatus resultStatus,
        List<ChunkSearchResult> retrievalHits,
        RetrievalMode retrievalMode
    ) {
        AskLog askLog = new AskLog(
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
            retrievalMode,
            properties.rag().topK(),
            properties.rag().similarityThreshold()
        );
        askLogRepository.save(askLog);
        askLogRepository.flush();
        retrievalHitRepository.saveAll(askLog.getId(), user.tenantId(), retrievalHits);
        operationalMetrics.recordAsk(response, resultStatus);
        return response;
    }

    private AskResponse response(
        UUID requestId,
        long totalStarted,
        String answer,
        boolean found,
        boolean grounded,
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
            grounded,
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
            .append("如果问题包含多个部分（例如‘以及’、‘分别’、‘同时’、‘综合’），请先在内部拆成编号清单，再按编号逐项回答。每一项必须给出资料中的具体动作、条件、数字或时间；不能用‘确认’、‘注意检查’等空泛短语代替具体检查项。回答结束前，逐项核对用户问题中的每个动作和对象都已经覆盖，资料中明确的数字、时间、条件或双方责任必须完整保留，不要只回答其中一半。\n\n")
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
            .append("\n\n请用中文简洁回答，并在末尾不要重复来源（来源由系统单独展示）。")
            .append("如果问题同时询问‘是否/能否/吗’、‘多久/多少’、‘由谁’等多个条件，answer 必须使用 1.、2.、3. 编号逐项回答；每个编号只负责一个条件，不能只给出其中一个结论。")
            .append("\n\n必须只输出一个 JSON 对象，不要输出 Markdown、解释文字或代码围栏。JSON 字段必须是：")
            .append("answer（字符串）、found（布尔值）、grounded（布尔值）、sourceIndexes（正整数数组）。")
            .append("sourceIndexes 只能引用上面资料片段的编号；found=false 时 answer 必须为未找到相关信息，grounded=false 且 sourceIndexes=[]。")
            .append("found=true 时必须 grounded=true、sourceIndexes 非空，并且回答中的每个重要结论都能被所引用资料支持。");
        return builder.toString();
    }

    private boolean hasValidCitations(StructuredAnswer answer, List<ChunkSearchResult> hits) {
        return answer.grounded()
            && !answer.sourceIndexes().isEmpty()
            && answer.sourceIndexes().stream().allMatch(index -> index >= 1 && index <= hits.size());
    }

    private boolean hasUsableAnswer(StructuredAnswer answer) {
        String normalized = answer.answer() == null ? "" : answer.answer().trim();
        return !normalized.isBlank()
            && !normalized.equals(HANDOFF)
            && !normalized.equals(TEMPORARY_UNAVAILABLE)
            && !normalized.equals(STRUCTURED_OUTPUT_FALLBACK)
            && !normalized.equals(CITATION_FALLBACK)
            && INCOMPLETE_ANSWER_MARKERS.stream().noneMatch(normalized::contains);
    }

    private List<AskSourceResponse> sourcesOf(List<ChunkSearchResult> hits, List<Integer> indexes) {
        return indexes.stream()
            .distinct()
            .map(index -> sourceOf(hits.get(index - 1)))
            .toList();
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

    private record StructuredOutputRetryResult(
        StructuredAnswer answer,
        int tokenUsage,
        long generationMs
    ) {
    }
}
