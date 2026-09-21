# Retrieval Fusion Benchmark v1 — Matched-Case Local Report (2026-09-21)

## Scope

- Cases: `evaluation/datasets/keyword_candidates_v1.jsonl`, 8 sanitized stress cases.
- Vector evidence: protected authenticated diagnostics capture from the same case/ACL fixture.
- Keyword evidence: normalized keyword candidates from `run_keyword_candidate_benchmark.py`.
- Fusion: document-level Reciprocal Rank Fusion (`rrf_k=60`), plus a bounded keyword-weight-2 variant.
- Context boundary: five unique document candidates.

This is a matched-case simulation, not an online Hybrid Search implementation. The vector capture used a larger candidate setting and the comparison collapses chunk hits to their best document rank; therefore these numbers are document-level evidence and should not be presented as a new production Top-K result. The keyword side remains offline. No backend query, migration, extension, or default configuration was changed.

## Result

| Method | Recall@1 | Recall@3 | Recall@5 | Avg first expected rank | Answerable context interference | Refusal cases with candidates | ACL leakage |
|---|---:|---:|---:|---:|---:|---:|---:|
| Vector document rank | 75.00% | 100% | 100% | 1.1667 | 76.67% | 2/2 | 0 |
| Normalized keyword document rank | 91.67% | 100% | 100% | 1.0000 | 74.07% | 2/2 | 0 |
| Equal-weight RRF | 75.00% | 100% | 100% | 1.1667 | 76.67% | 2/2 | 0 |
| Keyword-weight-2 RRF | 91.67% | 100% | 100% | 1.0000 | 76.67% | 2/2 | 0 |

The weighted RRF variant improves rank quality at Recall@1, but it does not recover a source that vector retrieval misses at Recall@5. Both the vector and keyword paths are ACL-filtered for the acting user, and the fused candidate set has zero forbidden-document leakage.

The refusal signal remains unresolved: both refusal cases still produce candidates. This means a production fusion layer would need a calibrated score threshold or an explicit no-evidence policy; simply unioning vector and keyword candidates would increase context pollution risk.

## Decision

Decision: **run a bounded online authenticated A/B comparison before implementing Hybrid Search**.

The online comparison must use the same request question, user/tenant/knowledge-base ACL, document/chunk corpus, and context budget. It should compare the current vector path with keyword-weight-2 fusion while recording:

1. source Recall@1/3/5 and citation correctness;
2. refusal correctness and context interference;
3. ACL leakage after fusion;
4. retrieval-stage and end-to-end P50/P95 latency;
5. token usage, estimated cost, provider failures, and structured-output failures.

The winner must pass the same 20-question Golden and 8-question Stress gates. Until then, the production default remains vector retrieval with Top-K=5; no BM25/pg_trgm migration, RRF runtime path, Top-K change, or Reranker is enabled.

## Reproduction

The raw diagnostics and keyword report remain outside Git. With those local artifacts available:

```bash
python3 evaluation/run_retrieval_fusion_benchmark.py \
  --diagnostics /private/tmp/rag-stress-native-4000-diagnostics-20260921.jsonl \
  --keyword-report /tmp/keyword-candidates-v1-local.json \
  --output /tmp/retrieval-fusion-v1-local.json
```

The fusion CPU operation was sub-millisecond in this small local corpus. That is not comparable with API or model latency and does not establish a production performance SLO.
