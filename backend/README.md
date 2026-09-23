# RAG Knowledge Base Backend

Spring Boot 3.x + PostgreSQL + pgvector backend for the RAG knowledge base MVP.

## Java Toolchain

- Verified runtime: JDK 25.0.3.
- Repository version hint: root `.java-version` is `25`.
- Maven compiler release: Java 17, as configured by `java.version` in `pom.xml`.
- Tests launch Mockito as an explicit Java agent through Surefire, avoiding dynamic agent attachment on newer JDKs.
- GitHub Actions reads `.java-version` and uses Eclipse Temurin for backend tests.

Run `java -version` and `./mvnw -version` before debugging toolchain-specific failures. On this development machine Java is available in the login shell through the Android Studio JBR.

## Local Run

```bash
docker compose up -d db
cd backend
./mvnw spring-boot:run
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

Operational endpoints:

- `GET /actuator/health`, `/livez`, and `/readyz` are public status-only probes; health details are disabled.
- `GET /actuator/metrics` and `/actuator/prometheus` require a valid JWT plus `SYSTEM_ADMIN` or `AUDITOR`; keep them behind an internal network boundary in a real deployment as well.
- Readiness covers Spring readiness state, database connectivity, and disk space. Liveness does not call the database or AI Provider.
- Business meters include `rag.ask.requests`, `rag.ask.duration`, `rag.ask.stage.duration`, `rag.ask.tokens`, `rag.index.tasks`, and `rag.index.duration`; duration histograms support P50/P95 calculation in the metrics backend.
- Business metric tags are restricted to fixed `result`, `failure`, and `stage` values. Tenant IDs, user IDs, questions, document content, Prompt text, and raw Provider errors are not metric labels.

The Prometheus endpoint is a scrape target, not a public application API. Keep it behind authentication or an internal network boundary in deployment.

Login:

```bash
curl -s http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}'
```

## Configuration

All secrets and runtime choices are environment variables.

| Variable | Default | Notes |
|---|---:|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/rag_knowledge_base` | PostgreSQL/pgvector |
| `DB_USERNAME` / `DB_PASSWORD` | `rag` / `rag` | Local dev only |
| `JWT_SECRET` | dev string | Replace in real deployments |
| `UPLOAD_STORAGE_DIR` | `./uploads` | Raw files |
| `UPLOAD_MAX_FILE_SIZE_BYTES` | `20971520` | 20 MB |
| `RAG_CHUNK_SIZE` | `700` | Character chunk target |
| `RAG_CHUNK_OVERLAP` | `100` | Character overlap |
| `RAG_TOP_K` | `5` | Retrieval count |
| `RAG_SIMILARITY_THRESHOLD` | `0.35` | Below this, return handoff without LLM |
| `RAG_HYBRID_EXPERIMENT_ENABLED` | `false` | Gate for the evaluation-only `keyword-rrf` request header; default vector path is unchanged |
| `RAG_HYBRID_CANDIDATE_K` | `50` | ACL-visible chunks inspected by the gated in-memory keyword experiment |
| `RAG_HYBRID_KEYWORD_WEIGHT` | `2.0` | Keyword weight in the gated RRF comparison |
| `RAG_HYBRID_RRF_K` | `60` | RRF smoothing constant for the gated comparison |
| `RAG_CONTEXT_SELECTION_EXPERIMENT_ENABLED` | `false` | Dedicated gate for `vector-adjacent`; retrieves only Top-K+2 ACL-visible vector candidates and keeps the selected context at Top-K |
| `AI_EMBEDDING_DIM` | `768` | Must match embedding model |
| `AI_BASE_URL` | `http://localhost:1234/v1` | LM Studio/Ollama/OpenAI-compatible chat URL |
| `AI_MODEL_ID` | `google/gemma-4-26b-a4b-qat` | Chat model; verify the identifier in `GET http://localhost:1234/v1/models` |
| `AI_API_KEY` | empty | Optional for local providers |
| `AI_MAX_TOKENS` | `2400` | Completion budget; reasoning models may consume part of it before returning answer content |
| `AI_COMPLEX_MAX_TOKENS` | `3200` | Experimental budget for explicit multi-part questions |
| `AI_COMPLEX_ROUTING_ENABLED` | `false` | Enable deterministic complex-question budget routing; keep off until a quality/latency gate passes |
| `AI_TIMEOUT_SECONDS` | `120` | Local model can be slow |
| `AI_MAX_RETRIES` | `2` | Provider/network retry count |
| `AI_STRUCTURED_OUTPUT_RETRIES` | `1` | Bounded retry after invalid model JSON; still fails closed when exhausted |
| `AI_DAILY_LIMIT` | `50` | Per-user daily ask limit |
| `AI_EMBEDDING_BASE_URL` | same as `AI_BASE_URL` | OpenAI-compatible embeddings |
| `AI_EMBEDDING_MODEL_ID` | `text-embedding-nomic-embed-text-v1.5` | Default local embedding model; must return 768 dimensions |
| `INDEX_TASKS_ENABLED` | `true` | Enable after-commit dispatch and persistent queue polling |
| `INDEX_TASK_POLL_DELAY_MS` | `15000` | Delay between recovery/queue polls |

The Java `HttpClient` for chat and embedding explicitly uses `HTTP_1_1`, matching the `ai-weekly-report` local provider fix.

Provider logging is a separate security boundary. During the 2026-09-18 local verification, LM Studio Developer Logs displayed embedding inputs, prompts, and model output even though the application itself did not log them. Do not use customer-sensitive documents until the selected Provider's request/response logging, retention, and access controls have been reviewed.

### LM Studio smoke check

Opening the LM Studio desktop window does not prove that its OpenAI-compatible API server is running. Check both the server and the exact model identifiers before starting the backend:

```bash
lms server status
lms server start --port 1234
curl -s http://localhost:1234/v1/models
```

The configured model IDs must match the returned `id` values exactly. For the current local fixture they are `google/gemma-4-26b-a4b-qat` and `text-embedding-nomic-embed-text-v1.5`; the latter must return 768 dimensions. If the GUI reports an unexpected exit but the CLI server starts and `/v1/models` responds, the desktop UI process and the local API service are separate failure surfaces.

## Embedding Dimension

`chunk.embedding` is created as `vector(${AI_EMBEDDING_DIM})`. If you change embedding model or dimension after data exists, recreate the database or rebuild the `chunk` table and re-upload documents. pgvector will reject vectors with a different dimension.

## Database Migrations and ACL

- Flyway owns schema changes in `src/main/resources/db/migration`.
- V1 creates the original RAG schema and `vector(${AI_EMBEDDING_DIM})` column.
- V2 adds tenant, department, role, knowledge-base membership, document lifecycle metadata, and document ACL tables.
- V5 adds persistent indexing tasks for upload, replacement, single reindex, and batch reindex.
- V6-V8 add ask observability, feedback, and operational audit/metric fields; V9 repairs the `app_user.created_at` default for older baseline databases; V10 stores protected Top-K retrieval snapshots for diagnostics; V11 records the retrieval mode used by each ask.
- `spring.jpa.hibernate.ddl-auto=validate`; application startup fails when entity mappings and the migrated schema disagree.
- `baseline-on-migrate=true` upgrades the pre-Flyway MVP database by recording it as V1 before applying V2. Back up a real deployment before its first migration.
- The fixed default tenant and knowledge-base IDs are compatibility identities for the local V0.1 environment; they are not request-controlled values.
- JWT contains both user and tenant IDs. Listing, detail lookup, vector retrieval, upload, and deletion enforce permissions server-side; UI filtering is not treated as a security boundary.

## Tests

```bash
./mvnw test
```

The regular unit and Spring context tests run against H2. PostgreSQL-specific migration and ACL coverage lives in `PostgresEnterpriseIntegrationTests`, which uses Testcontainers 1.21.4 with `pgvector/pgvector:pg16`. With Docker Desktop 4.91.0, local `./mvnw test` executed all 100 tests, including 13 PostgreSQL/pgvector integration tests, with 0 skipped. In environments where Docker is unavailable to Testcontainers, those integration tests remain skipped, so CI output must still be checked before treating PostgreSQL coverage as proven.

## Cloud Provider Examples

OpenAI-compatible cloud:

```bash
export AI_BASE_URL=https://api.deepseek.com/v1
export AI_API_KEY=sk-...
export AI_MODEL_ID=deepseek-chat
export AI_EMBEDDING_BASE_URL=https://api.openai.com/v1
export AI_EMBEDDING_API_KEY=sk-...
export AI_EMBEDDING_MODEL_ID=text-embedding-3-small
export AI_EMBEDDING_DIM=1536
```

For Docker with LM Studio running on the host:

```bash
export AI_BASE_URL=http://host.docker.internal:1234/v1
export AI_EMBEDDING_BASE_URL=http://host.docker.internal:1234/v1
```

## API Contract

All business responses use:

```json
{"code":0,"message":"ok","data":{}}
```

Main endpoints:

- `POST /api/auth/login`
- `POST /api/documents/upload`
- `GET /api/documents`
- `GET /api/documents/{id}`
- `GET /api/documents/{id}/acl`
- `POST /api/documents/{id}/acl`
- `DELETE /api/documents/{id}/acl/{aclId}`
- `DELETE /api/documents/{id}`
- `POST /api/documents/{id}/disable`
- `POST /api/documents/{id}/enable`
- `POST /api/documents/{id}/reindex`
- `POST /api/documents/{id}/replace`
- `POST /api/ask`
- `PUT /api/ask/{requestId}/feedback`
- `GET /api/agent/tools`
- `POST /api/agent/tools/execute`
- `POST /mcp`
- `GET /api/admin/roles`
- `GET /api/admin/users`
- `POST /api/admin/users`
- `POST /api/admin/users/{userId}/roles`
- `DELETE /api/admin/users/{userId}/roles/{roleCode}`
- `GET /api/admin/departments`
- `POST /api/admin/departments`
- `GET /api/admin/knowledge-bases`
- `POST /api/admin/knowledge-bases`
- `POST /api/admin/knowledge-bases/{knowledgeBaseId}/disable`
- `POST /api/admin/knowledge-bases/{knowledgeBaseId}/activate`
- `GET /api/admin/knowledge-bases/{knowledgeBaseId}/memberships`
- `POST /api/admin/knowledge-bases/{knowledgeBaseId}/memberships`
- `DELETE /api/admin/knowledge-bases/{knowledgeBaseId}/memberships/{membershipId}`
- `GET /api/admin/audit-events`
- `GET /api/admin/retrieval-diagnostics/{requestId}`
- `POST /api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks/batch-reindex`
- `GET /api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks`

For the local, explicitly enabled retrieval experiment only, send
`X-RAG-Retrieval-Mode: keyword-rrf` or `X-RAG-Retrieval-Mode: vector-diversity`.
The headers are rejected while
`RAG_HYBRID_EXPERIMENT_ENABLED=false`; omitting it always uses `VECTOR`.

The experimental `vector-adjacent` mode has a separate, default-off
`RAG_CONTEXT_SELECTION_EXPERIMENT_ENABLED` gate. It starts with the normal
ACL-filtered vector Top-K, then may substitute one adjacent `chunk#N` from the
same document when that chunk is within two candidate ranks after Top-K. It
does not change the Top-K context budget and is recorded as `VECTOR_ADJACENT`
in ask diagnostics. Enable only for a controlled evaluation, then turn it off.
Both experiments reuse server-side tenant/knowledge-base/document ACLs and
record `retrievalMode` in protected diagnostics. `keyword-rrf` reads a bounded
set of ACL-visible chunks in memory; `vector-diversity` retrieves a bounded
vector candidate pool and selects at most one Chunk per document before filling
the configured context size. Neither mode is a production default or a
production full-text/reranker implementation.
- `GET /api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks/{taskId}`
- `POST /api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks/{taskId}/retry`

`POST /api/ask` returns `found=false` and `sources=[]` for low-similarity or model/retrieval failure. Low-similarity questions do not call the LLM. Every HTTP response includes `X-Request-Id`; a valid caller-supplied UUID is preserved and an invalid value is replaced.

The answer body includes `requestId`, `found`, backend-validated `grounded`, ACL-derived `sources`, total `latencyMs`, `tokenUsage`, a nullable typed `failureReason`, and `timings` for `embeddingMs`, `retrievalMs`, and `generationMs`. The model must return only `answer`, `found`, `grounded`, and `sourceIndexes`; the OpenAI-compatible provider sends the same contract as a strict `response_format=json_schema` request, while the backend validates it again and maps source indexes to retrieved ACL-filtered metadata. Invalid JSON, missing grounding, invalid citations, or a `found=true` answer that explicitly admits source facts are missing fail closed to a stable handoff response. Stable failure reasons are `RETRIEVAL_MISS`, `INSUFFICIENT_CONTEXT`, `EMBEDDING_TIMEOUT`, `EMBEDDING_ERROR`, `RETRIEVAL_ERROR`, `GENERATION_TIMEOUT`, `GENERATION_ERROR`, `STRUCTURED_OUTPUT_INVALID`, and `CITATION_MISSING`. Flyway V6/V7 stores the same correlation and timing fields with provider, model, and retrieval configuration in `ask_log`; raw exception messages, prompts, and document text are not stored there.

The read-only Agent Tool boundary exposes exactly three tools through `POST /api/agent/tools/execute`: `search_knowledge`, `list_documents`, and `get_document_status`. Calls inherit the authenticated user and tenant; `tenantId`, `userId`, arbitrary SQL, write operations, and raw file content are not accepted. Tool arguments use closed schemas, results are capped by the existing Top-K, a batch is limited to three calls, and every allow/deny/error outcome is written to the existing audit event table. Empty search/list results are successful no-result responses; unknown tools, invalid arguments, permission failures, provider errors, and timeouts return stable per-call failure reasons.

The minimal MCP adapter is a stateless JSON-RPC surface targeting MCP `2026-07-28`: authenticated `POST /mcp` accepts `server/discover`, `tools/list`, and `tools/call`, requires `MCP-Protocol-Version` and `Mcp-Method` headers, and requires `Mcp-Name` for tool calls. `tools/list` returns only the same three registered read-only tools with private/no-cache hints; `tools/call` delegates to `AgentToolService`, so tenant/user context, closed argument schemas, ACL checks, call budget, and audit behavior are not duplicated or bypassed. This is a bounded adapter, not a full MCP server: sessions, model-driven loops, Tasks, Resources, Prompts, OAuth metadata, notifications, and all write tools remain disabled. See the [official MCP 2026-07-28 release notes](https://blog.modelcontextprotocol.io/posts/2026-07-28/) for the protocol revision targeted here.

`PUT /api/ask/{requestId}/feedback` accepts `{"rating":"HELPFUL|NOT_HELPFUL","reason":"optional"}`. Only the authenticated user who created that ask record in the same tenant can submit feedback; missing, cross-user, and cross-tenant request IDs all return `404`. Repeating the request updates the single feedback row for that answer. `reason` is optional, limited to 500 characters, normalized for whitespace/control characters, and never written to application logs.

Document ACL endpoints (`/api/documents/{id}/acl`) require document `MANAGE`. They validate that the USER/DEPARTMENT/ROLE principal belongs to the caller's tenant, support `READ`/`MANAGE`, increment `permissionVersion`, and hide unauthorized document targets as `404`.

Admin endpoint boundaries:

- User and role administration requires `SYSTEM_ADMIN`.
- Department administration requires `SYSTEM_ADMIN`.
- Knowledge-base creation, activation, and disabling require `SYSTEM_ADMIN`.
- Knowledge-base membership administration requires `MANAGE` on that knowledge base.
- Audit-event queries require `SYSTEM_ADMIN` or `AUDITOR` and are always restricted to the caller's tenant.
- Index-task batch creation, status queries, and manual retry require `MANAGE` on the target knowledge base.
- Creating a user defaults to the `EMPLOYEE` role when `roleCodes` is omitted.

Role matrix:

| Role | Current V0.1 meaning |
|---|---|
| `SYSTEM_ADMIN` | Tenant-level administrator. Can manage users, roles, departments, knowledge-base lifecycle, and all knowledge-base/document permissions. |
| `KNOWLEDGE_ADMIN` | Reserved business role for delegated knowledge ownership. It does not grant global access by itself; grant `MANAGE` through knowledge-base membership for concrete scopes. |
| `EMPLOYEE` | Default user role. Can only read knowledge explicitly granted through user, department, role, or knowledge-base membership ACLs. |
| `AUDITOR` | Read-only governance role. Can query audit events for its own tenant but receives no user, document, or knowledge-base write permissions. |

Audit boundaries:

- Permission-denied admin and document-management operations write `DENY` events into `audit_event`.
- Hidden-resource behavior is preserved externally: document management denial still returns `404` to avoid leaking whether the document exists.
- Audit records include tenant, user, action, resource type, resource id, outcome, reason, and timestamp.
- `GET /api/admin/audit-events` supports optional `userId`, `action`, `resourceType`, `resourceId`, `outcome`, `from`, `to`, and `limit` filters. `from`/`to` use ISO-8601 timestamps; `limit` defaults to 100 and is capped at 200.
- Audit results are ordered newest first and include `hasMore`; unauthorized query attempts return `403` and are themselves recorded as `AUDIT_EVENT_LIST` denials.
- Retrieval diagnostics are restricted to `SYSTEM_ADMIN` or `AUDITOR` and the current tenant. They expose rank, filename, locator, similarity, configured Top-K/threshold, result status, and failure reason; they deliberately exclude question text, Prompt, and Chunk content. Unauthorized requests return `403` and are recorded as `RETRIEVAL_DIAGNOSTIC_GET` denials.

Document lifecycle boundaries:

- Upload calculates SHA-256 and returns the existing undeleted document when the same checksum already exists in the same knowledge base.
- Delete is a soft delete for the document row, but clears chunks and deletes the raw uploaded file.
- Disable hides a document from list, detail, and retrieval without deleting its row or raw file.
- Upload, reindex, and replacement persist an `index_task` before asynchronous processing starts. API responses include `taskId` for newly queued work.
- Reindex increments `contentVersion` and marks the document as `processing`; existing chunks are retained until the replacement index is ready, then swapped transactionally.
- Replace accepts a multipart `file`, requires document `MANAGE`, and rejects disabled or already-processing documents.
- Replacing with the current checksum is idempotent (`unchanged=true`); matching another active document in the same knowledge base returns `409`.
- A replacement is stored under a versioned path and increments `contentVersion`. Existing chunks and the old raw file remain intact until parsing and embedding succeed.
- Successful processing swaps chunks transactionally and cleans the old raw file after commit. Parser or embedding failure restores the previous metadata/version/status, keeps the old chunks searchable, and cleans the failed replacement file.
- Task identity is `documentId:contentVersion`, so repeated enqueue attempts return the same task. Workers claim due tasks with PostgreSQL `FOR UPDATE SKIP LOCKED`.
- Failed work retries automatically up to three attempts with bounded backoff. Final failures retain the sanitized reason, attempt count, start/end time, and duration; a manager can explicitly queue another three attempts for upload/reindex work.
- A failed replacement restores the prior document and deletes the failed staged file, so that old replacement task cannot be retried; submit the replacement file again to create a new version/task.
- A startup/poll recovery pass returns stale `RUNNING` tasks to `PENDING`. This is an in-process PostgreSQL queue for V0.1, not a distributed message-broker guarantee.
