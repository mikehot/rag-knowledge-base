# Vector Candidate Expansion / Rerank Benchmark v1 (2026-09-22)

## Scope

This is a deterministic, candidate-level comparison over the ACL-filtered 50-candidate vector diagnostics. It does not call a model, does not read Chunk content, and does not modify `AskService`, Top-K, ACL predicates, or the default retrieval mode.

The selectors are:

- `vector_top_k`: current first five vector hits;
- `document_diversity_first`: select the first hit from each document, then fill remaining slots by vector order;
- `document_cap_one`: select at most one Chunk from each document.

## Result

### `retrieval-stress-v1` — 8 cases

| Selector | Answerable expected-source recall | Answerable context interference | Refusal cases with candidates | Candidate behavior pass | ACL leakage |
|---|---:|---:|---:|---:|---:|
| Vector Top-K=5 | 100% | 60.00% | 2/2 | 6/8 | 0 |
| Diversity first | 100% | 76.67% | 2/2 | 6/8 | 0 |
| Document cap one | 100% | 76.67% | 2/2 | 6/8 | 0 |

The stress set already had all expected answerable documents inside the current Top-5. Diversity did not improve candidate recall, while it increased irrelevant context and did not reduce refusal candidates. The two diversity selectors were identical on this small corpus.

### `QUALITY-002` — targeted case

| Selector | Expected source in context | Context interference | ACL leakage |
|---|---:|---:|---:|
| Vector Top-K=5 | No | 4/5 | 0 |
| Diversity first | Yes | 4/5 | 0 |
| Document cap one | Yes | 4/5 | 0 |

The diversity selectors recover `sample_faq.md` by skipping the second Chunk of `long-ops-manual.md` and admitting the rank-6 FAQ Chunk. This proves a plausible local mechanism for Q002, but it does not prove that the model will produce a better answer from a more polluted context.

## Decision

Keep the production Vector Top-K=5 path unchanged. The local strategy fixes one narrow ranking-boundary case but has no net candidate-level benefit on the stress set and increases context interference. Before any runtime reranker or diversity policy is considered, the next gate must use generated answers with the same Golden/Stress cases and compare citations, refusal correctness, latency, Token usage, and ACL leakage.

## Evidence boundary

- This is synthetic/local candidate evidence, not an answer-quality or production SLO result.
- Candidate recall does not establish grounded answer correctness.
- No model-generated text or Chunk content was written to the benchmark report.
