-- Atomic per-user daily ask counter. A slot is reserved before any model call,
-- so concurrent requests cannot exceed AI_DAILY_LIMIT.
CREATE TABLE ask_daily_usage (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    ask_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);
