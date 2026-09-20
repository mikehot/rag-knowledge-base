# Retrieval Stress v1 — Local API Report (2026-09-20)

## Scope

- Environment: local Spring Boot API, PostgreSQL 16.15 + pgvector, LM Studio OpenAI-compatible API
- Chat model: `google/gemma-4-26b-a4b-qat`
- Embedding model: `text-embedding-nomic-embed-text-v1.5`, 768 dimensions
- Dataset: `evaluation/datasets/retrieval_stress_v1.jsonl`, 8 sanitized cases
- Fixture: disposable tenant with installation, support SLA, firmware release, and long operations documents
- Diagnostic path: Flyway V10 `ask_retrieval_hit` plus admin-only retrieval diagnostics endpoint

## Result

The latest full corrected-model run (v5, default `AI_MAX_TOKENS=2400`) passed 8/8 cases:

| Metric | Result |
|---|---:|
| Overall cases | 8/8 |
| Answerable pass rate | 6/6 |
| Expected answer-point coverage | 100% |
| Citation coverage | 100% |
| Citation correctness | 100% |
| Refusal correctness | 100% |
| ACL leakage | 0 |
| Schema failures | 0 |
| Latency P50 | 13.59s |
| Latency P95 / max | 18.44s / 19.65s |
| Average token usage | 2,173 |

`STRESS-003`, the cross-document question that previously failed, passed with both expected answer points and both expected citations. The local Gemma provider needed a larger completion budget because its reasoning output consumed most of the old 1200-token limit; with `AI_MAX_TOKENS=2400`, the final visible answer content is returned instead of an empty or truncated message.

## Protected retrieval diagnostics

The V10 diagnostic collector and scorer were run against all eight request IDs:

| Metric | Result |
|---|---:|
| Recall@1 | 75.00% |
| Recall@3 | 91.67% |
| Recall@5 | 100% |
| Average first expected-source rank | 1.1667 |
| Average Top-1 similarity | 0.7098 |
| Average Top-1 margin | 0.0307 |
| Average candidate count | 4.625 |
| ACL leakage | 0 |

`STRESS-003` retrieved the installation document at rank 1 and the support SLA document at rank 4. `STRESS-008` retrieved the support SLA document at rank 2. The current Top-K=5 is therefore sufficient for this small stress fixture, while the low Top-1 margin shows that the candidate set contains distractors and should remain observable before introducing more retrieval components.

## Failure and recovery note

The first run after restarting the services returned `EMBEDDING_ERROR` for all 8 cases because the environment used the stale model ID `text-embedding-nomic-embed-text`. LM Studio exposed `text-embedding-nomic-embed-text-v1.5` and accepted a 768-dimensional embedding request. The backend was restarted with the verified chat and embedding IDs; the defaults in `application.yml`, `docker-compose.yml`, and the runbook were then synchronized.

The earlier 8/8 stress report remains a separate historical run. This report is the current evidence for the V10 diagnostics path, corrected model identifiers, and the 2400-token local completion budget.

## Targeted generation follow-up

After the first 7/8 run, the generation prompt was tightened for multi-part questions: the model is asked to decompose the question into numbered items, preserve concrete conditions and values, and avoid empty phrases such as “确认” or “注意检查”. Three consecutive `STRESS-003` requests were then run against the same local fixture:

- 2/3 responses included both the installation-gap point and all three remote-opening checks;
- 1/3 returned `GENERATION_ERROR` without an answer;
- this targeted run was not a replacement full-suite run.

The subsequent full v3 run still produced one `GENERATION_ERROR` for the same case. Increasing the completion budget to 2400 and rerunning the full suite produced 8/8; the prompt-level completeness guard and the provider budget now have a reproducible local pass. Generation failures remain separately tracked from retrieval quality and do not justify adding BM25, Hybrid Search, or Reranker.

## Provider classification hardening

After v3, the shared `AiCallException` classifier was hardened to recognize direct or nested `HttpTimeoutException` and `SocketTimeoutException` causes. Chat, Embedding, and `AskService` now use the same timeout rule; focused tests cover direct timeout, nested socket timeout, wrapped `AiCallException`, and ordinary provider failure. This change does not retroactively change the v3 result; a follow-up real run is required to determine whether LM Studio's 61-second failure is classified as `GENERATION_TIMEOUT` or remains an ordinary provider error.

One post-hardening real `STRESS-003` request completed normally with HTTP 200, `found=true`, no failure reason, 25.38s generation time, and five returned sources. The final default-budget full v5 run then passed all 8 cases, including both STRESS-003 answer points.

## Completion budget correction

LM Studio server-level logs showed the old 1200-token budget ending with `finish_reason=length` while the Gemma response had empty or truncated visible `message.content` and most completion tokens spent in reasoning. The default is now 2400 in `application.yml`, `docker-compose.yml`, and the backend runbook. The v5 full run is the acceptance evidence for this change.

## Evidence boundary

- The report is local-provider evidence, not a production SLO.
- Recall is document-level and calculated from protected Top-K snapshots, not inferred from answer citations.
- No BM25, Hybrid Search, or Reranker was added. The current evidence does not justify that work yet.
- Raw responses, request IDs, credentials, and diagnostics remain outside the repository.
