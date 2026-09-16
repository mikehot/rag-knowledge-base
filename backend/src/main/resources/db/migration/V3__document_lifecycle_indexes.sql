CREATE UNIQUE INDEX IF NOT EXISTS ux_document_active_checksum
    ON document(tenant_id, knowledge_base_id, checksum)
    WHERE checksum IS NOT NULL AND deleted_at IS NULL;
