package com.example.ragknowledgebase.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragknowledgebase.ask.AskFailureReason;
import com.example.ragknowledgebase.ask.AskResponse;
import com.example.ragknowledgebase.ask.AskResultStatus;
import com.example.ragknowledgebase.ask.AskTimingsResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationalMetricsTests {
    @Test
    void recordsOnlyLowCardinalityAskAndIndexDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationalMetrics metrics = new OperationalMetrics(registry);
        AskResponse response = new AskResponse(
            "暂时无法检索资料，建议转人工。",
            false,
            List.of(),
            UUID.randomUUID(),
            125,
            32,
            AskFailureReason.GENERATION_ERROR,
            new AskTimingsResponse(10, 15, 100)
        );

        metrics.recordAsk(response, AskResultStatus.FAILED);
        metrics.recordIndexTask("retry_scheduled", 250);

        assertThat(registry.get("rag.ask.requests")
            .tags("result", "failed", "failure", "generation_error")
            .counter()
            .count()).isEqualTo(1);
        assertThat(registry.get("rag.ask.tokens").counter().count()).isEqualTo(32);
        assertThat(registry.get("rag.ask.duration").tag("result", "failed").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.ask.stage.duration").timers()).hasSize(3);
        assertThat(registry.get("rag.index.tasks").tag("result", "retry_scheduled").counter().count())
            .isEqualTo(1);
        assertThat(registry.get("rag.index.duration").tag("result", "retry_scheduled").timer().count())
            .isEqualTo(1);
        assertThat(registry.getMeters())
            .allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("result", "failure", "stage")));
    }
}
