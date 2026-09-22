ALTER TABLE ask_log
    DROP CONSTRAINT IF EXISTS ck_ask_log_retrieval_mode;

ALTER TABLE ask_log
    ADD CONSTRAINT ck_ask_log_retrieval_mode
    CHECK (retrieval_mode IN ('VECTOR', 'VECTOR_DIVERSITY', 'KEYWORD_RRF'));
