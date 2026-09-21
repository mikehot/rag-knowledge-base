ALTER TABLE ask_log
    ADD COLUMN retrieval_mode VARCHAR(32) NOT NULL DEFAULT 'VECTOR';

ALTER TABLE ask_log
    ADD CONSTRAINT ck_ask_log_retrieval_mode
    CHECK (retrieval_mode IN ('VECTOR', 'KEYWORD_RRF'));

CREATE INDEX idx_ask_log_tenant_retrieval_mode
    ON ask_log(tenant_id, retrieval_mode, created_at DESC);
