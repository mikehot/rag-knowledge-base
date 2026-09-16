CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenant (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Default Tenant')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE department (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    parent_id UUID REFERENCES department(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS department_id UUID;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
UPDATE app_user
SET tenant_id = '00000000-0000-0000-0000-000000000001'
WHERE tenant_id IS NULL;
ALTER TABLE app_user ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_department FOREIGN KEY (department_id) REFERENCES department(id);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);
CREATE INDEX idx_app_user_department ON app_user(department_id);

CREATE TABLE app_role (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

INSERT INTO app_role (id, tenant_id, code, name) VALUES
    ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'SYSTEM_ADMIN', 'System Administrator'),
    ('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000001', 'KNOWLEDGE_ADMIN', 'Knowledge Administrator'),
    ('00000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000001', 'EMPLOYEE', 'Employee'),
    ('00000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000001', 'AUDITOR', 'Auditor')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO user_role (user_id, role_id)
SELECT id, '00000000-0000-0000-0000-000000000011'
FROM app_user
ON CONFLICT DO NOTHING;

CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

INSERT INTO knowledge_base (id, tenant_id, code, name, description)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000001',
    'default',
    'Default Knowledge Base',
    'Compatibility knowledge base for the existing single-user MVP'
)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE knowledge_base_membership (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    principal_type VARCHAR(32) NOT NULL,
    principal_id UUID NOT NULL,
    permission VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_kb_membership_principal_type
        CHECK (principal_type IN ('USER', 'DEPARTMENT', 'ROLE')),
    CONSTRAINT ck_kb_membership_permission
        CHECK (permission IN ('READ', 'MANAGE')),
    UNIQUE (knowledge_base_id, principal_type, principal_id, permission)
);

INSERT INTO knowledge_base_membership (
    id,
    tenant_id,
    knowledge_base_id,
    principal_type,
    principal_id,
    permission
)
SELECT
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000101',
    'USER',
    id,
    'MANAGE'
FROM app_user
ON CONFLICT DO NOTHING;

ALTER TABLE document ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE document ADD COLUMN IF NOT EXISTS knowledge_base_id UUID;
ALTER TABLE document ADD COLUMN IF NOT EXISTS source_id VARCHAR(255);
ALTER TABLE document ADD COLUMN IF NOT EXISTS checksum VARCHAR(128);
ALTER TABLE document ADD COLUMN IF NOT EXISTS content_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE document ADD COLUMN IF NOT EXISTS permission_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE document ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ;
ALTER TABLE document ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
UPDATE document
SET tenant_id = '00000000-0000-0000-0000-000000000001',
    knowledge_base_id = '00000000-0000-0000-0000-000000000101'
WHERE tenant_id IS NULL OR knowledge_base_id IS NULL;
ALTER TABLE document ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document ALTER COLUMN knowledge_base_id SET NOT NULL;
ALTER TABLE document
    ADD CONSTRAINT fk_document_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE document
    ADD CONSTRAINT fk_document_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id);
CREATE INDEX idx_document_tenant_kb_status ON document(tenant_id, knowledge_base_id, status);
CREATE INDEX idx_document_source ON document(tenant_id, knowledge_base_id, source_id);

CREATE TABLE document_acl (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    principal_type VARCHAR(32) NOT NULL,
    principal_id UUID NOT NULL,
    permission VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_document_acl_principal_type
        CHECK (principal_type IN ('USER', 'DEPARTMENT', 'ROLE')),
    CONSTRAINT ck_document_acl_permission
        CHECK (permission IN ('READ', 'MANAGE')),
    UNIQUE (document_id, principal_type, principal_id, permission)
);

CREATE INDEX idx_kb_membership_lookup
    ON knowledge_base_membership(tenant_id, principal_type, principal_id, knowledge_base_id);
CREATE INDEX idx_document_acl_lookup
    ON document_acl(tenant_id, principal_type, principal_id, document_id);
