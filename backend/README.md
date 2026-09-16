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
| `AI_EMBEDDING_DIM` | `768` | Must match embedding model |
| `AI_BASE_URL` | `http://localhost:1234/v1` | LM Studio/Ollama/OpenAI-compatible chat URL |
| `AI_MODEL_ID` | `google/gemma-4-26b-a4b` | Chat model |
| `AI_API_KEY` | empty | Optional for local providers |
| `AI_TIMEOUT_SECONDS` | `120` | Local model can be slow |
| `AI_DAILY_LIMIT` | `50` | Per-user daily ask limit |
| `AI_EMBEDDING_BASE_URL` | same as `AI_BASE_URL` | OpenAI-compatible embeddings |
| `AI_EMBEDDING_MODEL_ID` | `text-embedding-nomic-embed-text` | Default local embedding model |

The Java `HttpClient` for chat and embedding explicitly uses `HTTP_1_1`, matching the `ai-weekly-report` local provider fix.

## Embedding Dimension

`chunk.embedding` is created as `vector(${AI_EMBEDDING_DIM})`. If you change embedding model or dimension after data exists, recreate the database or rebuild the `chunk` table and re-upload documents. pgvector will reject vectors with a different dimension.

## Database Migrations and ACL

- Flyway owns schema changes in `src/main/resources/db/migration`.
- V1 creates the original RAG schema and `vector(${AI_EMBEDDING_DIM})` column.
- V2 adds tenant, department, role, knowledge-base membership, document lifecycle metadata, and document ACL tables.
- `spring.jpa.hibernate.ddl-auto=validate`; application startup fails when entity mappings and the migrated schema disagree.
- `baseline-on-migrate=true` upgrades the pre-Flyway MVP database by recording it as V1 before applying V2. Back up a real deployment before its first migration.
- The fixed default tenant and knowledge-base IDs are compatibility identities for the local V0.1 environment; they are not request-controlled values.
- JWT contains both user and tenant IDs. Listing, detail lookup, vector retrieval, upload, and deletion enforce permissions server-side; UI filtering is not treated as a security boundary.

## Tests

```bash
./mvnw test
```

The regular unit and Spring context tests run against H2. PostgreSQL-specific migration and ACL coverage lives in `PostgresEnterpriseIntegrationTests`, which uses Testcontainers with `pgvector/pgvector:pg16`. Those tests run automatically when Docker is available to Testcontainers and are skipped when Docker is unavailable, so CI output should be checked for skipped integration tests before treating PostgreSQL coverage as proven.

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
- `DELETE /api/documents/{id}`
- `POST /api/ask`
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

`POST /api/ask` returns `found=false` and `sources=[]` for low-similarity or model/retrieval failure. Low-similarity questions do not call the LLM.

Admin endpoint boundaries:

- User and role administration requires `SYSTEM_ADMIN`.
- Department administration requires `SYSTEM_ADMIN`.
- Knowledge-base creation, activation, and disabling require `SYSTEM_ADMIN`.
- Knowledge-base membership administration requires `MANAGE` on that knowledge base.
- Creating a user defaults to the `EMPLOYEE` role when `roleCodes` is omitted.
