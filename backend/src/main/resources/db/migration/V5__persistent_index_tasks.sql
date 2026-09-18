CREATE TABLE index_task (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    requested_by UUID REFERENCES app_user(id),
    operation VARCHAR(32) NOT NULL,
    content_version INTEGER NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    error_msg VARCHAR(500),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    rollback_filename VARCHAR(255),
    rollback_file_type VARCHAR(32),
    rollback_file_path TEXT,
    rollback_checksum VARCHAR(128),
    rollback_content_version INTEGER,
    rollback_status VARCHAR(32),
    rollback_chunk_count INTEGER,
    rollback_error_msg VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_index_task_operation
        CHECK (operation IN ('UPLOAD', 'REINDEX', 'REPLACE')),
    CONSTRAINT ck_index_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_index_task_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0)
);

CREATE INDEX idx_index_task_claim
    ON index_task(status, next_attempt_at, created_at);

CREATE INDEX idx_index_task_tenant_kb_created
    ON index_task(tenant_id, knowledge_base_id, created_at DESC);

CREATE INDEX idx_index_task_document_created
    ON index_task(tenant_id, document_id, created_at DESC);
