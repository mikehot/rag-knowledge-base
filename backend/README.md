# RAG Knowledge Base Backend

Spring Boot 3.x + PostgreSQL + pgvector backend for the RAG knowledge base MVP.

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

`POST /api/ask` returns `found=false` and `sources=[]` for low-similarity or model/retrieval failure. Low-similarity questions do not call the LLM.
