ALTER TABLE ask_log
    ADD COLUMN tenant_id UUID REFERENCES tenant(id),
    ADD COLUMN request_id UUID,
    ADD COLUMN result_status VARCHAR(32),
    ADD COLUMN failure_reason VARCHAR(64),
    ADD COLUMN latency_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN embedding_latency_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN retrieval_latency_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN generation_latency_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN model_id VARCHAR(255),
    ADD COLUMN top_k INTEGER,
    ADD COLUMN similarity_threshold DOUBLE PRECISION;

UPDATE ask_log log
SET tenant_id = app_user.tenant_id,
    request_id = log.id,
    result_status = CASE WHEN log.found THEN 'ANSWERED' ELSE 'NOT_FOUND' END
FROM app_user
WHERE app_user.id = log.user_id;

ALTER TABLE ask_log
    ALTER COLUMN tenant_id SET NOT NULL,
    ALTER COLUMN request_id SET NOT NULL,
    ALTER COLUMN result_status SET NOT NULL,
    ADD CONSTRAINT uq_ask_log_request_id UNIQUE (request_id),
    ADD CONSTRAINT ck_ask_log_result_status
        CHECK (result_status IN ('ANSWERED', 'NOT_FOUND', 'FAILED'));

CREATE INDEX idx_ask_log_tenant_created
    ON ask_log(tenant_id, created_at DESC);
