package com.example.ragknowledgebase.ask;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AskLogRepository extends JpaRepository<AskLog, UUID> {
    long countByUserIdAndCreatedAtAfter(UUID userId, OffsetDateTime createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AskLog> findByRequestIdAndTenantIdAndUserId(UUID requestId, UUID tenantId, UUID userId);
}
