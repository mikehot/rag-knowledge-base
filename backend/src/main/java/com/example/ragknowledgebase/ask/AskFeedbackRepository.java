package com.example.ragknowledgebase.ask;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AskFeedbackRepository extends JpaRepository<AskFeedback, UUID> {
    Optional<AskFeedback> findByAskLogId(UUID askLogId);
}
