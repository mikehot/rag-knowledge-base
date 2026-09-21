# Keyword Candidate Benchmark v1 — Local Offline Report (2026-09-21)

## Scope

- Dataset: `evaluation/datasets/keyword_candidates_v1.jsonl`, 8 sanitized retrieval cases.
- Corpus: `sample_faq.md` plus the six sanitized fixture documents used by `prepare_api_fixture.py`.
- Variants: raw question-derived CJK character n-grams/ASCII tokens versus generic-question-word normalization plus phrase bonus.
- Context boundary: current `Top-K=5`; candidate pool limit: 10.
- Provider/database: none. This is a deterministic Python benchmark and does not call the API, model, or PostgreSQL.

The dataset contains only the question, expected source document IDs, actor/ACL context, forbidden document IDs, and expected behavior. The runner rejects `expected_answer_points` and `refusal_match_terms`, so model answer words cannot influence the candidate score.

Run it with:

```bash
python3 evaluation/run_keyword_candidate_benchmark.py \
  --iterations 20 \
  --output /tmp/keyword-candidates-v1-local.json
```

## Result

| Variant | Recall@1 | Recall@3 | Recall@5 | Avg first expected rank | Answerable context interference | Refusal cases with candidates | ACL leakage | Offline p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Raw char n-gram | 91.67% | 100% | 100% | 1.00 | 75.86% | 2/2 | 0 | 0.028 / 0.031 ms |
| Normalized keyword | 91.67% | 100% | 100% | 1.00 | 74.07% | 2/2 | 0 | 0.155 / 0.167 ms |

ACL filtering was applied before scoring. On average, 2.5 of the seven corpus documents were removed for each actor query, and neither variant returned a forbidden document.

The normalized variant reduced average candidate count and context interference slightly, but it did not improve source recall. The two refusal cases still produced candidates: the ACL-filtered case returned a visible but irrelevant FAQ candidate, and the out-of-scope CRM question returned several generic-term matches. This is a real context-pollution signal, not an ACL leakage signal.

## Comparison and decision

The current checked-in vector stress report records Recall@5 = 100% and ACL leakage = 0 for the same eight-case stress fixture. Therefore this offline keyword benchmark does not show unique source recovery that would justify changing the production retrieval path.

Decision: **do not enable Hybrid Search yet**.

The benchmark is now the candidate-stage gate. The next retrieval experiment, if needed, must execute vector and keyword candidates for the same authenticated request and compare:

1. unique expected-source recovery at the same Top-K/context budget;
2. refusal and context-interference rate;
3. ACL leakage after fusion, not just before keyword scoring;
4. retrieval-stage P50/P95 and end-to-end latency;
5. token/cost change and failure behavior.

No PostgreSQL extension, migration, BM25 path, RRF fusion, Top-K change, or Reranker was added by this benchmark.

## Evidence boundary

- The benchmark is document-level and synthetic/local; it is not a production SLO.
- Offline keyword CPU latency is not directly comparable with the vector report's end-to-end API latency.
- The corpus is intentionally small, so the benchmark proves the measurement boundary and ACL behavior, not large-corpus search scalability.
- The checked-in production default remains vector retrieval with Top-K=5.
