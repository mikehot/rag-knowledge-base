package com.example.ragknowledgebase.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "document")
public class KnowledgeDocument {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "source_id")
    private String sourceId;

    @Column
    private String checksum;

    @Column(name = "content_version", nullable = false)
    private int contentVersion = 1;

    @Column(name = "permission_version", nullable = false)
    private int permissionVersion = 1;

    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PROCESSING;

    @Column(name = "chunk_count")
    private int chunkCount;

    @Column(name = "error_msg")
    private String errorMsg;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(
        UUID id,
        UUID tenantId,
        UUID knowledgeBaseId,
        UUID userId,
        String filename,
        String fileType,
        String filePath
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.userId = userId;
        this.filename = filename;
        this.fileType = fileType;
        this.filePath = filePath;
    }

    public KnowledgeDocument(
        UUID id,
        UUID tenantId,
        UUID knowledgeBaseId,
        UUID userId,
        String filename,
        String fileType,
        String filePath,
        String checksum
    ) {
        this(id, tenantId, knowledgeBaseId, userId, filename, fileType, filePath);
        this.checksum = checksum;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getFilename() {
        return filename;
    }

    public String getFileType() {
        return fileType;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public int getPermissionVersion() {
        return permissionVersion;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDisabledAt() {
        return disabledAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void markReady(int chunkCount) {
        this.status = DocumentStatus.READY;
        this.chunkCount = chunkCount;
        this.errorMsg = null;
    }

    public void markFailed(String message) {
        this.status = DocumentStatus.FAILED;
        this.errorMsg = message;
        this.chunkCount = 0;
    }

    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
        this.errorMsg = null;
    }

    public DocumentContentSnapshot contentSnapshot() {
        return new DocumentContentSnapshot(
            filename,
            fileType,
            filePath,
            checksum,
            contentVersion,
            status,
            chunkCount,
            errorMsg
        );
    }

    public void replaceContent(String filename, String fileType, String filePath, String checksum) {
        this.filename = filename;
        this.fileType = fileType;
        this.filePath = filePath;
        this.checksum = checksum;
        this.contentVersion++;
        markProcessing();
    }

    public void restoreContent(DocumentContentSnapshot snapshot) {
        this.filename = snapshot.filename();
        this.fileType = snapshot.fileType();
        this.filePath = snapshot.filePath();
        this.checksum = snapshot.checksum();
        this.contentVersion = snapshot.contentVersion();
        this.status = snapshot.status();
        this.chunkCount = snapshot.chunkCount();
        this.errorMsg = snapshot.errorMsg();
    }

    public void disable() {
        this.disabledAt = OffsetDateTime.now();
        this.permissionVersion++;
    }

    public void enable() {
        this.disabledAt = null;
        this.permissionVersion++;
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
        this.permissionVersion++;
        this.chunkCount = 0;
    }

    public void bumpContentVersion() {
        this.contentVersion++;
    }
}
