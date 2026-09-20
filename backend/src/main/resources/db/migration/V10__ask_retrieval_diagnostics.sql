CREATE TABLE ask_retrieval_hit (
    id UUID PRIMARY KEY,
    ask_log_id UUID NOT NULL REFERENCES ask_log(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    rank INTEGER NOT NULL,
    -- These are immutable retrieval snapshots; reindexing may delete the live chunk.
    chunk_id UUID NOT NULL,
    document_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    locator TEXT,
    similarity DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ask_retrieval_hit_rank UNIQUE (ask_log_id, rank),
    CONSTRAINT ck_ask_retrieval_hit_rank CHECK (rank > 0),
    CONSTRAINT ck_ask_retrieval_hit_similarity CHECK (similarity >= -1 AND similarity <= 1)
);

CREATE INDEX idx_ask_retrieval_hit_tenant_created
    ON ask_retrieval_hit(tenant_id, created_at DESC);

CREATE INDEX idx_ask_retrieval_hit_request
    ON ask_retrieval_hit(ask_log_id, rank);
