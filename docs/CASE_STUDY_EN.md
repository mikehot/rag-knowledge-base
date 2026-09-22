# Enterprise RAG Knowledge Base — Project Explanation

## One-line summary

I evolved a Java/Spring Boot RAG MVP into a governed enterprise knowledge-base slice with PostgreSQL/pgvector, tenant-aware ACLs, citations, structured output validation, durable indexing tasks, evaluation evidence, and a bounded read-only Tool/MCP surface.

## Customer problem

Employees need answers across internal documents, but an enterprise system must also answer: who is allowed to see each document, where did the answer come from, what happens when indexing or the model fails, and how can operators measure latency, tokens, feedback, and access denials?

## What I built

- Document ingestion for PDF, DOCX, TXT, and Markdown.
- Versioned parsing, chunking, embeddings, pgvector retrieval, and persistent indexing tasks.
- JWT tenant/user context, department/role/knowledge-base membership, and document ACL filtering before retrieval.
- A strict structured-answer contract. The model returns answer metadata and source indexes; the backend validates the contract and owns final citations.
- Fail-closed handling for retrieval misses, provider timeouts, invalid JSON, missing citations, and insufficient context.
- Audit events, request IDs, stage timings, token/cost metrics, user feedback, health/readiness probes, and protected retrieval diagnostics.
- Three read-only tools and a stateless MCP adapter. Identity, tenant, ACL, call budget, and audit behavior remain server-owned.

## Evidence and limitations

The local Docker-backed backend suite passed 109 tests with no failures, errors, or skips. The versioned stress set has an 8/8 historical local baseline with zero ACL leakage, and the read-only MCP smoke check passed 16/16 checks. These are repository and local-fixture results, not production SLA, customer accuracy, or ROI claims.

The current local models have not passed the independent structured-output capability gate. The default retrieval path therefore remains vector Top-K=5; Hybrid Search, reranking, complex model routing, autonomous writes, and a model-driven Agent loop remain disabled.

## Five-to-ten-minute talk track

1. Start PostgreSQL/pgvector and the OpenAI-compatible provider; explain that the UI being open is not proof that the provider API is available.
2. Upload `sample_faq.md` and show the durable indexing state moving to `ready`.
3. Ask an in-scope question and show `found`, `grounded`, backend-owned sources, request ID, timings, and token usage.
4. Ask an out-of-scope question and show the fail-closed refusal with no sources.
5. Submit feedback and explain that metrics and diagnostics are protected by role.
6. Run the read-only MCP smoke check and show the exact three-tool allowlist, identity-override rejection, and unauthenticated 401.
7. Explain the employee ACL fixture and the retry/recovery path without exposing credentials or customer data.
8. Close with the evidence boundary: this is a reproducible enterprise-AI project slice, not a claim of production scale or autonomous operations.

## Why the architecture matters

The project keeps Java/Spring Boot for business workflows, authorization, transactions, APIs, and deployment. Python is used for evaluation and experiments. This separates enterprise delivery concerns from model experimentation while keeping every quality or retrieval change measurable.
