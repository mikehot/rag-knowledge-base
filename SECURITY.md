# Security Policy

## Scope & Architecture Boundaries

- **Identity & ACL enforcement:** All retrieval, document listing, and admin endpoints authenticate callers via JWT (`Authorization: Bearer`). Document visibility and tenant/knowledge-base/department/user ACL filtering are enforced server-side in PostgreSQL before any chunk reaches the LLM.
- **No built-in production secrets:** `application.yml` defines no fallback for `JWT_SECRET` or `APP_DEFAULT_PASSWORD`. Always supply strong, randomly generated secrets via environment variables in staging/production.
- **Loopback-first Docker publishing:** `docker-compose.yml` binds ports to `127.0.0.1` by default (`RAG_BIND_HOST`). Expose services externally only behind a TLS-terminating reverse proxy.
- **Data retention & logging:** See [docs/delivery/DATA_HANDLING.md](docs/delivery/DATA_HANDLING.md) for what data is persisted (`ask_log`, uploaded documents, embeddings) and how `ASK_LOG_RETENTION_DAYS` purges expired Q&A logs.

## Reporting a Vulnerability

Please do not open a public GitHub issue for security vulnerabilities. Report suspected issues privately to `mikehot668@gmail.com` with reproduction steps and impact assessment.
