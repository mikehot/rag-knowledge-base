# Enterprise RAG / FDE Delivery Roadmap

> Version: v2.0
>
> Last verified: 2026-09-24
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
- This milestone remains `in-progress`: the minimum management APIs, document ACL grant/list/revoke boundary, permission-denied audit, rollback-safe replacement upload, automated PostgreSQL ACL tests, cross-tenant cases, persistent batch reindex, retry, idempotency, task observability, and restart recovery are implemented with coverage. The Flutter ACL/index-task UI passed synthetic Android 16/API 36 acceptance for administrator grant/revoke, employee list visibility, failed-task display, and safe retry. A new document-scoped principal-candidate endpoint fixes the non-system document manager's 403 against global admin directories. On 2026-09-24, the isolated `.verify` device flow loaded USER/DEPARTMENT/ROLE candidates for an EMPLOYEE document manager; USER and Employee-role READ grant/revoke changed synthetic reader visibility as expected. The department grant path and post-revoke ask/citation are still untested. PostgreSQL integration covers tenant scoping and no-MANAGE denial. Secondary identity lifecycle operations and the next Docker-backed CI run remain open.

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
- Enforce a model-owned JSON contract containing only `answer`, `found`, `grounded`, and `sourceIndexes`; the OpenAI-compatible provider also sends a strict `response_format=json_schema` request. Backend-owned source metadata, request IDs, failure reasons, and timings are never accepted from the model. Invalid output and missing citations fail closed to a stable handoff response. Implemented locally on 2026-09-20.
- Add request/correlation IDs and stage-level timings for embedding, retrieval, and generation. Implemented and locally verified on 2026-09-18.
- Record model/provider, retrieval parameters, token usage, result status, and sanitized failure reason. Implemented in Flyway V6/V7 and verified locally and in GitHub Actions run `35299110979`.
- Add user feedback (`helpful`, `not_helpful`, optional sanitized reason). Implemented with tenant/user ownership checks, locally verified on PostgreSQL 16.15, and verified in GitHub Actions run `35300087644` with 51 tests / 0 skipped on 2026-09-18.
- Add health/readiness checks and operational metrics without logging sensitive content by default. Status-only liveness/readiness probes, SYSTEM_ADMIN/AUDITOR-protected Actuator/Prometheus endpoints, ask/index metrics, feedback submissions, persisted ACL denials, and configured-rate cost estimates are implemented; initial PromQL guardrails are documented in `OBSERVABILITY.md` and verified in GitHub Actions run `35321425249` with 62 tests / 0 skipped on 2026-09-18. Real 7-day baseline tuning remains pending.
- Stabilize normal, fallback, provider-error, timeout, validation, and permission-denied contracts. Provider timeouts now map to `EMBEDDING_TIMEOUT` / `GENERATION_TIMEOUT`; HTTP 400/401/403 semantics are covered by service and MockMvc tests and verified in GitHub Actions run `35319672140` with 61 tests / 0 skipped on 2026-09-18.
- On 2026-09-24, the OpenAI-compatible response's normalized `finish_reason` and completion-token count were added to the internal provider result. Explicitly interrupted and explicitly unrecognized finish reasons fail closed even if their text parses as complete JSON; absent finish reasons remain backward-compatible. Validation-failure logs contain only request ID and bounded provider metadata, never prompt/question/answer text. Synthetic regressions and the full Docker-backed suite pass (123 tests). A disposable post-revocation replay then ran three times under fixed Gemma/Thinking-off settings and returned `INSUFFICIENT_CONTEXT` each time with no ACL leakage; `STRUCTURED_OUTPUT_INVALID` did not recur, so the earlier one-off failure's root cause remains unknown. No raw output was retained; see `evaluation/reports/structured-output-termination-diagnostics-local-2026-09-24.md`.

Acceptance gate:

- Normal, fallback, timeout, provider-error, validation, and permission-denied paths have stable contracts.
- A request can be traced across API, retrieval, and model stages by `requestId`.
- Operators can distinguish retrieval miss, ACL rejection, provider failure, timeout, and validation failure.
- Feedback can be linked to the corresponding answer and evaluation sample.

### Milestone 4 — Twenty-Question Evaluation Baseline

Goal: replace subjective demos with reproducible evidence.

Current status (2026-09-21): `golden-v1` is checked in at `evaluation/datasets/golden_v1.jsonl`, and the separate `retrieval-stress-v1` set is checked in at `evaluation/datasets/retrieval_stress_v1.jsonl`. Two historical authenticated local API runs passed 20/20 golden cases; the 2400-token structured-output baseline recorded 16/20, while the isolated 4000-token/Top-K=8 candidate recorded 15/20 and four fail-closed `STRUCTURED_OUTPUT_INVALID` responses. The historical stress run passed 8/8, and the 4000-token candidate also passed 8/8 with 100% answer-point/citation/refusal metrics and zero ACL leakage, but with materially higher latency. Protected candidate-rank/similarity snapshots show Recall@1/3/5=75%/91.67%/100%; larger-corpus ranking evidence, cloud cost comparison, and independent model judging remain open. The checked-in default remains Top-K=5 and 2400 tokens.

Provider follow-up (2026-09-21): timeout classification is now shared across Chat, Embedding, and AskService and recognizes nested HTTP/socket timeout causes. LM Studio server logs showed Gemma reasoning exhausted the old 1200-token completion budget; native JSON Schema remains enabled and the checked-in `AI_MAX_TOKENS=2400` baseline is retained. The full 4000-token/Top-K=8 candidate and the 3200-token complex-question route both failed to beat the 2400 Golden baseline, so neither is enabled globally. The backend now also rejects contradictory `found=true` plus handoff-text or explicit source-missing responses; retrieval and provider stability remain ahead of MCP.

Evaluation fixture follow-up (2026-09-21): an existing `candidate-admin` account had been reused without reconciling its `SYSTEM_ADMIN` role, which made RAG-014/RAG-020 look like retrieval failures after ACL trimming. The fixture preparer now idempotently assigns requested/default actor roles. With corrected roles, Top-K=5/2400 achieved 16/20 and exposed one real Top-5 miss (RAG-014); Top-K=8 recovered that source but fell to 15/20 because of context interference and higher token usage. The default remains Top-K=5; a bounded keyword/full-text experiment is the next retrieval step.

Answer-quality extension follow-up (2026-09-21): `answer-quality-v1` adds 12 focused cases without changing the frozen `golden-v1` contract. The deterministic runner now accepts optional `quality_rules` for minimum answer-point coverage, grounded output, unexpected citation limits, and fail-closed refusal. Dataset validation and positive answer/refusal quality-gate checks pass locally; the 12-case real API capture remains the next evidence step.

Answer-quality A/B follow-up (2026-09-21): the 12-case backend-owned VECTOR/KEYWORD_RRF comparison completed with HTTP 12/12 and ACL leakage 0 for both modes. After correcting the `QUALITY-001` matcher and adding legitimate natural-language variants for Q002/Q005, the initial VECTOR/KEYWORD_RRF quality-gate pass was 10/12 versus 10/12; Keyword-RRF improved answerable retrieval Recall@5 from 87.5% to 100% and citation coverage from 75% to 87.5%, but increased average Token usage from 1522.58 to 1805.50 and produced two `STRUCTURED_OUTPUT_INVALID` failures. A later prompt-hardening capture scored 10/12 versus 11/12, but remains a small local-model sample. The next work is to fix the identified retrieval and generation failure categories, not to enable Hybrid Search by default.

Structured Output retry follow-up (2026-09-21): the backend now supports one configurable retry after invalid model JSON through `AI_STRUCTURED_OUTPUT_RETRIES` (default `1`), merges retry Token/latency accounting, and preserves the existing fail-closed fallback when retries are exhausted. It also rejects `found=true` answers that explicitly admit source facts are missing. Targeted and full backend tests pass with 100 tests and 0 skipped. A clean-limit local smoke capture recovered one previously invalid multi-part answer, but model output variance prevented a stable overall quality gain; the next evidence gate remains a repeatable answer-completeness baseline.

Multi-part answer follow-up (2026-09-21): database inspection confirmed `QUALITY-006` has all relevant return/exchange rules in one ACL-visible Chunk. The AskService prompt now explicitly requires numbered one-condition-per-item answers for questions containing `是否/能否/吗`, `多久/多少`, or `由谁`; the Prompt regression test passes. The fresh 12-case capture after this change kept VECTOR at 10/12 with Q002/Q006 still `INSUFFICIENT_CONTEXT`; KEYWORD_RRF reached 11/12 after the scorer accepted natural expressions, but Q006 still omitted the non-quality shipping responsibility. This is useful diagnosis, not stable quality improvement, so repeated captures remain required.

Keyword follow-up (2026-09-21): PostgreSQL `simple` FTS does not provide reliable Chinese phrase matching for this fixture. A disposable `pg_trgm`/`word_similarity` experiment can rank the target Chunk when query phrases are normalized, but raw-question similarity is noisy. The next retrieval task is an offline keyword-candidate benchmark with ACL-preserving candidate recall and context-interference measurement; no database extension or runtime hybrid path is enabled yet.

Offline keyword benchmark follow-up (2026-09-21): `keyword-candidates-v1` now compares raw CJK n-grams with generic-question-word normalization without reading answer-point terms. Both variants reached Recall@5=100% on the eight-case stress fixture and ACL leakage=0, matching the current vector Recall@5=100% reference; normalized keyword reduced answerable context interference to 74.07% but still returned candidates for both refusal cases. This establishes the candidate-stage measurement boundary, not a reason to enable Hybrid Search. The next gate is a same-request vector-plus-keyword comparison with fused ranking, ACL verification after fusion, retrieval latency, and token/cost impact.

Fusion simulation follow-up (2026-09-21): a matched-case document-level RRF simulation using the protected stress diagnostics and normalized keyword candidates shows keyword-weight-2 can improve Recall@1 from 75% to 91.67%, while Recall@5 remains 100% and ACL leakage remains zero. Both refusal cases still produce candidates. This is enough to justify a bounded authenticated online A/B experiment, but not enough to add a database extension or enable Hybrid Search in the default path.

Online candidate A/B follow-up (2026-09-21): the real authenticated `/api/ask` vector capture completed 8/8 HTTP requests with P50/P95 latency of 12130.93/18165.33 ms and average token usage of 1428.25; one refusal behavior failed because `STRESS-007` returned `found=true`. The keyword and keyword-weight-2 RRF candidates were ranked only after applying the same tenant, knowledge-base, READY, lifecycle, owner, role, department, and document-ACL boundary. Recall@1 improved from 75% to 91.67%, Recall@5 stayed at 100%, and ACL leakage stayed at zero. Because the fused candidate context was not injected into `AskService`, generated-answer, citation, structured-output, token, cost, and end-to-end failure comparisons remain unverified. Keep the default vector path unchanged; the next gate is a backend-owned, flag-controlled A/B.

Backend-owned A/B follow-up (2026-09-21): `X-RAG-Retrieval-Mode: keyword-rrf` now routes through the same `AskService` generation, citation validation, failure classification, feedback ownership, and protected retrieval diagnostics as the default VECTOR path, but only when `RAG_HYBRID_EXPERIMENT_ENABLED=true`. V11 persists `VECTOR` or `KEYWORD_RRF` in `ask_log`; the default remains disabled. The keyword side reuses server-side ACL filtering and a bounded in-memory scorer aligned with the versioned candidate benchmark, so this is an evaluation switch, not a production full-text index. The next gate is a full 8-case paired capture and end-to-end comparison.

Backend-owned paired capture follow-up (2026-09-21): the full eight-case VECTOR/KEYWORD_RRF capture completed with HTTP 8/8, behavior/citation contract failures 0/0 for both modes, and ACL leakage 0/0. Keyword-RRF improved Recall@1 from 75% to 91.67%, Recall@3 from 91.67% to 100%, and kept Recall@5 at 100%; answerable context interference stayed 60% and both refusal cases still had candidates. The local API P95 was 16103 ms for VECTOR and 13815 ms for KEYWORD_RRF, with average tokens 1423.375 and 1429.25. These are small-sample LM Studio results, not production SLO or cost evidence; keep the flag disabled and expand answer-quality evaluation before choosing an indexed production implementation.

Stability capture follow-up (2026-09-21): `evaluation/run_stability_eval.py` provides a bounded repeat collector and `evaluation/summarize_stability_eval.py` provides a redacted aggregate for selected answer-quality cases. A local disposable `demo.employee` credential was prepared with the fixture ACL, `SUPPORT` department, and `EMPLOYEE` role; Q002/Q006 were each captured three times in VECTOR and KEYWORD_RRF. All 12 targeted requests were HTTP 200/API code 0: VECTOR stably returned `INSUFFICIENT_CONTEXT` for both cases, while KEYWORD_RRF stably returned `STRUCTURED_OUTPUT_INVALID` for both. This is targeted employee-scoped evidence, not a full 12-case quality score or a reason to enable Hybrid Search. The tools omit answers and request IDs from the summary and keep credentials/raw captures outside Git. After the run, the default Hybrid flag was restored to false.

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

Current local evidence:

```text
python3 evaluation/run_eval.py
dataset_valid: true
case_count: 20
behavior_counts: ANSWER=16, ACL_FILTERED_REFUSAL=2, REFUSE=2
```

The checked-in aggregate report is the live local result; it does not represent a production SLO or independent semantic judge.

Current quality status (2026-09-23): the evaluation datasets and deterministic scoring pipeline are implemented, but this means the **measurement baseline** is verified, not that answer quality passes a release gate. The latest corrected-rubric VECTOR answer-quality capture scored 10/12 in two paired-baseline repeats; ACL leakage and schema failures were zero in those captures. A local Thinking-off set reported Golden 16/20 and stress 7/8 under its recorded configuration. These runs use different datasets/configuration contexts and must remain separate; none is a production SLO or independent semantic judge.

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

Current decision (2026-09-23): the backend-owned `VECTOR_ADJACENT` experiment completed two full 12-case API comparisons and scored 9/12 twice versus VECTOR 10/12 twice; it remains behind a dedicated default-off flag. A follow-up source-preserving offline selector made no substitutions on the current fixture and did not improve point coverage (75%, 6/8), so it did not proceed to API evaluation. No Hybrid Search, Reranker, diversity, or adjacent strategy is approved for the default path. `QUALITY-002` remains a Top-5 boundary miss; `QUALITY-006` remains unresolved end-to-end. Do not add another retrieval component until an offline candidate shows a measurable same-budget benefit without discarding unique document evidence.

### Milestone 6 — Read-Only Agent Tools and MCP

Goal: extend a secure, measured knowledge system into a bounded Agent delivery.

Current status (2026-09-22): the application-owned read-only Tool Registry is implemented at `GET /api/agent/tools` and `POST /api/agent/tools/execute`. It exposes only `search_knowledge`, `list_documents`, and `get_document_status`; calls inherit the authenticated tenant/user context, reject unknown arguments, cap each batch at three calls, and write allow/deny/error outcomes to the existing audit table. Unit coverage includes ACL denial, empty result, timeout, unknown tool, invalid arguments, document filtering, status reads, and budget exhaustion. Real HTTP verification against PostgreSQL/LM Studio confirmed the three definitions, successful list/search/status calls, authentication, unknown-tool rejection, identity-override rejection, and batch-limit rejection. A minimal stateless MCP `2026-07-28` adapter is now available at `POST /mcp`; it exposes only `server/discover`, `tools/list`, and `tools/call`, requires the protocol/routing headers, and delegates tool execution to the existing registry. Full MCP transport/auth conformance, a model-driven loop, sessions, Tasks, Resources, Prompts, and writes remain deliberately deferred.

Initial read-only tools:

- `search_knowledge(query, knowledgeBaseId)`
- `list_documents(status, knowledgeBaseId)`
- `get_document_status(documentId)`

Work:

- Add typed Tool Calling behind the implemented application-owned allowlist/registry.
- Pass authenticated user, tenant, role, and request context outside model-controlled arguments.
- Validate arguments and enforce ACL again before execution.
- Bound tool calls, loop iterations, wall-clock time, tokens, and result size.
- Add timeout, partial-failure, unknown-tool, invalid-argument, and budget-exhaustion tests.
- Expose only approved read-only capabilities through MCP after the internal tool boundary is stable.
- Target the current stateless MCP transport with explicit protocol/routing headers and private cache hints for ACL-dependent tool catalogs.
- Keep write operations disabled until explicit approval, audit, idempotency, and rollback exist.

Acceptance gate:

- The model cannot select or execute an unregistered tool.
- Unauthorized access is rejected before tool execution.
- The Agent terminates deterministically on success, failure, timeout, or budget exhaustion.
- MCP clients can discover only the approved read-only surface.
- MCP requests cannot bypass JWT authentication, tenant/user context, closed schemas, ACL checks, the three-call budget, or existing audit records.

### Milestone 7 — Delivery Package and FDE Evidence

Goal: demonstrate both engineering quality and customer delivery ability.

Current status (2026-09-24): the first evidence-bounded delivery package is now
checked in under `docs/`: architecture/data-flow diagrams, discovery brief,
demo script, deployment/operations runbook, and Chinese/English case-study
material. Disposable API flows and minimum Flutter ACL/index-task interactions
have been exercised locally, including on an isolated Android device. A
sanitized recording, public deployment, and real customer operating baseline
remain separate evidence gates.

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
- The project can be presented as a reproducible end-to-end enterprise RAG capstone on synthetic/disposable data, not as a customer deployment or production operation.

## 6. Immediate Four-Week Focus (updated 2026-09-24)

- Week 1: **committed and pushed** — reconciled the pre-existing 24-file change set, preserved the user-owned experiment/test/report changes, kept V13 and retrieval experiments default-off, and made evaluator-verification vs. quality-gate status explicit. Core delivery commit: `a7d7568`.
- Week 2: **minimum API and administrator/non-system-manager device acceptance passed; bounded follow-ups remain** — on Android 16/API 36, the isolated `.verify` app granted and revoked employee READ access through Flutter, with employee list visibility changing 5→6→5. The same UI showed a controlled index failure at attempt 3/3; after restoring LM Studio, the UI retry reached SUCCEEDED at attempt 4/6 and the document returned to ready. A follow-up device run verified that a non-system `EMPLOYEE` document manager can load same-tenant USER/DEPARTMENT/ROLE candidates; USER and Employee-role grant/revoke toggled a synthetic reader's visibility. Department grant/revoke and employee ask/citation remain untested.
- Week 3: **API and minimum device delivery slice passed; provider log policy remains open** — an empty disposable API demo exists (2026-09-22), Android upload-to-READY was verified (2026-09-23), ACL/task UI acceptance and manual database+upload-directory restore passed. On 2026-09-24, a continuous API demo passed authorized citation, refusal, permission denial, feedback, failure/retry recovery, and MCP 16/16; a synthetic live LM Studio log-stream probe confirmed input/output visibility. A separate persistence probe found both synthetic input/output markers in newly appended bytes from one local server-log, confirming persistence of at least part of model I/O; it read only appended bytes and retained no raw text. The log root was subsequently restricted to owner-only mode 0700 (existing log files remain 0644), and the mode remained 0700 after the user's normal app quit/reopen; directory-recreation persistence remains unverified. A sandbox loopback probe reported the server stopped, but the user's screenshot and a host-permission `lms server status`/`GET /v1/models` recheck confirmed the desktop app and API server were running; do not treat the sandbox-only result as a host outage. Backend diagnostics now distinguish missing from explicitly unrecognized finish reasons and fail closed on abnormal/unknown explicit termination, with sanitized failure-only metadata; the full Docker-backed suite passes 123 tests. The prior post-revocation failure still needs a controlled LM Studio replay. Metadata inventory found 68 dated local server-log files from 2026-03-19 to 2026-09-24; complete content classification, app-managed access/rotation, redaction, and retention remain unverified. One happy-path wording miss remains a retrieval-quality limitation, not a passed benchmark. The restore test is not production backup/PITR evidence.
- Week 4: Chinese/English Case Studies and Demo notes now include the minimum UI acceptance and current quality limitations. A sanitized recording and separate public-hosting go/no-go remain pending provider-log and clean-delivery gates; public hosting is not required to claim local verification.

The retrieval candidate experiment is complete for now and did not yield an acceptable runtime change. Reopen it only when a new offline hypothesis passes the same-budget source-preservation gate. Milestone 6 extensions beyond the existing read-only tools/MCP adapter remain deferred until the V0.1 security, quality, and delivery gates are stable.

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
