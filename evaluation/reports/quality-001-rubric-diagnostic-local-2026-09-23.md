# QUALITY-001 Rubric Diagnostic — Local Evidence (2026-09-23)

## Scope

- Investigated the low-battery reminder point in `QUALITY-001` after the disposable end-to-end report marked the case failed.
- Used the complete synthetic `sample_faq.md` as one fixed context and the locally loaded Gemma 4 model with LM Studio `Enable Thinking=off`.
- Compared the current answer prompt with a variant that explicitly says question-mark-separated clauses must each be answered. Requests were interleaved 3 per condition and used the backend's strict JSON Schema, model ID, and 2400-token limit.
- Evaluated only contract fields and declared answer-point terms in memory. No response text or credentials were written to disk or emitted in logs.

## Results

| Measure | Current prompt | Question-clause variant |
|---|---:|---:|
| Strict JSON/schema valid | 3/3 | 3/3 |
| `found=true`, grounded, source 1 cited | 3/3 | 3/3 |
| Battery type and duration terms covered | 3/3 | 3/3 |
| Existing `battery-alert` match_any phrase hit | 0/3 | 0/3 |

A separate three-call check with a compact equivalent prompt found both low-battery language and an App/application notification/reminder expression in all 3/3 answers using a deliberately broad lexical proxy. One additional response matched the specific safe synonym `通过 App 推送`, which is consistent with the question's reminder clause and the fixture source `低电量 App 推送提醒`.

## Interpretation and change

The direct fixed-context diagnostic shows that natural paraphrases can occur, but it did not establish that the live `/api/ask` failure was only a rubric false negative. The prompt variant did not improve the exact-term score. `answer_quality_v1.jsonl` now accepts the observed phrase `通过 App 推送`; a deterministic regression test verifies that a complete grounded answer using that phrase passes all three QUALITY-001 points. No production prompt, answer contract, retrieval, or Top-K behavior changed.

## Corrected-rubric end-to-end gate

- Re-ran all 12 `answer-quality-v1` cases once through authenticated `/api/ask` against a fresh disposable PostgreSQL fixture, with vector retrieval, Top-K=5, the local Gemma model, and LM Studio Thinking off. All 12 returned HTTP 200 with no schema failures; no ACL leakage was observed.
- The corrected rubric passed 7/12 cases overall (58.33% quality-gate pass rate); answerable cases passed 3/8 (37.5%). Expected answer-point coverage was 61.46%, citation coverage/correctness 75%, and refusal correctness 100%.
- Failures: `QUALITY-001`, `QUALITY-002`, `QUALITY-003`, `QUALITY-005`, `QUALITY-006`. `QUALITY-001` still missed `battery-alert` despite retrieving/citing the expected source. `QUALITY-002` and `QUALITY-006` returned grounded=false with no expected source; `QUALITY-003` missed the tampering point; `QUALITY-005` missed the return-window point.
- Mean API latency was 1,520.75 ms, median 1,251 ms, and mean token usage 1,581.75. This is one local capture, not a stable baseline.
- This capture is not directly comparable to the older 9/12 report because the rubric changed. It also shows that the synonym update alone did not resolve `QUALITY-001` in the real retrieval/generation path; classify the five live failures before changing prompts, retrieval defaults, or the historical baseline.
- LM Studio `Enable Thinking` was restored to its original `on` state after the run. The disposable database/backend and external credentials/captures were temporary; only sanitized aggregate results are retained here.

## Failure classification from retained evidence

| Case | Current-run signal | Corroborating evidence | Classification / confidence |
|---|---|---|---|
| `QUALITY-001` | Expected source cited; battery-alert point missed. | Earlier protected chunk diagnostic placed all battery points at candidate rank 4; prior targeted API replay classified this as an answer-completeness miss. The new accepted synonym did not change the outcome. | Generation/completeness omission, high confidence; exact latest wording is not retained. |
| `QUALITY-002` | `found=false`, `grounded=false`, expected source absent. | Earlier ACL-aware capture ranked both answer-point chunks at 7; Top-K=5 failed twice, while Top-K=8 was non-repeatable. | Retrieval boundary miss is primary; generation varies when more context is supplied, high confidence from repeated evidence. |
| `QUALITY-003` | Expected source cited and 3/4 answer points matched; `warranty-tamper` was not matched. | Fixture contains the tampering exclusion in the warranty section. The prior raw response was intentionally deleted, so the scorer cannot distinguish semantic omission from an unlisted paraphrase. | Answer-point coverage/scorer discrepancy, unresolved between omission and paraphrase; medium-low confidence. |
| `QUALITY-005` | Expected source cited and 1/2 answer points matched; `return-window` was not matched. | Fixture contains the seven-day return rule. The prior raw response was intentionally deleted, so semantic omission versus paraphrase cannot be determined from aggregate output. | Answer-point coverage/scorer discrepancy, unresolved between omission and paraphrase; medium-low confidence. |
| `QUALITY-006` | `found=false`, `grounded=false`, expected source absent. | Earlier ACL-aware capture ranked the three answer-point chunks at 6; Top-K=5 failed twice, while Top-K=8 was non-repeatable. | Retrieval boundary miss is primary; generation varies when more context is supplied, high confidence from repeated evidence. |

The scorer is exact substring matching after whitespace normalization. Therefore a missing point ID is evidence that none of the declared `match_any` terms appeared; it is not by itself proof that the answer was semantically wrong. This matters especially for Q003 and Q005 because the raw answer from the latest run was not retained.

## QUALITY-003 / QUALITY-005 focused replay

- Rebuilt a disposable PostgreSQL/pgvector fixture and ran only these two authenticated `/api/ask` cases three times each with vector retrieval, default Top-K=5, the same Gemma model, and Thinking off.
- Both cases passed 3/3 runs: HTTP/API success 6/6, schema failures 0, expected-source citation 6/6, answer-point coverage 100% in every run, ACL leakage 0.
- Q003 consistently covered all four warranty exclusions, including `私自拆解`; Q005 consistently covered the seven-day no-reason return window and both conditions (`不影响二次销售`, `配件齐全`). The answers used the existing rubric terms, so no synonym expansion is justified.
- This does not prove the earlier full-set misses were solely sampling variance: the earlier raw answers and exact ranked context were intentionally not retained. It does show those misses were not reproducible in three focused runs under the same request/model/Top-K path and that the current rubric correctly scores representative complete answers.
- LM Studio `Enable Thinking` was restored to `on`; the temporary backend, database, uploads, credentials, manifests, and response captures were removed. No raw answer text is retained in this report.

## Second full corrected-rubric API gate

- Repeated the full 12-case authenticated API evaluation against a fresh disposable PostgreSQL/pgvector fixture, with vector retrieval, Top-K=5, and Thinking off.
- All 12 requests returned HTTP 200; 10/12 passed the corrected quality gate (83.33%), with 6/8 answerable cases passing. Expected-point coverage was 75%; citation coverage/correctness were 75%; refusal correctness was 100%; schema failures and ACL leakage were both 0.
- Only `QUALITY-002` and `QUALITY-006` failed. Both returned `found=false`, `grounded=false`, no sources, and `INSUFFICIENT_CONTEXT`; the failure occurs after generation, not from HTTP, JSON Schema, or ACL leakage.
- `QUALITY-001`, `QUALITY-003`, and `QUALITY-005` passed this full run. Combined with Q003/Q005 focused 3/3 repeats, their earlier single-run misses are not reproducible under the tested condition. Q001 also changed from failing to passing between the two complete corrected-rubric runs.
- Mean API latency was 1,479 ms, median 1,216 ms, and mean token usage 1,581. These local figures are descriptive, not production targets.
- Across the two complete runs with the corrected rubric, totals were 7/12 then 10/12; per-case outcomes changed for Q001/Q003/Q005 while Q002/Q006 failed both times. This is evidence of per-case non-repeatability for the former group and a repeated failure signal for Q002/Q006—not evidence that the overall quality gate is stable.
- The older 9/12 score used an earlier rubric and is not directly comparable. The second run does not authorize updating that historical record or enabling a different retrieval strategy.
- LM Studio Thinking was restored to `on`; the temporary database/backend, credentials, answer captures, manifest, and uploaded fixture files were removed. Only aggregate results remain here.

## Limits and next gate

- The provider replay and the single corrected-rubric API capture answer different questions; neither alone establishes a repeatable score.
- The broad paraphrase proxy is a diagnostic signal, not a semantic judge.
- Q003/Q005 have three passing targeted repeats and pass in the second full run; Q001 also passes in the second full run. Keep the rubric unchanged for these cases. Q002/Q006 failed both corrected-rubric full runs and have prior candidate ranks 7 and 6, respectively. Next, verify those candidates against the current disposable fixture's protected retrieval metadata and answer-point chunk evidence, then test one bounded offline/context-selection hypothesis against the full set; do not change Top-K=5 or enable Hybrid/Reranker without repeated aggregate quality evidence. The answer-quality gate is not yet stable enough for Flutter ACL UI acceptance.
- The unsaved LM Studio preset was not saved.

## Fresh QUALITY-002 / QUALITY-006 isolated diagnostic

- Rebuilt the fixture in a new PostgreSQL/pgvector container with no persistent volume and reran only QUALITY-002/QUALITY-006 through authenticated `/api/ask` using VECTOR, Top-K=5, and Thinking off. Both returned HTTP 200; schema failures 0 and ACL leakage 0. This targeted run returned `STRUCTURED_OUTPUT_INVALID` for both, unlike the prior two full-set runs that returned `INSUFFICIENT_CONTEXT`; do not treat the failure classification as stable across runs.
- The protected retrieval diagnostics for these request IDs showed 5 candidates per query and the live Top-5 omitted `sample_faq.md` for both. The ACL-filtered offline vector candidate query found 7 visible chunks: for QUALITY-002, the FAQ answer-point chunk was rank 7 (similarity 0.526589; rank-5 similarity 0.536526); for QUALITY-006, its answer-point chunk was rank 6 (similarity 0.515066). The QUALITY-006 FAQ chunk at rank 3 did not contain the expected points.
- Metadata-only chunk evidence confirmed Top-5 point coverage of 0/2 and 0/3. Selecting all 7 visible chunks (the measured K=8/K=10 budgets saturate at 7) raised both to 2/2 and 3/3, respectively. This is lexical fixture evidence, not a semantic quality result or authorization to raise production Top-K.
- At that expanded context size each case selected 7 chunks, of which 6 had no declared expected answer point, spanning 4 non-evidence documents. ACL leakage remained 0. The isolated result reinforces that target chunks rank just outside K=5, while also showing substantial context noise and an unstable generation/contract failure mode.
- No runtime retrieval setting or code changed. The disposable DB, backend, credentials, fixture uploads, and raw captures were removed; only aggregate ranks, counts, and failure classifications are retained. The Mac was locked during initial cleanup; after the user unlocked it, LM Studio UI showed `Enable Thinking=on`, matching the original state. No toggle was needed.

## Updated next gate

- Do not raise Top-K, enable Hybrid Search, or enable Reranker based on candidate-point coverage alone. First run a bounded offline context-selection comparison on the full answer-quality set (for example, preserve ACL-filtered vector candidates, then select evidence-bearing chunks under the same context budget) and compare point coverage, citation correctness, refusal correctness, context-noise count, tokens, latency, structured-output failures, and ACL leakage against the unchanged Top-K=5 path. Require repeatability before any runtime change.
