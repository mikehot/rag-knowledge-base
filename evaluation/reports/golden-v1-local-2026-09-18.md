# Golden v1 Local API Evaluation Report

> This report stores aggregate evidence only. Credentials, raw responses, request IDs, and model-provider logs remain outside the repository.

## Run boundary

- Date: 2026-09-18 (Asia/Shanghai)
- Environment: local Spring Boot API, PostgreSQL 16.15 + pgvector, LM Studio OpenAI-compatible API
- Chat model: Gemma 4 26B; embedding model: Nomic Embedding
- Dataset: `evaluation/datasets/golden_v1.jsonl`, 20 sanitized cases
- Fixture: disposable local tenant with one shared FAQ document and two ACL-protected policy documents
- Collection: `evaluation/run_api_eval.py`, repeated twice with the same fixture and configuration
- Scoring: `evaluation/run_eval.py` deterministic checks; no LLM-as-judge

## Result

| Metric | Run 1 | Run 2 |
|---|---:|---:|
| Responses | 20 / 20 | 20 / 20 |
| Overall cases passed | 20 / 20 (100%) | 20 / 20 (100%) |
| Answerable pass rate | 16 / 16 (100%) | 16 / 16 (100%) |
| Expected answer-point coverage | 100% | 100% |
| Citation coverage | 100% | 100% |
| Citation correctness | 100% | 100% |
| Refusal correctness | 100% | 100% |
| ACL leakage count | 0 | 0 |
| Schema failure count | 0 | 0 |
| P50 API latency | 6.34 s | 6.28 s |
| P95 API latency | 9.13 s | 9.17 s |
| Maximum API latency | 11.57 s | 9.91 s |
| Average recorded tokens | 1,266 | 1,265 |

The `found` flag and `(filename, locator)` source pairs were identical across all 20 cases in both runs.

## Interpretation

The current local baseline covers normal answers, out-of-scope refusal, and ACL-filtered refusal without a detected source leak. The first run exposed a multi-part completeness gap: the answer named the merchant's shipping responsibility but omitted the buyer's responsibility for non-quality returns. Adding an explicit prompt rule to cover every sub-question fixed that case in the second run, and the repeated run kept the same result.

The five other first-run misses were evaluator vocabulary gaps, not retrieval misses; the expected match terms now include the source's legitimate paraphrases. This is why the report keeps deterministic term matching separate from any future groundedness or semantic judge.

## Limits

- This is two local runs against sanitized sample documents, not a production SLO or a multi-day regression baseline.
- Local LM Studio has no cloud token price, so estimated monetary cost is not reported here.
- Recall@K, ranking quality across a larger corpus, concurrency, and independent model-based groundedness scoring remain open.
- Raw JSONL responses are intentionally not checked in; rerun the documented collector with external credentials on a disposable tenant when refreshing this evidence.
