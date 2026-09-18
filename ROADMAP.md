# Enterprise RAG / FDE Delivery Roadmap

> Version: v2.0
>
> Last verified: 2026-09-18
> Target: evolve the existing RAG MVP into an enterprise knowledge-base V0.1, then extend it into a bounded Agent/FDE delivery case.

## 1. Product Goal

The first delivery target is not a general-purpose AI platform. It is one complete enterprise workflow:

> An administrator uploads enterprise documents; the system parses, chunks, embeds, and indexes them; an authenticated employee can search only the knowledge they are allowed to access; the answer is grounded in retrieved content and displays citations; operators can inspect latency, token usage, failures, audit events, and user feedback.

The project is successful only when it is:

- runnable: a reviewer can start the system from documented steps;
- explainable: architecture, security boundaries, and failure behavior are clear;
- measurable: a reproducible evaluation set records quality, latency, and cost;
- deployable: configuration, secrets, health checks, migration, backup, and rollback are documented;
- valuable: the case study connects the system to a real workflow and measurable outcome.

## 2. Technical Direction

- Keep Java / Spring Boot as the enterprise application backend.
- Keep PostgreSQL + pgvector as the metadata, audit, and vector store for V0.1.
- Keep Flutter as the client that demonstrates authentication, knowledge management, citations, feedback, and failure recovery.
- Use Python for concrete AI support work such as offline evaluation, OCR, reranker experiments, data cleaning, and benchmarks; do not rewrite the main service merely to change language.
- Keep the existing provider interfaces. Evaluate Spring AI 1.1.x behind those interfaces only when it reduces implementation cost without destabilizing the current API.
- Do not migrate to Spring Boot 4 / Spring AI 2 solely to obtain newer Agent or MCP APIs during V0.1.

The current `pom.xml` targets Java 17. The supported local and CI JDK must be decided and recorded during Milestone 1; Java 21 LTS is a candidate, not a verified project requirement yet.

## 3. Verified Baseline

Implemented in the repository:

- JWT authentication with a single demo user.
- PDF, DOCX, TXT, and Markdown ingestion.
- Async parse, chunk, embedding, and document status flow.
- PostgreSQL + pgvector schema and Top-K vector similarity retrieval.
- Similarity-threshold fallback, citations, token usage, and a daily question limit.
- OpenAI-compatible chat and embedding providers with configurable models and timeouts.
- Flutter question-answering and document-management flows.
- Docker Compose definitions for pgvector and the backend.

Verification performed on 2026-09-16:

- `flutter analyze --no-pub`: passed.
- `flutter test --no-pub --concurrency=1`: passed; only one widget smoke test exists.
- `docker compose config --quiet`: passed.
- `./mvnw test`: passed on JDK 25.0.3 with 13 tests after configuring an explicit Mockito Java agent.
- PostgreSQL 16 + pgvector 0.8.6 started successfully; the vector extension, four application tables, and HNSW cosine index were verified.
- LM Studio with Gemma 4 26B and Nomic Embedding completed upload, two-chunk indexing, cited answer, out-of-scope refusal, and deletion.
- Real verification exposed and fixed two defects: model-level refusal previously returned `found=true` with irrelevant sources, and document deletion previously left the raw upload on disk.

This evidence supersedes older generic statements that all tests pass.

Milestone 2 checkpoint on 2026-09-16:

- Flyway V1/V2 now owns the PostgreSQL/pgvector schema; Hibernate runs with `ddl-auto=validate`.
- The enterprise schema includes tenant, department, role, user-role, knowledge base, membership, document lifecycle metadata, and document ACL tables.
- JWT authentication carries the tenant boundary, and document/chunk SQL performs security trimming before content reaches generation.
- Upload and delete require `MANAGE`; list, detail, and retrieval accept inherited `READ`/`MANAGE` grants from user, department, or role principals.
- Both an existing non-empty database upgrade and a fresh V1+V2 database were verified. A temporary reader saw no document before a grant, saw it after `READ`, and still could not delete it.
- This milestone remains `in-progress`: the minimum management APIs, permission-denied audit, rollback-safe replacement upload, automated PostgreSQL ACL tests, cross-tenant cases, persistent batch reindex, retry, idempotency, task observability, and restart recovery are verified. Management UI and secondary identity lifecycle operations remain open.

## 4. Delivery Principles

- Improve this repository instead of creating additional empty repositories.
- Treat permissions as a retrieval constraint, not a UI-only filter or post-generation mask.
- Fail closed: missing or stale permission context must not broaden access.
- Generate citations from retrieved document/chunk metadata; never ask the model to invent source records.
- Add every capability with a normal path, failure path, tests, observable evidence, and documentation.
- Keep model, embedding dimension, retrieval parameters, timeouts, limits, and feature flags configurable.
- Keep prompts, document text, tool arguments, credentials, and personal data out of logs by default.
- Use sanitized sample data; never commit customer documents, production secrets, tokens, or embedding dumps.
- Let evaluation results decide whether retrieval complexity is necessary.

## 5. Milestones and Acceptance Gates

### Milestone 1 — Reproducible RAG Baseline

Goal: repair the test/runtime baseline before adding enterprise features.

Work:

- Pin and document the supported JDK for local development and CI.
- Fix the backend test runtime failure.
- Add focused tests for retrieval hit, low-similarity fallback, provider failure, daily limit, citation mapping, token recording, and document deletion.
- Start PostgreSQL + pgvector, the backend, and one OpenAI-compatible model service.
- Run `sample_faq.md` through upload, processing, retrieval, cited answer, fallback, and deletion.
- Record exact commands, environment, results, and unresolved failures in `PROGRESS.md`.

Acceptance gate:

- Backend and Flutter checks pass on the documented toolchain.
- A real document reaches `ready` with a non-zero chunk count.
- A covered question returns `found=true` with a valid source derived from retrieved metadata.
- An uncovered question returns `found=false` without an LLM-generated answer.
- Deletion removes the document and its chunks from subsequent retrieval.

### Milestone 2 — Enterprise Identity, ACL, and Document Lifecycle

Goal: ensure users can retrieve only authorized and current knowledge.

Work:

- Add users, departments, roles, knowledge bases, memberships, and document ACLs.
- Define the tenant boundary even if the first deployment contains one tenant.
- Apply security trimming inside document listing and chunk retrieval queries.
- Add permission-denied audit events and deterministic cross-department tests.
- Add document source identity, checksum, content version, permission version, last-modified time, disabled/deleted state, and indexing status.
- Make upload/update/reindex idempotent and propagate deletion and permission changes to retrieval.
- Define recovery behavior for failed parsing, embedding, and index updates.

Acceptance gate:

- An authorized user can list and retrieve allowed documents.
- An unauthorized user cannot list, retrieve, cite, or infer a forbidden document.
- The ACL leakage count in the security evaluation is zero.
- Re-upload, permission change, disable, delete, and reindex behavior is reproducible.

### Milestone 3 — Structured Answers, Audit, Observability, and Feedback

Goal: make each answer traceable and each failure diagnosable.

Target answer contract:

```json
{
  "answer": "...",
  "found": true,
  "sources": [],
  "requestId": "...",
  "latencyMs": 0,
  "tokenUsage": 0,
  "failureReason": null,
  "timings": {
    "embeddingMs": 0,
    "retrievalMs": 0,
    "generationMs": 0
  }
}
```

Work:

- Stabilize typed output for success, fallback, and failure responses.
- Add request/correlation IDs and stage-level timings for embedding, retrieval, and generation. Implemented and locally verified on 2026-09-18.
- Record model/provider, retrieval parameters, token usage, result status, and sanitized failure reason. Implemented in Flyway V6/V7 and locally verified on PostgreSQL 16.15; CI verification is pending.
- Add user feedback (`helpful`, `not_helpful`, optional sanitized reason).
- Add health/readiness checks and operational metrics without logging sensitive content by default.

Acceptance gate:

- Normal, fallback, timeout, provider-error, and permission-denied paths have stable contracts.
- A request can be traced across API, retrieval, and model stages by `requestId`.
- Operators can distinguish retrieval miss, ACL rejection, provider failure, timeout, and validation failure.
- Feedback can be linked to the corresponding answer and evaluation sample.

### Milestone 4 — Twenty-Question Evaluation Baseline

Goal: replace subjective demos with reproducible evidence.

Each evaluation case records:

- question and answerability;
- expected answer points and expected source document;
- acting user, department, role, and knowledge base;
- forbidden documents that must never be returned;
- expected fallback or permission behavior.

Metrics:

- retrieval Hit Rate / Recall@K;
- citation coverage and citation correctness;
- groundedness and expected-answer coverage;
- out-of-scope refusal correctness;
- ACL leakage count;
- P50/P95 latency, token usage, estimated cost, and failure rate.

Acceptance gate:

- The 20 sanitized cases run from a versioned dataset.
- Deterministic checks cover source IDs, ACL, fallback, and response schema.
- Model-based scoring, if used, is reported separately from deterministic checks.
- Failed cases and error categories are retained instead of being hidden by an average score.

### Milestone 5 — Evaluation-Driven Retrieval Improvements

Goal: add retrieval complexity only when a measured failure justifies it.

Decision order:

1. Tune chunking, metadata, Top-K, and similarity threshold.
2. If exact terms, IDs, or names are missed, add PostgreSQL full-text/keyword retrieval.
3. Combine vector and keyword results with a documented fusion strategy such as RRF.
4. If ranking remains the measured bottleneck, evaluate a reranker.

Acceptance gate:

- Every added component names the evaluation failures it addresses.
- Before/after results use the same versioned dataset and configuration record.
- Quality gain is reported together with latency, complexity, and cost impact.
- Elasticsearch, a separate vector database, or GraphRAG is not introduced without evidence that PostgreSQL is insufficient.

### Milestone 6 — Read-Only Agent Tools and MCP

Goal: extend a secure, measured knowledge system into a bounded Agent delivery.

Initial read-only tools:

- `search_knowledge(query, knowledgeBaseId)`
- `list_documents(status, knowledgeBaseId)`
- `get_document_status(documentId)`

Work:

- Add typed Tool Calling behind an application-owned allowlist/registry.
- Pass authenticated user, tenant, role, and request context outside model-controlled arguments.
- Validate arguments and enforce ACL again before execution.
- Bound tool calls, loop iterations, wall-clock time, tokens, and result size.
- Add timeout, partial-failure, unknown-tool, invalid-argument, and budget-exhaustion tests.
- Expose only approved read-only capabilities through MCP after the internal tool boundary is stable.
- Keep write operations disabled until explicit approval, audit, idempotency, and rollback exist.

Acceptance gate:

- The model cannot select or execute an unregistered tool.
- Unauthorized access is rejected before tool execution.
- The Agent terminates deterministically on success, failure, timeout, or budget exhaustion.
- MCP clients can discover only the approved read-only surface.

### Milestone 7 — Delivery Package and FDE Evidence

Goal: demonstrate both engineering quality and customer delivery ability.

Deliverables:

- architecture and data-flow diagrams;
- discovery brief: users, workflow, pain point, constraints, success metrics, and non-goals;
- deployment, migration, backup, rollback, troubleshooting, and incident runbook;
- security and data-boundary statement;
- evaluation report and known limitations;
- short Chinese case study and concise English project explanation;
- reproducible demo covering ingestion, authorized answer, citation, refusal, permission rejection, feedback, and failure recovery;
- business-value section covering adoption, time saved, quality, latency, and unit cost without unsupported ROI claims.

Acceptance gate:

- A new reviewer can start, test, evaluate, and explain the project from repository documentation.
- Demo and portfolio claims match reproducible evidence.
- No credentials, customer data, internal-only links, or unsupported metrics are published.
- The project can be presented as an end-to-end customer deployment, not only as a framework demo.

## 6. Immediate Four-Week Focus

- Week 1: complete Milestone 1 and freeze the reproducible baseline.
- Week 2: implement the minimum identity, knowledge-base, document ACL, and lifecycle model from Milestone 2.
- Week 3: implement Milestone 3 and establish the 20-question dataset and runner.
- Week 4: publish the first evaluation report, decide whether Hybrid Search is justified, and complete the V0.1 delivery package.

Milestone 6 (Agent/MCP) starts only after the V0.1 security and evaluation gates pass. It is not required to declare enterprise knowledge-base V0.1 complete.

## 7. Deferred Scope

Deferred until a measured customer or evaluation need exists:

- fine-tuning;
- GraphRAG and Neo4j;
- complex multi-agent graphs;
- Kubernetes and multi-region architecture;
- local GPU procurement;
- voice/WebRTC agents;
- a full Python rewrite;
- autonomous write operations without human approval.

## 8. Progress Rules

Use these states in `PROGRESS.md`:

- `planned`: scope and acceptance criteria exist;
- `in-progress`: implementation or verification has started;
- `verified`: code, tests, runtime evidence, and documentation satisfy the gate;
- `blocked`: an external dependency prevents verification and the exact dependency is recorded.

Every verified milestone records:

- commit or release identifier;
- environment and commands executed;
- automated test results and runtime evidence;
- evaluation/demo artifact;
- security and data boundary;
- known limitations and next action.

## 9. Reference Decisions

- [Spring AI project and version compatibility](https://github.com/spring-projects/spring-ai)
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/)
- [pgvector: hybrid search with PostgreSQL full-text search](https://github.com/pgvector/pgvector)
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/17/ddl-rowsecurity.html)
- [OWASP Top 10 for LLM and GenAI Applications](https://genai.owasp.org/initiatives/top-10-for-llm-and-genai/)
