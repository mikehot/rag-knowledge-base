package com.example.ragknowledgebase.observability;

import com.example.ragknowledgebase.ask.AskResponse;
import com.example.ragknowledgebase.ask.AskFeedbackRating;
import com.example.ragknowledgebase.ask.AskResultStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OperationalMetrics {
    private final MeterRegistry registry;
    private final double estimatedCostPer1kTokens;

    public OperationalMetrics(
        MeterRegistry registry,
        @Value("${app.ai.estimated-cost-per-1k-tokens:0}") double estimatedCostPer1kTokens
    ) {
        this.registry = registry;
        this.estimatedCostPer1kTokens = Math.max(0, estimatedCostPer1kTokens);
    }

    public void recordAsk(AskResponse response, AskResultStatus resultStatus) {
        String result = normalized(resultStatus.name());
        String failure = response.failureReason() == null
            ? "none"
            : normalized(response.failureReason().name());

        Counter.builder("rag.ask.requests")
            .description("Completed RAG ask requests")
            .tag("result", result)
            .tag("failure", failure)
            .register(registry)
            .increment();
        Timer.builder("rag.ask.duration")
            .description("End-to-end RAG ask duration")
            .tag("result", result)
            .register(registry)
            .record(Duration.ofMillis(response.latencyMs()));
        recordStage("embedding", response.timings().embeddingMs());
        recordStage("retrieval", response.timings().retrievalMs());
        recordStage("generation", response.timings().generationMs());
        if (response.tokenUsage() > 0) {
            Counter.builder("rag.ask.tokens")
                .description("Model tokens reported by the configured provider")
                .register(registry)
                .increment(response.tokenUsage());
            Counter.builder("rag.ask.estimated.cost")
                .description("Estimated model cost in the configured currency unit")
                .register(registry)
                .increment(response.tokenUsage() / 1000d * estimatedCostPer1kTokens);
        }
    }

    public void recordFeedback(AskFeedbackRating rating) {
        Counter.builder("rag.ask.feedback")
            .description("Submitted answer feedback")
            .tag("rating", normalized(rating.name()))
            .register(registry)
            .increment();
    }

    public void recordAclDenied(String action, String resourceType) {
        Counter.builder("rag.acl.denied")
            .description("Durably recorded ACL denials")
            .tag("action", normalized(action))
            .tag("resource", normalized(resourceType))
            .register(registry)
            .increment();
    }

    public void recordIndexTask(String result, long durationMs) {
        String normalizedResult = normalized(result);
        Counter.builder("rag.index.tasks")
            .description("Completed indexing task attempts")
            .tag("result", normalizedResult)
            .register(registry)
            .increment();
        Timer.builder("rag.index.duration")
            .description("Indexing task attempt duration")
            .tag("result", normalizedResult)
            .register(registry)
            .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    private void recordStage(String stage, long durationMs) {
        Timer.builder("rag.ask.stage.duration")
            .description("RAG ask stage duration")
            .tag("stage", stage)
            .register(registry)
            .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
