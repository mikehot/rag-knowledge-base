# RAG Knowledge Base

[![CI](https://github.com/mikehot/rag-knowledge-base/actions/workflows/ci.yml/badge.svg)](https://github.com/mikehot/rag-knowledge-base/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[中文](README.md) | English

An enterprise knowledge-base Q&A system where employees only retrieve documents they are allowed to see, every answer cites its sources, and uncovered questions are handed off instead of invented. It is built with Spring Boot, PostgreSQL + pgvector, and Flutter, and ships with evaluation, audit, and deployment runbooks.

- **Start here:** [PORTFOLIO.md](PORTFOLIO.md) (design decisions, evaluation results, and known limitations) and the [English case study](docs/CASE_STUDY_EN.md).
- **Run it in five minutes:** [Quick Start](#quick-start) below. It was verified verbatim on a fresh clone.
- **Status:** V0.1 is wrapping up; see [ROADMAP.md](ROADMAP.md). Verified evidence is in [PROGRESS.md](PROGRESS.md) and [evaluation/reports](evaluation/reports/README.md).

## Highlights

- **Permissions are a retrieval constraint.** Tenant, department, role, knowledge-base, and document ACLs are enforced inside the vector-search SQL, so unauthorized content never reaches the model. Every permission change is audited in the same transaction.
- **Backend-owned citations, fail-closed.** The model returns only an answer, flags, and source indexes. The backend validates the contract and builds the citations. Invalid or truncated output, provider errors, and timeouts hand off to a human with a stable failure reason.
- **Recoverable indexing.** A persistent task queue provides idempotency, retries, and restart recovery. A failed reindex keeps serving the previous version.
- **Evaluation-driven decisions.** Versioned datasets and a frozen quality gate decide changes. With local Gemma the gate is not passed. With DeepSeek flash as the chat model (embeddings stay local) it passes 10/10/10 at a median of about 1–1.6 s and an estimated under $1 per 1,000 questions at list price.
- **Bounded agent surface.** Three read-only tools and a stateless MCP adapter share the same identity, ACL, budget, and audit.

Most of the implementation was written with AI coding agents (OpenAI Codex, Claude Code). Scope, architecture, evaluation design, review, and acceptance are owned by the author.

## Quick Start

### Prerequisites

- Docker (daemon running), JDK 25 (`.java-version`; sources compile to Java 17 bytecode), `curl`, `jq`.
- An OpenAI-compatible model server. The default is local LM Studio at `http://localhost:1234/v1` with:
  - Chat: `google/gemma-4-26b-a4b-qat` (override with `AI_MODEL_ID`). Turn **Enable Thinking** off for Gemma in LM Studio; see the [runbook](docs/DEPLOYMENT_RUNBOOK.md).
  - Embedding: `text-embedding-nomic-embed-text-v1.5` (768 dimensions).
  - Both IDs must match `curl -s http://localhost:1234/v1/models`.
- A cloud chat model works too. The verified setup is DeepSeek `deepseek-flash` with local embeddings and `AI_RESPONSE_FORMAT=json_object`; see [backend/README.md](backend/README.md#cloud-provider-examples).
- Use LM Studio only with synthetic or public data.
- `docker-compose.yml` fixes the container name `rag-knowledge-base-db` and port 5432; a second checkout on the same machine will conflict.

### 1. Start

Run all commands from the repository root.

```bash
docker compose up -d db
(cd backend && ./mvnw spring-boot:run)
```

In another terminal, wait until `curl -s http://localhost:8080/readyz` returns `{"status":"UP"}`. The default admin is `demo` / `demo123456` (local demos only).

### 2. Upload and ask

```bash
BASE_URL=http://localhost:8080
TOKEN=$(curl -s "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}' | jq -r .data.token)

DOC_ID=$(curl -s "$BASE_URL/api/documents/upload" -H "Authorization: Bearer $TOKEN" \
  -F 'file=@sample_faq.md' | jq -r .data.documentId)

# 等待索引完成（通常几秒）
until curl -s "$BASE_URL/api/documents/$DOC_ID" -H "Authorization: Bearer $TOKEN" \
  | jq -e '.data.status == "ready"' >/dev/null; do sleep 2; done

# 资料内：found=true，sources 来自后端检索
curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"设备保修期是多久？"}' | jq '.data | {found, answer, sources: [.sources[].filename], latencyMs}'

# 资料外：found=false，不编造
curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"公司明年的股权计划是什么？"}' | jq '.data | {found, sources, failureReason}'
```

The Chinese questions ask "How long is the device warranty?" (covered by `sample_faq.md`) and "What is next year's equity plan?" (not covered).

### 3. Permissions: employees only retrieve what they are granted

```bash
EMP_ID=$(curl -s "$BASE_URL/api/admin/users" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"demo-employee","password":"employee123456","roleCodes":["EMPLOYEE"]}' | jq -r .data.id)
EMP_TOKEN=$(curl -s "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo-employee","password":"employee123456"}' | jq -r .data.token)
ask_as_employee() {
  curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $EMP_TOKEN" -H 'Content-Type: application/json' \
    -d '{"question":"设备保修期是多久？"}' | jq -c '.data | {found, sources: [.sources[].filename]}'
}

ask_as_employee    # 未授权：found=false，无引用；GET /api/documents 为空

ACL_ID=$(curl -s "$BASE_URL/api/documents/$DOC_ID/acl" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"principalType\":\"USER\",\"principalId\":\"$EMP_ID\",\"permission\":\"READ\"}" | jq -r .data.id)
ask_as_employee    # 已授权：found=true，引用 sample_faq.md

curl -s -X DELETE "$BASE_URL/api/documents/$DOC_ID/acl/$ACL_ID" -H "Authorization: Bearer $TOKEN" >/dev/null
ask_as_employee    # 撤权后：再次拒答
```

Grants can also target a `DEPARTMENT` or `ROLE`. The filter runs in SQL at retrieval time, so unauthorized content never enters the model context.

For the full demo (feedback, read-only tools/MCP, failure recovery, Flutter client), see [docs/DEMO.md](docs/DEMO.md).

## Documentation

| Document | Contents |
|---|---|
| [PORTFOLIO.md](PORTFOLIO.md) | Problem, architecture, design decisions, evaluation, limitations (Chinese, with an English summary) |
| [docs/CASE_STUDY_EN.md](docs/CASE_STUDY_EN.md) | English case study and a five-minute talk track |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Context, data flows, trust boundaries |
| [docs/DEPLOYMENT_RUNBOOK.md](docs/DEPLOYMENT_RUNBOOK.md) | Start, migrate, back up and restore, troubleshoot, provider data boundary |
| [evaluation/README.md](evaluation/README.md) | Datasets, the frozen quality gate, evaluation tools |

## License

[MIT](LICENSE)
