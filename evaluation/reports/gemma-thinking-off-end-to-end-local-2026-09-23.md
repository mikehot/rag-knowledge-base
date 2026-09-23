# Gemma Thinking-Off End-to-End Evaluation — Local Evidence (2026-09-23)

## Scope and setup

- Provider: LM Studio OpenAI-compatible API, `google/gemma-4-26b-a4b-qat`.
- LM Studio `Enable Thinking` was off for this diagnostic run; project defaults and provider request settings were not changed.
- Backend was started from the current source against a fresh, no-volume PostgreSQL/pgvector container and a temporary upload directory. Synthetic fixture users/documents were provisioned only there. The existing development database was not used.
- Captured the versioned `golden-v1` (20), `answer-quality-v1` (12), and `retrieval-stress-v1` (8) API datasets, plus protected retrieval diagnostics for the stress set.
- Raw answer captures, credentials, fixture manifest, and per-case reports were kept outside Git under `/private/tmp` and removed after scoring; this report contains only aggregate results and failing case IDs.
- Scores use the repository's deterministic evaluator, not an LLM judge. Latency is local-device/provider evidence, not an SLO. Small sample percentiles are descriptive only.

## Results

| Dataset | Passed | Answerable pass rate | Citation coverage / correctness | Answer-point coverage | Refusal correctness | ACL leakage | Schema failures | Mean API tokens | Median / observed p95 / max HTTP latency |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `golden-v1` | 16/20 | 75.00% | 81.25% / 81.25% | 79.17% | 100% | 0 | 0 | 1553.75 | 1.84 / 3.12 / 4.54 s |
| `answer-quality-v1` | 9/12 | 62.50% | 75.00% / 75.00% | 70.83% | 100% | 0 | 0 | 1583.08 | 1.37 / 2.12 / 2.36 s |
| `retrieval-stress-v1` | 7/8 | 83.33% | 100% / 100% | 91.67% | 100% | 0 | 0 | 1475.00 | 2.13 / 3.17 / 3.17 s |

The answer-quality rubric gate passed 9/12 (75%). Protected retrieval diagnostics passed 8/8, with document Recall@1 75%, Recall@3 91.67%, Recall@5 100%, mean first expected-source rank 1.17, and zero forbidden-document leakage. The diagnostic candidate pool averaged 4.63 documents. `STRESS-003`'s two expected documents were both present by rank 5; `STRESS-008`'s expected source was at rank 2.

## Failures to investigate

- Golden answer failures: `RAG-003`, `RAG-010`, `RAG-013`, `RAG-014`.
- Answer-quality failures: `QUALITY-001`, `QUALITY-002`, `QUALITY-006`.
- Stress answer failure: `STRESS-008` (retrieval source existed at rank 2; generated answer missed one expected point).
- No schema-invalid responses, refusal errors, or ACL leakage were observed in these three captures.

The `answer-quality-v1` failures show that passing strict JSON is not sufficient: the current end-to-end answer-quality gate is not met. The perfect stress Recall@5 and `STRESS-008` rank-2 source point to answer completeness/selection for that case rather than a missing expected document. Other failures still need per-case retrieval-vs-generation diagnosis before choosing a retrieval change. Do not infer that BM25, Hybrid Search, a reranker, or a higher Top-K is warranted from this single run.

## Targeted failed-case diagnostic replay

After the aggregate run, the seven failed Golden/quality cases were replayed against a newly provisioned disposable fixture. The deterministic rubric failures recurred in all seven. Their protected Top-5 snapshots and ACL-aware vector candidates (up to rank 50) were checked; only filenames, chunk locators, ranks, and similarity metadata were used, not chunk text.

| Case | API/answer evidence on targeted replay | First expected document rank in ACL-visible vector candidates | Classification |
|---|---|---:|---|
| `RAG-003` | `INSUFFICIENT_CONTEXT`; FAQ chunk#2 appeared at Top-5 rank 5 | 5 | Document-level presence at the boundary does not establish that the returned chunk contains the door-thickness fact; chunk-level miss remains possible. |
| `RAG-010` | `INSUFFICIENT_CONTEXT`; FAQ chunk#2 at rank 3 | 3 | Likely model abstention despite the expected document being present; metadata alone cannot prove chunk-content sufficiency. |
| `RAG-013` | Grounded answer, but omitted the offline-pairing rubric point; FAQ chunks at ranks 2 and 3 | 2 | Answer-completeness/generation omission with the expected document in context. |
| `RAG-014` | `INSUFFICIENT_CONTEXT`; no FAQ chunk in Top-5 | 7 | Confirmed document-level Top-5 recall miss in this fixture/run. |
| `QUALITY-001` | Grounded answer, but omitted low-battery App-push detail; FAQ chunks at ranks 1 and 4 | 1 | Answer-completeness/generation omission with the expected document in context. |
| `QUALITY-002` | `INSUFFICIENT_CONTEXT`; no FAQ chunk in Top-5 | 6 | Confirmed document-level Top-5 recall miss in this fixture/run. |
| `QUALITY-006` | `INSUFFICIENT_CONTEXT`; FAQ chunk#2 at rank 3 | 3 | Likely model abstention despite the expected document being present; chunk-content sufficiency is unverified. |

No forbidden documents were returned for these actors. This confirms a mixed failure set: two repeatable Top-5 source misses, at least two answer-completeness misses with the expected file represented in Top-5, and two abstentions where document-level evidence is present but chunk-level relevance is not proven. The document-level stress Recall@5 metric must not be mistaken for chunk-level evidence coverage.

## Decision

- Keep model/provider defaults, Top-K=5, and experimental retrieval modes unchanged; do not proceed to Flutter ACL UI yet.
- Preserve fail-closed Structured Output and keep the LM Studio setting out of project runtime defaults. `Enable Thinking=off` is a local diagnostic condition, not yet a reproducible deployment configuration.
- Next: add or collect chunk-level expected-evidence diagnostics for the failed FAQ cases, without exposing chunk text in reports; then evaluate one bounded candidate-selection/context test against the same failed cases and full answer-quality set. Keep runtime retrieval defaults unchanged until a repeatable aggregate quality improvement is demonstrated. Only after the answer-quality gate is stable should the Flutter ACL UI decision be revisited.

## Cleanup and evidence boundary

The one-off backend process was stopped and both exact temporary database containers were removed. LM Studio's unsaved `Enable Thinking` setting was restored to its original on state and verified in the UI. Temporary evaluation credentials, manifests, response captures, diagnostics, and upload files were removed from `/private/tmp`. No project code, online default, or existing development database was changed. This is local disposable evidence, not production readiness, public deployment, or a customer outcome.
