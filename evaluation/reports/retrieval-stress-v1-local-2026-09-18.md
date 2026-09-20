# Retrieval Stress v1 Local API Evaluation Report

> This report stores aggregate evidence only. Credentials, raw responses, request IDs, and model-provider logs remain outside the repository.

## Run boundary

- Date: 2026-09-18 (Asia/Shanghai)
- Environment: local Spring Boot API, PostgreSQL 16.15 + pgvector, LM Studio OpenAI-compatible API
- Chat model: Gemma 4 26B; embedding model: Nomic Embedding
- Dataset: `evaluation/datasets/retrieval_stress_v1.jsonl`, 8 sanitized cases
- Fixture: the existing disposable tenant plus installation, support SLA, firmware release, and long operations documents
- Permissions: stress employee/auditor accounts received the stress-document grants; stress outsider did not
- Collection: `evaluation/run_api_eval.py`
- Scoring: `evaluation/run_eval.py --expected-case-count 8`; deterministic checks only

## Result

| Metric | Result |
|---|---:|
| Responses | 8 / 8 |
| Overall cases passed | 8 / 8 (100%) |
| Answerable pass rate | 6 / 6 (100%) |
| Expected answer-point coverage | 100% |
| Citation coverage | 100% |
| Citation correctness | 100% |
| Refusal correctness | 100% |
| ACL leakage count | 0 |
| Schema failure count | 0 |
| P50 API latency | 7.91 s |
| P95 API latency | 10.55 s |
| Maximum API latency | 13.91 s |
| Average recorded tokens | 1,755 |

## Retrieval observations

- All six answerable cases returned the expected source document in the Top-K source list.
- The long operations document was replaced successfully and indexed into 2 chunks; the stress responses retrieved both `chunk#1` and `chunk#2` for the relevant long-document questions.
- Several Top-K lists also contained distractor documents. The current deterministic contract checks that required sources are present, but does not treat the declared expected sources as an exhaustive relevance list. Ranking precision and similarity margins therefore remain unscored.
- The outsider case returned the fallback without leaking `release-notes-v2.md`.

## Fixture recovery evidence

The first fixture attempt exposed a real operational boundary: LM Studio had stopped, so four Embedding tasks entered `failed`. After the provider was restored, the existing document `reindex` API moved all four documents to `ready`. The fixture script also now treats an existing ACL grant as idempotent when the API envelope returns business code `409` over HTTP 400.

## Limits

- This is one local stress run against sanitized documents, not a production SLO or a large-corpus benchmark.
- Similarity scores and candidate rank are intentionally not exposed by the ordinary public API and were not guessed from citation presence; V10 now persists them for a protected `SYSTEM_ADMIN`/`AUDITOR` diagnostics endpoint. Recall@K and source relevance precision were not scored in this run.
- Local LM Studio has no cloud token price, so estimated monetary cost is not reported.
- No LLM-as-judge or independent semantic groundedness score was used.
