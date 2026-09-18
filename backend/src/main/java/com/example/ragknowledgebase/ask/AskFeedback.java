package com.example.ragknowledgebase.ask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ask_feedback")
public class AskFeedback {
    @Id
    private UUID id;

    @Column(name = "ask_log_id", nullable = false, unique = true)
    private UUID askLogId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AskFeedbackRating rating;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AskFeedback() {
    }

    public AskFeedback(
        UUID id,
        UUID askLogId,
        UUID tenantId,
        UUID userId,
        AskFeedbackRating rating,
        String reason
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        this.id = id;
        this.askLogId = askLogId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.rating = rating;
        this.reason = reason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(AskFeedbackRating rating, String reason) {
        this.rating = rating;
        this.reason = reason;
        this.updatedAt = OffsetDateTime.now();
    }

    public AskFeedbackRating getRating() {
        return rating;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
