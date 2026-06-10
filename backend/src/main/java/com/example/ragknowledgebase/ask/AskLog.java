package com.example.ragknowledgebase.ask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ask_log")
public class AskLog {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 2000)
    private String question;

    @Column(nullable = false)
    private boolean found;

    @Column(name = "token_usage")
    private int tokenUsage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AskLog() {
    }

    public AskLog(UUID id, UUID userId, String question, boolean found, int tokenUsage) {
        this.id = id;
        this.userId = userId;
        this.question = question;
        this.found = found;
        this.tokenUsage = tokenUsage;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
