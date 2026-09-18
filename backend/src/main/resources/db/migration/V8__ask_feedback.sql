CREATE TABLE ask_feedback (
    id UUID PRIMARY KEY,
    ask_log_id UUID NOT NULL REFERENCES ask_log(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    rating VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ask_feedback_ask_log UNIQUE (ask_log_id),
    CONSTRAINT ck_ask_feedback_rating CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL'))
);

CREATE INDEX idx_ask_feedback_tenant_created
    ON ask_feedback(tenant_id, created_at DESC);

CREATE INDEX idx_ask_feedback_user_created
    ON ask_feedback(user_id, created_at DESC);
