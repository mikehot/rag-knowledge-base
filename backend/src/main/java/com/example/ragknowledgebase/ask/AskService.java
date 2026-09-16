package com.example.ragknowledgebase.ask;

import com.example.ragknowledgebase.ai.AiProvider;
import com.example.ragknowledgebase.ai.AiProviderResponse;
import com.example.ragknowledgebase.ai.EmbeddingProvider;
import com.example.ragknowledgebase.auth.AuthenticatedUser;
import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import com.example.ragknowledgebase.document.ChunkJdbcRepository;
import com.example.ragknowledgebase.document.ChunkSearchResult;
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

    public AskService(
        AppProperties properties,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository,
        AiProvider aiProvider,
        AskLogRepository askLogRepository
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.aiProvider = aiProvider;
        this.askLogRepository = askLogRepository;
    }

    @Transactional
    public AskResponse ask(AuthenticatedUser user, AskRequest request) {
        String question = request.question().trim();
        enforceDailyLimit(user.userId());
        try {
            List<float[]> questionEmbeddings = embeddingProvider.embed(List.of(question));
            List<ChunkSearchResult> hits = chunkRepository.search(
                user.tenantId(),
                user.userId(),
                questionEmbeddings.get(0),
                properties.rag().topK()
            );
            if (hits.isEmpty() || hits.get(0).similarity() < properties.rag().similarityThreshold()) {
                return record(user.userId(), question, new AskResponse(HANDOFF, false, List.of(), 0));
            }
            String prompt = buildPrompt(question, hits);
            AiProviderResponse response = aiProvider.generate(prompt);
            if (isHandoff(response.text())) {
                return record(
                    user.userId(),
                    question,
                    new AskResponse(HANDOFF, false, List.of(), response.tokenUsage())
                );
            }
            AskResponse answer = new AskResponse(
                response.text(),
                true,
                hits.stream().map(this::sourceOf).toList(),
                response.tokenUsage()
            );
            return record(user.userId(), question, answer);
        } catch (Exception ex) {
            return record(user.userId(), question, new AskResponse(TEMPORARY_UNAVAILABLE, false, List.of(), 0));
        }
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

    private AskResponse record(UUID userId, String question, AskResponse response) {
        askLogRepository.save(new AskLog(UUID.randomUUID(), userId, question, response.found(), response.tokenUsage()));
        return response;
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
}
