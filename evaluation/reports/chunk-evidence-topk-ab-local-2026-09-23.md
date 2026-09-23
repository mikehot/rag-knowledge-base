# Chunk Evidence and Top-K A/B — Local Disposable Evidence (2026-09-23)

## Scope

- Used the existing `golden-v1` and `answer-quality-v1` cases but limited chunk diagnostics to the seven cases that failed in the preceding Gemma run.
- Created a fresh, no-volume PostgreSQL/pgvector Compose service and synthetic API fixture. All retrieval queries used the fixture actor's tenant, knowledge base, and ACL predicates.
- Added `run_chunk_evidence_diagnostic.py`. It joins ACL-filtered Top-50 vector chunk IDs/ranks with chunk text fetched into process memory. It writes only chunk IDs, filenames/locators, expected-point IDs, ranks, and aggregate coverage; chunk text and `match_any` terms are never written.
- Evidence score is exact normalized presence of a dataset `match_any` term in a chunk from an `expected_source_documents` file. Off-source lexical matches are reported separately and do not count. This is a deterministic lexical indicator, not a semantic judge.
- Compared context budgets K=5/8/10 offline, then ran the complete 12-case `answer-quality-v1` API set twice at Top-K=5 and twice at Top-K=8 against the same disposable corpus, model, provider settings, and ACL fixture. The local `Enable Thinking=off` condition was held constant. No project default was changed.

## Chunk evidence on the seven previously failed cases

| Context K | Mean expected-source exact evidence-point coverage | Cases with every point lexically present | Mean non-evidence chunks | ACL leakage |
|---:|---:|---:|---:|---:|
| 5 | 28.57% | 2/7 | 4.57 | 0 |
| 8 | 100% | 7/7 | 6.00 | 0 |
| 10 | 100% | 7/7 | 6.00 | 0 |

The expected FAQ evidence ranks in the current disposable vector capture were: `RAG-003` door-thickness point at rank 7; `RAG-010` all four return/shipping points at rank 6; `RAG-013` all three offline points at ranks 2–3; `RAG-014` both expected points at ranks 7–9; `QUALITY-001` all battery points at rank 4; `QUALITY-002` both installation points at rank 7; `QUALITY-006` all three return/shipping points at rank 6. Two cases also had lexical point-term matches in non-expected files; these were explicitly excluded from evidence coverage.

This shows why document-level Recall@K was insufficient for the failure cohort. It does not prove that a larger context is semantically better: K=8 adds, on average, six chunks without an exact expected-point match in this seven-case set, and some non-evidence chunks can still contain relevant context not represented by the scorer.

## Full answer-quality API A/B

| Top-K | Repeat | Rubric pass | Answerable pass rate | Answer-point coverage | Citation coverage / correctness | Refusal correctness | ACL leakage | Schema failures | Mean API tokens/case |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 1 | 10/12 (83.33%) | 75.00% | 75.00% | 75% / 75% | 100% | 0 | 0 | 1845.67 |
| 5 | 2 | 10/12 (83.33%) | 75.00% | 75.00% | 75% / 75% | 100% | 0 | 0 | 1968.75 |
| 8 | 1 | 11/12 (91.67%) | 87.50% | 93.75% | 100% / 100% | 100% | 0 | 0 | 2086.58 |
| 8 | 2 | 9/12 (75.00%) | 62.50% | 85.42% | 100% / 100% | 100% | 0 | 0 | 2086.75 |

Across the two captures, both settings averaged 10/12 rubric passes and a 75% answerable pass rate. Top-K=8 averaged 89.58% answer-point coverage versus 75% at K=5, with citation coverage/correctness at 100% in both K=8 runs versus 75% in both K=5 runs. However, K=8's rubric pass count varied from 11/12 to 9/12, and its failed case IDs changed between repeats. Mean API tokens were 2086.67 at K=8 versus 1907.21 at K=5 (about 9.4% higher). Local latency varied between sequential runs and is not used to claim a performance win.

## Decision

- Keep production/default Top-K=5. The exact-evidence coverage increase at K=8 did not produce a repeatable increase in overall rubric pass rate; it used more tokens and admits substantially more non-evidence chunks.
- Do not enable Hybrid Search, a reranker, document diversity, or any other retrieval change from this result.
- Retain the chunk-level diagnostic as evidence only. The next quality investigation should separate prompt/model omissions from candidate omissions on the stable K=5 failures, and any future retrieval candidate must pass repeated full-set answer-quality, citation, refusal, ACL, token, and latency checks.

## Cleanup and evidence boundary

The temporary backend and no-volume Compose database were stopped/removed; temporary credentials, manifest, answer captures, chunk diagnostic output, and uploads were removed from `/private/tmp`. Existing development database state was not touched. The local LM Studio `Enable Thinking` setting was restored to on after the evaluation. This is local synthetic evidence, not production readiness or a deployment claim.
