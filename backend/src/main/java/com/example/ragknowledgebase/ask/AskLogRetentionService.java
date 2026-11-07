package com.example.ragknowledgebase.ask;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AskLogRetentionService {
    private static final Logger log = LoggerFactory.getLogger(AskLogRetentionService.class);

    private final AskLogRepository askLogRepository;
    private final AskFeedbackRepository askFeedbackRepository;
    private final int retentionDays;
    private final Clock clock;

    @Autowired
    public AskLogRetentionService(
        AskLogRepository askLogRepository,
        AskFeedbackRepository askFeedbackRepository,
        @Value("${app.ask-retention.retention-days:0}") int retentionDays
    ) {
        this(askLogRepository, askFeedbackRepository, retentionDays, Clock.systemUTC());
    }

    AskLogRetentionService(
        AskLogRepository askLogRepository,
        AskFeedbackRepository askFeedbackRepository,
        int retentionDays,
        Clock clock
    ) {
        this.askLogRepository = askLogRepository;
        this.askFeedbackRepository = askFeedbackRepository;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.ask-retention.poll-delay-ms:3600000}")
    @Transactional
    public int purgeExpired() {
        if (retentionDays <= 0) {
            return 0;
        }
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(retentionDays);
        return purgeBefore(cutoff);
    }

    @Transactional
    public int purgeBefore(OffsetDateTime cutoff) {
        askFeedbackRepository.deleteByAskLogCreatedAtBefore(cutoff);
        int deleted = askLogRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired ask_log rows created before {}", deleted, cutoff);
        }
        return deleted;
    }
}
