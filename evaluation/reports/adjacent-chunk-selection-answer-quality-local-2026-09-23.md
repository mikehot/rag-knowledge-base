# Adjacent Chunk Selection — Answer-Quality Candidate Diagnostic (2026-09-23)

## Scope and method

- Rebuilt all 12 `answer-quality-v1` cases against a fresh no-volume PostgreSQL 16.15/pgvector fixture with synthetic users and the fixture's document ACL grants. Captured up to 50 ACL-filtered vector candidates per question.
- Compared the existing first-Top-K vector context with a bounded adjacent-chunk substitution at the same context budget. For each query, the experimental selector considers only the initial Top-K hits, filename, `chunk#N` locator, and candidate rank. If an adjacent chunk from a seeded document appears within two ranks after K, it may replace the lowest-ranked selected chunk from another document; at most one replacement is made per query. It does not read expected answer points, source labels, chunk text, or model output when selecting.
- The chunk evidence scorer uses the dataset's expected point terms only after selection, to score contexts. Chunk text is fetched under the actor's ACL into process memory and is not serialized. This is candidate-level lexical evidence, not semantic or generated-answer quality.

## Results at equal context budget

| Selector / K=5 | Mean exact answer-point coverage (8 answerable cases) | Cases with all expected points | Mean non-evidence chunks | Mean non-evidence documents | Off-source point matches | ACL leakage |
|---|---:|---:|---:|---:|---:|---:|
| Existing vector Top-5 | 75.00% | 6/8 | 4.25 | 2.875 | 0 | 0 |
| Adjacent-chunk substitution | 87.50% | 7/8 | 4.00 | 2.25 | 0 | 0 |

- The only failed answerable case after the substitution was `QUALITY-002`: its expected FAQ chunks were not represented among the initial Top-5 seeds, so adjacency cannot recover a document absent from the selected context. `QUALITY-006` improved from 0/3 to 3/3 expected points because an FAQ chunk was already in Top-5 and its adjacent chunk ranked just outside the boundary.
- K=8 already yielded 100% exact point coverage (8/8) for both selection methods, with mean 5.875 non-evidence chunks; the adjacent selector made no changes at that budget in this fixture.
- The output remained ACL-trimmed with zero detected ACL leakage. The method is deterministic, so another run over the same fixture would not establish model-output repeatability.

## Decision and limitations

- This is a positive but narrow candidate-stage signal: same-budget exact point coverage rose by 12.5 percentage points, full-point cases by one, and measured lexical context noise fell slightly. It does not measure answer rubric pass rate, citations, refusal correctness, structured-output failures, model tokens, or generation latency because no chat completion was run.
- Keep the selector offline and keep production Top-K=5/VECTOR unchanged. Before considering runtime integration, add a feature-flagged backend experiment and run repeated full 12-case paired API captures, measuring answer quality, citations, refusals, ACL, tokens, latency, and structured-output failures. Q002 needs a distinct candidate-recall solution; adjacency alone is not sufficient.
- No Hybrid Search, Reranker, or default retrieval behavior was enabled. The temporary backend, database, credentials, manifest, candidate captures, evidence report, and uploaded files were removed; only aggregate evidence is retained here.

## Repeated backend API A/B (2026-09-23)

- Ran four full authenticated captures against a fresh disposable PostgreSQL 16.15/pgvector fixture: `VECTOR`, `VECTOR_ADJACENT`, then one repeat of each (12 cases per capture; 48 `/api/ask` requests total). The same employee identity, document ACL fixture, Top-K=5, model/provider settings, and backend build were used. `RAG_CONTEXT_SELECTION_EXPERIMENT_ENABLED=true` was set only on this local backend; the repository/default Compose value remains false.
- All 48 requests returned HTTP 200. The protected retrieval endpoint returned 48/48 diagnostics; persisted modes were `VECTOR` or `VECTOR_ADJACENT` as requested, all selected contexts had 5 hits, and no forbidden document appeared in candidate hits or citations. No Schema failures or ACL leaks were observed; refusal correctness was 100% in each run.

| Capture | Quality gate | Answerable pass | Expected-point coverage | Citation coverage / correctness | Main answerable failures |
|---|---:|---:|---:|---:|---|
| VECTOR 1 | 10/12 (83.33%) | 6/8 (75%) | 75.00% | 75% / 75% | QUALITY-002, QUALITY-006 |
| VECTOR 2 | 10/12 (83.33%) | 6/8 (75%) | 75.00% | 75% / 75% | QUALITY-002, QUALITY-006 |
| VECTOR_ADJACENT 1 | 9/12 (75%) | 5/8 (62.5%) | 83.33% | 87.5% / 75% | QUALITY-002, QUALITY-006, QUALITY-008 |
| VECTOR_ADJACENT 2 | 9/12 (75%) | 5/8 (62.5%) | 77.08% | 87.5% / 87.5% | QUALITY-002, QUALITY-005, QUALITY-006 |

- The offline lexical coverage signal did not translate into an end-to-end quality-gate gain: both adjacent runs scored one fewer passing case than both vector runs. The contexts changed for 6/12 cases in each paired capture. QUALITY-002 remained unanswered in every run. QUALITY-006 changed from `found=false` to `found=true` with the adjacent selector, but still failed complete answer-point coverage; QUALITY-005 or QUALITY-008 failed in one adjacent repeat each, so the regression cases were not stable across repeats.
- API `failureReason` counts were 11 `INSUFFICIENT_CONTEXT` plus 1 `STRUCTURED_OUTPUT_INVALID` over the 24 vector requests, and 10 `INSUFFICIENT_CONTEXT` over the 24 adjacent requests. The one structured-output failure occurred in VECTOR 1. This is a small local-provider sample, not a production latency/SLO or statistical significance claim; a cold first run affected observed latency, and token usage was unavailable in these captures.
- **Decision:** do not promote `VECTOR_ADJACENT`. Keep `VECTOR` and Top-K=5 as defaults; keep the new mode behind its dedicated flag (default false). The experiment is useful as a reproducible research branch, but it has not passed the end-to-end quality gate. Revisit only with a selector that avoids trading away useful cross-document evidence, then repeat the same full paired gate. Q002 still needs a separate rank-boundary recall solution.

Raw answers, request captures, credentials, manifests, and diagnostic payloads stayed in `/private/tmp` and were removed after aggregation. Only this minimized aggregate record is retained.

## Conservative source-preserving offline follow-up (2026-09-23)

- Rebuilt the 12-case employee ACL fixture in a fresh no-volume PostgreSQL/pgvector container. Captured VECTOR Top-7 diagnostics so the candidate pool included the production Top-5 plus two boundary chunks. The offline selector was given only protected document IDs, rank, and `chunk#N` locators; answer-point terms were applied only after selection.
- Compared the original Top-5, the earlier adjacent selector that may replace a different document, and a conservative alternative that replaces a chunk only when it is a redundant second hit from the same document. This preserves the Top-K document set by construction. All scoring used ACL-visible chunk text in memory; no model-generated answer contributed to this offline result.

| Context selection | Mean exact point coverage (8 answerable cases) | Full-point cases | Mean non-evidence chunks | Mean non-evidence documents | Document set preserved |
|---|---:|---:|---:|---:|---:|
| VECTOR Top-5 | 75.00% | 6/8 | 4.25 | 2.875 | baseline |
| Cross-document adjacent replacement | 87.50% | 7/8 | 4.00 | 2.25 | Not guaranteed |
| Same-document redundant-hit replacement | 75.00% | 6/8 | 4.25 | 2.875 | 12/12 cases |

- The conservative selector made no substitutions in this fixture: none of the 12 questions had a qualifying same-document redundant hit plus an adjacent candidate within the boundary window. It therefore preserved all represented documents but did not recover `QUALITY-006`; `QUALITY-002` also remained outside Top-5.
- **Decision:** no end-to-end API run is warranted for this candidate because the offline gate showed no context change or lexical-coverage gain. Keep the existing runtime experiment default-off and do not promote it. Further retrieval redesign is paused until a new candidate can demonstrate an offline benefit without dropping a unique source; continue with the planned Flutter operations UI and clean delivery/demo work.
- This is candidate evidence from one local fixture, not semantic answer quality. The temporary service, database, credentials, captures, diagnostic payloads, and chunk text were removed after aggregation.
