CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID REFERENCES app_user(id),
    username VARCHAR(255),
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    outcome VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_event_outcome
        CHECK (outcome IN ('ALLOW', 'DENY', 'ERROR'))
);

CREATE INDEX idx_audit_event_tenant_created
    ON audit_event(tenant_id, created_at DESC);

CREATE INDEX idx_audit_event_user_created
    ON audit_event(tenant_id, user_id, created_at DESC);

CREATE INDEX idx_audit_event_resource_created
    ON audit_event(tenant_id, resource_type, resource_id, created_at DESC);
