# RAG Knowledge Base

[![CI](https://github.com/mikehot/rag-knowledge-base/actions/workflows/ci.yml/badge.svg)](https://github.com/mikehot/rag-knowledge-base/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[中文](README.md) | English

An enterprise knowledge-base Q&A system where employees only retrieve documents they are allowed to see, every answer cites its sources, and uncovered questions are handed off instead of invented. It is built with Spring Boot, PostgreSQL + pgvector, and Flutter, and ships with evaluation, audit, and deployment runbooks.

- **Start here:** [PORTFOLIO.md](PORTFOLIO.md) (design decisions, evaluation results, and known limitations) and the [English case study](docs/CASE_STUDY_EN.md).
- **Run it in five minutes:** [Quick Start](#quick-start) below. It was verified verbatim on a fresh clone.
- **Status:** V0.2 (evaluation and retrieval quality); see [ROADMAP.md](ROADMAP.md). Verified evidence is in [PROGRESS.md](PROGRESS.md) and [evaluation/reports](evaluation/reports/README.md). There is no demo video or public demo yet.

## Highlights

- **Permissions are a retrieval constraint.** Tenant, department, role, knowledge-base, and document ACLs are enforced inside the vector-search SQL, so unauthorized content never reaches the model. Every permission change is audited in the same transaction.
- **Backend-owned citations, fail-closed.** The model returns only an answer, flags, and source indexes. The backend validates the contract and builds the citations. Invalid or truncated output, provider errors, and timeouts hand off to a human with a stable failure reason.
- **Recoverable indexing.** A persistent task queue provides idempotency, retries, and restart recovery. A failed reindex keeps serving the previous version.
- **Evaluation-driven decisions, with the spread attached.** Versioned datasets, a frozen quality gate, and a repeat-run tool that reports run-to-run variation decide changes. On a 28-document, 57-question Chinese corpus the recommended configuration (bge-m3 embeddings, `AI_TEMPERATURE=0`) answers 44 of 45 answerable questions and refuses all 12 out-of-scope or unauthorized ones; the same configuration varies by up to 3 cases between runs, so single-run differences are not claimed. The quality gate passes for that configuration and does **not** pass for the shipped default (nomic). **These are measurements of one day on one machine, not constants:** a re-run on a fresh stack the next day scored 38 of 45 with identical retrieval (median top-1 similarity 0.703, all expected documents in the Top-5); the cause was not isolated (see the addendum in `evaluation/reports/corpus-v2-paraphrase-local-2026-10-13.md`). Quote a number together with its date and configuration.
- **Defence in depth around the model.** Retrieved text is treated as untrusted data inside a keyed boundary; answers that echo it or carry links absent from the evidence are handed off, and the backend checks that the cited chunks actually support the answer and repairs the citation when they do not.
- **Switchable embeddings.** Every chunk records the model that produced its vector; a model switch is a rebuild with a progress endpoint, never a silent mix of vector spaces.
- **Bounded agent surface.** Three read-only tools and a stateless MCP adapter share the same identity, ACL, budget, and audit. A separate Python service, [rag-agent-python](https://github.com/mikehot/rag-agent-python), consumes them through MCP with the caller's own JWT as an orchestration layer; it does not reimplement retrieval, and its tool-calling evaluation (safety rows pass in every configuration; answer rows vary with tool-output length) is reported there.

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

# Wait for indexing to finish (usually a few seconds)
until curl -s "$BASE_URL/api/documents/$DOC_ID" -H "Authorization: Bearer $TOKEN" \
  | jq -e '.data.status == "ready"' >/dev/null; do sleep 2; done

# In-scope: found=true, sources from backend retrieval
curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"设备保修期是多久？"}' | jq '.data | {found, answer, sources: [.sources[].filename], latencyMs}'

# Out-of-scope: found=false, no hallucination
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

ask_as_employee    # Unauthorized: found=false, no sources; GET /api/documents returns empty

ACL_ID=$(curl -s "$BASE_URL/api/documents/$DOC_ID/acl" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"principalType\":\"USER\",\"principalId\":\"$EMP_ID\",\"permission\":\"READ\"}" | jq -r .data.id)
ask_as_employee    # Authorized: found=true, cites sample_faq.md

curl -s -X DELETE "$BASE_URL/api/documents/$DOC_ID/acl/$ACL_ID" -H "Authorization: Bearer $TOKEN" >/dev/null
ask_as_employee    # After revocation: refuses again
```

Grants can also target a `DEPARTMENT` or `ROLE`. The filter runs in SQL at retrieval time, so unauthorized content never enters the model context.

For the full demo (feedback, read-only tools/MCP, failure recovery, Flutter client), see [docs/DEMO.md](docs/DEMO.md).

The demo video has **not been recorded yet**. A storyboard and two scene-by-scene drivers are ready on the Python agent side (users' view in 7 scenes, an administrator part covering upload and grant/revoke, and a cut that fits 5 minutes): [rag-agent-python/docs/DEMO_VIDEO.md](https://github.com/mikehot/rag-agent-python/blob/main/docs/DEMO_VIDEO.md). The Flutter client and the device spot check are not part of it; see `docs/DEMO.md`.

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
