package com.example.ragknowledgebase.ask;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AskLogRepository extends JpaRepository<AskLog, UUID> {
    long countByUserIdAndCreatedAtAfter(UUID userId, OffsetDateTime createdAt);
}
