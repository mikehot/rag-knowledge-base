CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    filename VARCHAR(255) NOT NULL,
    file_type VARCHAR(64),
    file_path VARCHAR(2048),
    status VARCHAR(32) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_msg VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ask_log (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    question VARCHAR(2000) NOT NULL,
    found BOOLEAN NOT NULL,
    token_usage INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    seq INTEGER,
    locator TEXT,
    content TEXT NOT NULL,
    embedding vector(${embedding_dim}),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunk_document ON chunk(document_id);
CREATE INDEX idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_document_user_created ON document(user_id, created_at DESC);
CREATE INDEX idx_ask_log_user_created ON ask_log(user_id, created_at DESC);
