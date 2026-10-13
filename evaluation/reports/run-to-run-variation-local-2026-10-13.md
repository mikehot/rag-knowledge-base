# Run-to-run variation on corpus-v1 — local, 2026-10-13

Why: two corpus-v1 "gains" (Top-K=8, 1000/150 chunks) were reported from single runs and turned out to be
inside the variation of an unchanged configuration. `run_repeated_eval.py` was written so every result carries
its own spread; this report is its first use.

## Setup

| Item | Value |
|---|---|
| Models | `google/gemma-4-26b-a4b-qat` (Thinking off) + `text-embedding-bge-m3` via LM Studio |
| Configuration | VECTOR, Top-K=5, chunk 700/100, citation mode REPAIR, safety-rule prompt |
| Data | corpus-v1, 57 questions (45 answerable), one database, fixture built once |
| Settings | 3 runs each: fixed order · shuffled order (seed 11) · backend restarted before each run; then the same with `AI_TEMPERATURE=0` |

## Result

| Setting | Answerable /45 per run | Range | Flaky cases |
|---|---|---|---|
| fixed order, one backend process | 43, 43, 43 | 43–43 | CORP-003, CORP-040 |
| shuffled order | 44, 42, 44 | 42–44 | CORP-003, CORP-040 |
| restart before each run | 44, 44, 43 | 43–44 | CORP-003 |
| `AI_TEMPERATURE=0`, shuffled (same orders as above) | 44, 43, 44 | 43–44 | CORP-042 |
| `AI_TEMPERATURE=0`, restart before each run | 44, 44, 44 | 44–44 | none |

Refusals were 12/12 and ACL leakage 0 in all 15 runs. CORP-035 (cross-document) failed in every run.

## What this shows

- **Identical totals hide flipping cases.** The fixed-order runs all scored 43, yet two cases passed in some of
  them and failed in others. Compare per-case pass rates, not only totals.
- **Variation is small and real.** Across the nine default-temperature runs here, plus the earlier 44, 44, 44,
  43, and 41, an unchanged configuration spans 41–44 answerable questions (3 cases). The 41 occurred once in
  about twenty runs and was not reproduced in these fifteen.
- **Temperature 0 helps but is not shown to remove the variation.** Its two settings had 6 runs of 43–44 and
  one flaky case in total, against 9 runs of 42–44 and two or three flaky cases. `compare` says
  `WITHIN VARIATION` for shuffled default vs shuffled temperature 0 (ranges 42–44 and 43–44). Three runs each
  cannot establish a difference of this size.
- **Order matters even at temperature 0** (shuffled runs differ, restart runs did not), so the remaining
  variation is not only sampling; batching and prompt-cache state in the local server are plausible but were
  not isolated.
- **Flaky cases are mostly wording.** CORP-003 flips between "无法回滚" and "无法进行回滚" under a substring
  rubric; that is scoring sensitivity, not a retrieval or grounding change.

## Consequences

- Quote ranges. A difference of up to 3 answerable cases on a 45-question set needs more than 3 runs per side
  before it is called a change; `compare` never reports a win from a single run.
- The optional `AI_TEMPERATURE` setting was added (default unchanged: the provider's own default). For
  evaluation runs set it to 0, which narrowed the spread here without a measurable quality change.
- Not measured: whether temperature 0 changes answer quality for users. Do not change the default on this
  evidence.
