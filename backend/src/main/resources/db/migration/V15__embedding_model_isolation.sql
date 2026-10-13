-- Vectors from different embedding models (or dimensions) are not comparable. Record which model
-- produced every chunk vector and let several dimensions coexist, so a model switch can be rebuilt
-- document by document instead of mixing vector spaces or failing on dimension mismatch.
--
-- Flyway does not substitute placeholders inside dollar-quoted strings, hence the plain quotes: the
-- configured model id must not contain a single quote.
-- Existing rows are attributed to the model configured when this migration runs
-- (AI_EMBEDDING_MODEL_ID, default text-embedding-nomic-embed-text-v1.5). If the configured model was
-- changed earlier without a rebuild, those rows are mislabelled; reindex all documents to correct them.

DROP INDEX IF EXISTS idx_chunk_embedding;

ALTER TABLE chunk ALTER COLUMN embedding TYPE vector;

ALTER TABLE chunk
    ADD COLUMN embedding_dim INTEGER GENERATED ALWAYS AS (vector_dims(embedding)) STORED;

ALTER TABLE chunk
    ADD COLUMN embedding_model VARCHAR(128) NOT NULL DEFAULT '${embedding_model}';

-- HNSW indexes need a fixed dimension, so each supported dimension gets its own partial index.
-- Queries repeat the same cast and predicate (see ChunkJdbcRepository). Other dimensions still work
-- through a sequential scan; add an index of the same shape for them.
CREATE INDEX idx_chunk_embedding_768 ON chunk
    USING hnsw ((embedding::vector(768)) vector_cosine_ops) WHERE embedding_dim = 768;
CREATE INDEX idx_chunk_embedding_1024 ON chunk
    USING hnsw ((embedding::vector(1024)) vector_cosine_ops) WHERE embedding_dim = 1024;

CREATE INDEX idx_chunk_embedding_model ON chunk (embedding_model);
