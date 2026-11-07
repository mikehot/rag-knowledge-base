package com.example.ragknowledgebase.ask;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AskFeedbackRepository extends JpaRepository<AskFeedback, UUID> {
    Optional<AskFeedback> findByAskLogId(UUID askLogId);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AskFeedback f WHERE f.askLogId IN (SELECT a.id FROM AskLog a WHERE a.createdAt < :cutoff)")
    int deleteByAskLogCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
