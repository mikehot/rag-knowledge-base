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

On 2026-09-23, the local Docker-backed backend suite passed 116 tests with no failures, errors, or skips. The latest recorded synthetic Golden run was 16/20 and the eight-case retrieval stress run was 7/8 under its documented LM Studio Thinking-off condition. Two corrected-rubric 12-case VECTOR captures each scored 10/12, with QUALITY-002 and QUALITY-006 failing in both. ACL leakage and structured-output failures were zero in the adjacent-strategy API comparison, but that candidate scored 9/12 versus 10/12 for VECTOR and was not promoted. A conservative source-preserving offline selector made no substitutions and left lexical answer-point coverage at 75% (6/8).

These are distinct local fixtures and evaluation configurations, not one combined benchmark, independent semantic judging, CI evidence from this date, customer accuracy, production SLA, or ROI. The local Gemma structured-output probe passed 6/6 only with LM Studio Thinking disabled; Thinking-on runs have shown completion-budget exhaustion. The setting remains an operator-owned local condition, not a production guarantee. A fresh disposable API test verified that revoking an employee's document ACL removes the document from both read-only search results and the document list (zero observed ACL leakage); a synthetic failed index task recovered through the authorized retry API. A separate manual PostgreSQL dump plus uploaded-file archive restored into a fresh disposable environment; restored ACL visibility, seven successful task records, readiness, and MCP smoke (16/16) passed. On 2026-09-24, a new continuous disposable API demo also covered authorized cited answers, out-of-scope refusal, permission denial, feedback, MCP smoke (16/16), and recovery from a persisted indexing failure at attempt 3 via authorized retry at attempt 4. The first natural wording of the warranty question missed the Top-5 boundary and was refused; a more document-aligned wording retrieved the FAQ at rank 3 and answered with a citation. This is functional evidence and exposes wording sensitivity; it does not resolve the known retrieval-quality gap or pass the quality gate. A synthetic LM Studio log-stream probe confirmed that model input and output are visible. A metadata-only inventory found 68 dated local server-log files from 2026-03-19 through 2026-09-24 with file mode `0644`; no file contents were inspected, so this does not establish whether model I/O is persisted there. Log content classification, effective user access, redaction, and retention/rotation controls remain unverified. The Flutter ACL/task screens pass analysis and model tests. An earlier 2026-09-23 attempt to install the isolated `.verify` build was blocked by `INSTALL_FAILED_USER_RESTRICTED`; on 2026-09-24 the isolated build was installed on Android 16/API 36 and the minimum administrator ACL/retry flows passed synthetic device acceptance. The employee question/citation flow and non-system-admin document-manager path were not repeated on device. Default retrieval remains VECTOR Top-K=5; Hybrid Search, reranking, autonomous writes, and a model-driven Agent loop remain disabled.

Follow-up on 2026-09-24: after the earlier installation restriction, an isolated `.verify` build was installed on an Android 16/API 36 device without replacing the everyday app. The Flutter administrator ACL flow changed a synthetic employee's document-list visibility from 5 to 6 and back to 5 after revoke. The indexing panel showed a synthetic failure at attempt 3/3, then a device-triggered safe retry succeeded at attempt 4/6 and restored the document to `ready`. Later, a disposable API check exposed that non-system document managers could read a document ACL but received 403 from the global admin user directory. A document-scoped, tenant-bound principal-candidate endpoint was added and passed API/PostgreSQL integration tests; the updated Flutter flow has not yet been exercised on-device. One post-revocation ask persisted `found=false`, but generation ended with `STRUCTURED_OUTPUT_INVALID`; retrieval snapshots excluded the revoked FAQ while containing other employee-authorized files. This is ACL retrieval evidence, not a successful refusal/citation UI acceptance. See the [device report](../evaluation/reports/flutter-operations-acl-index-task-device-local-2026-09-24.md) and [principal-directory report](../evaluation/reports/document-acl-principal-directory-local-2026-09-24.md).

## Five-to-ten-minute talk track

1. Start PostgreSQL/pgvector and the OpenAI-compatible provider; explain that the UI being open is not proof that the provider API is available. Do not expose LM Studio model-I/O logs in recordings: they can contain retrieved document content.
2. Upload `sample_faq.md` and show the durable indexing state moving to `ready`.
3. Ask an in-scope question and show `found`, `grounded`, backend-owned sources, request ID, timings, and token usage.
4. Ask an out-of-scope question and show the fail-closed refusal with no sources.
5. Submit feedback and explain that metrics and diagnostics are protected by role.
6. Run the read-only MCP smoke check and show the exact three-tool allowlist, identity-override rejection, and unauthenticated 401.
7. In a disposable environment, demonstrate Flutter ACL grant/revoke and the indexing-task retry path without exposing credentials or customer data. Keep the observed wording-sensitive Top-5 miss visible as a limitation; the verified Android flow used a synthetic administrator and did not repeat employee ask/citation checks on-device.
8. Close with the evidence boundary: this is a reproducible enterprise-AI project slice, not a claim of production scale or autonomous operations.

## Why the architecture matters

The project keeps Java/Spring Boot for business workflows, authorization, transactions, APIs, and deployment. Python is used for evaluation and experiments. This separates enterprise delivery concerns from model experimentation while keeping every quality or retrieval change measurable.
