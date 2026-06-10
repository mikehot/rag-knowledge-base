package com.example.ragknowledgebase.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    List<KnowledgeDocument> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<KnowledgeDocument> findByIdAndUserId(UUID id, UUID userId);
}
