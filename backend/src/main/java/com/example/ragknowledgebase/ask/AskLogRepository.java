package com.example.ragknowledgebase.ask;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AskLogRepository extends JpaRepository<AskLog, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AskLog> findByRequestIdAndTenantIdAndUserId(UUID requestId, UUID tenantId, UUID userId);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AskLog a WHERE a.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
