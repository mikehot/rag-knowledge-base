# QUALITY-006 and the answer-quality gate — local, 2026-10-13

## What QUALITY-006 was

Question: 非人为质量问题多久可以换货，退换货运费由谁承担？ Expected: 15-day exchange window, the merchant pays
shipping for quality problems, **and** the buyer pays for non-quality problems. All of it sits in one bullet of
one chunk of `sample_faq.md`.

| Embedding | What happened |
|---|---|
| nomic-embed-text-v1.5 | `INSUFFICIENT_CONTEXT` every run: the chunk holding section four was not retrieved (the known chunk-mixing miss) |
| bge-m3 | Retrieved correctly. The answer was **incomplete**: "质量问题由商家承担运费" without the buyer half, in 3 of 3 runs of one session; complete in 5 of 5 runs of a later session |

So on the recommended embedding the problem is not retrieval and not the rubric (the missing clause really is
missing); it is a borderline completeness failure.

## Cause found while isolating it

With the prompt text, the retrieved chunks, and `temperature=0` fixed, **only the random per-request boundary
token** was varied (24 requests each, direct calls to the model with the backend's prompt):

| Prompt | Complete answer |
|---|---|
| baseline | 10/24 and 16/24 in two batches (26/48, about 54%) |
| baseline + completeness rule (below) | 24/24 |

A random string that carries no meaning flipped the answer, so the same question with the same evidence gave
a complete answer on one request and an incomplete one on the next. That is both a user-visible
inconsistency and a source of evaluation noise at temperature 0.

## Changes

1. **Deterministic boundary.** The boundary is now an HMAC-SHA256 of (question, retrieved chunk ids) under a
   server secret (derived from the configured JWT secret; random per process when none is configured). It is
   still unpredictable to a document author, but identical input now yields an identical prompt.
2. **Completeness rule** added to the prompt: when a rule gives different results per case ("质量问题……，
   非质量问题……"), every case must be written out even if the question names only one.

## Evidence for the prompt rule

| Set | Baseline | With the rule | Notes |
|---|---|---|---|
| answer-quality + golden + stress (40 cases × 5 random boundaries) | 184/200 | **190/200** | QUALITY-006 2/5 → 5/5, RAG-010 2/5 → 5/5, no case worse |
| corpus-v1, held out (57 cases × 3 random boundaries) | 167/171 | 168/171 | one case differs (CORP-044 2/3 → 3/3), none worse; 28 multi-part questions |
| Variants tried | | | "check at the end" 187/200 and "rule + check" 188/200 were worse (STRESS-008 and RAG-014 dropped) |

The rule was written after seeing QUALITY-006, so its gain on that case is selection-biased; RAG-010 and the
held-out corpus are the independent signals, and they are modest. The experiments used an ad-hoc script that
is not committed.

## Repeated runs through the backend (`run_repeated_eval.py`)

Same backend, fixture, and models; `AI_TEMPERATURE=0`; baseline = main before this change.

| Set | Baseline | Candidate |
|---|---|---|
| answer-quality-v1 ×5 | 11/12 each run (always failing: QUALITY-001) | 11/12 each run, same |
| answer-quality-v1 ×3, backend restarted before each run | — | 11/12 each run |
| golden-v1 ×3 | 19/20 (always failing: RAG-013) | 19/20, same |
| stress-v1 ×3 | 8/8 | 8/8 |

`compare` reports `WITHIN VARIATION` for every pair. The baseline passed QUALITY-006 in this session, so this
comparison cannot show the improvement; it shows no regression and no flaky cases.

## Gate record for this configuration

Frozen gate (`evaluation/README.md`): rubric-v2, three consecutive full 12-case captures each ≥ 10/12,
ACL leakage 0, schema failures 0, probe passing, mean tokens within +15% of the recorded baseline, golden and
stress not worse.

| Item | Value |
|---|---|
| Chat / embedding | `google/gemma-4-26b-a4b-qat` (Thinking off) / `text-embedding-bge-m3`, LM Studio |
| Retrieval | VECTOR, Top-K=5, threshold 0.35, chunk 700/100, `AI_MAX_TOKENS=2400`, `AI_TEMPERATURE=0`, citation mode REPAIR |
| Probe | 4/4 `contractValid`, `finishReason=stop` |
| answer-quality-v1 captures | 8 consecutive captures (5 + 3 with restarts), each **11/12**; ACL leakage 0, schema failures 0 |
| golden-v1 / stress-v1 | 19/20 and 8/8 in all 3 runs each (nomic baseline: 15–17 and 7–8) |
| Mean tokens per question | 1786 vs 1584 for the recorded baseline: +12.7% |

**The gate passes for this configuration. It does not pass for the shipped default** (nomic embedding,
provider-default temperature), which still scores 8–10/12 and fails on Q002/Q006.

## Limits

- One machine, one chat model, one embedding model; eight captures over three backend processes.
- Before this change the same bge-m3 setup failed the gate in an earlier session (9, 10, 10 with Q001, Q002,
  Q006 failing). The fixes are a prompt rule and a deterministic boundary, and `AI_TEMPERATURE=0`; their
  separate contributions were not isolated, and a later session passing does not prove the earlier failures
  cannot recur.
- QUALITY-001 and RAG-013 fail in every run because of answer wording against a substring rubric (the answers
  contain the facts). The rubric was not changed: that would create rubric-v3 and break comparability.
- The completeness rule costs about 50 prompt tokens.
