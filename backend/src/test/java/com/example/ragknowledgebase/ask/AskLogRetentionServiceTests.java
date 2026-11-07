package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AskLogRetentionServiceTests {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant FIXED_NOW = Instant.parse("2026-11-07T00:00:00Z");

    @Autowired
    private AskLogRepository askLogRepository;

    @Autowired
    private AskFeedbackRepository askFeedbackRepository;

    @BeforeEach
    void cleanUp() {
        askFeedbackRepository.deleteAll();
        askLogRepository.deleteAll();
    }

    @Test
    void doesNothingWhenRetentionDaysIsZeroOrNegative() {
        AskLog old = saveAskLog(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusDays(90));
        AskLogRetentionService disabled = new AskLogRetentionService(
            askLogRepository,
            askFeedbackRepository,
            0,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );

        int deleted = disabled.purgeExpired();

        assertThat(deleted).isZero();
        assertThat(askLogRepository.findById(old.getId())).isPresent();
    }

    @Test
    void purgesOnlyAskLogsAndFeedbackOlderThanRetentionCutoff() {
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        AskLog expired = saveAskLog(now.minusDays(31));
        AskLog recent = saveAskLog(now.minusDays(29));
        askFeedbackRepository.save(new AskFeedback(
            UUID.randomUUID(),
            expired.getId(),
            TENANT_ID,
            USER_ID,
            AskFeedbackRating.HELPFUL,
            "旧反馈"
        ));
        askFeedbackRepository.save(new AskFeedback(
            UUID.randomUUID(),
            recent.getId(),
            TENANT_ID,
            USER_ID,
            AskFeedbackRating.NOT_HELPFUL,
            "新反馈"
        ));

        AskLogRetentionService service = new AskLogRetentionService(
            askLogRepository,
            askFeedbackRepository,
            30,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );

        int deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(askLogRepository.findById(expired.getId())).isEmpty();
        assertThat(askFeedbackRepository.findByAskLogId(expired.getId())).isEmpty();
        assertThat(askLogRepository.findById(recent.getId())).isPresent();
        assertThat(askFeedbackRepository.findByAskLogId(recent.getId())).isPresent();
    }

    private AskLog saveAskLog(OffsetDateTime createdAt) {
        AskLog log = new AskLog(
            UUID.randomUUID(),
            TENANT_ID,
            UUID.randomUUID(),
            USER_ID,
            "测试问题",
            true,
            10,
            AskResultStatus.ANSWERED,
            null,
            100,
            10,
            20,
            70,
            "test-model",
            "openai-compatible",
            5,
            0.35
        );
        log.setCreatedAtForTesting(createdAt);
        return askLogRepository.save(log);
    }
}
