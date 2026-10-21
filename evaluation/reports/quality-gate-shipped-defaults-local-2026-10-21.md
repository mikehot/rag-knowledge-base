# Quality gate re-run on the shipped defaults — local, 2026-10-21

Question: after bge-m3 and `AI_TEMPERATURE=0` became the shipped defaults (commit `32c3c91`), does the
answer-quality gate still hold, and do golden and stress still score what they scored on 2026-10-13?

## Configuration

| Item | Value |
|---|---|
| Code | branch `chore/default-bge-m3-temp0` at `a86e1c2` plus the `EVAL_FIXTURE` option in `scripts/eval_stack.sh` |
| Models | `google/gemma-4-26b-a4b-qat` (Thinking off, checked by the stack preflight; structured-output probe 4/4) and `text-embedding-bge-m3`, LM Studio |
| Backend | VECTOR, Top-K 5, threshold 0.35, chunk 700/100, `AI_TEMPERATURE=0`, `AI_MAX_TOKENS` 2400, citation mode REPAIR, deterministic prompt boundary |
| Fixture | `EVAL_FIXTURE=synthetic` (sample_faq plus the synthetic policy and stress documents), disposable pgvector database, one backend process for all runs |
| Tool | `evaluation/run_repeated_eval.py run`, question order fixed |

The stack passes the model ID and temperature to the backend explicitly, so this run validates the evaluated
configuration, which the shipped defaults now equal. It did not start a backend with those variables unset.

## Result

| Set | Runs | Result per run | Always failing | ACL leakage |
|---|---:|---|---|---:|
| answer-quality-v1 | 8 | 11/12 (7/8 answerable, 4/4 refusals), range 11–11 | QUALITY-001 | 0 |
| golden-v1 | 3 | 19/20 (15/16 answerable, 4/4 refusals) | RAG-013 | 0 |
| retrieval-stress-v1 | 3 | 8/8 (6/6 answerable, 2/2 refusals) | none | 0 |

The gate (3 consecutive captures at ≥ 10/12, ACL leakage 0, schema failures 0) holds. The numbers equal the
2026-10-13 record (8 captures at 11/12, golden 19/20, stress 8/8), and the two always-failing cases are the
same two. No backend errors in the log.

## Limits

- All runs of a set are identical by construction: one backend process with a deterministic prompt. This
  confirms the score, not the run-to-run spread; restarts were not tried here.
- Same small synthetic sets, one machine, one day. The 2026-10-14 reproduction gap on corpus-v2 (43 → 38/45)
  is a separate open question this run does not address.
- Token usage was recorded (mean 1792 / 1746 / 1908 per request for the three sets) but not compared against the
  2026-10-13 `+12.7%` figure, which was measured against a nomic baseline.
