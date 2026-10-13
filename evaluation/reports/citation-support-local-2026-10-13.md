# Citation support check — design, calibration, replay, local, 2026-10-13

## Problem

The backend guaranteed that a cited index exists and that citations are built from retrieved metadata, but
not that the cited chunk **contains what the answer says**. In earlier runs a correct answer was shown next to
the wrong evidence (RAG-008, RAG-004, INJ-001, CORP-033): the model cited retrieved chunk #1 while the chunk
that held the fact was #2.

## What was built

`CitationSupport` (pure, deterministic, no model call). Support is the share of the answer's content units
(digits, Latin identifiers, Chinese character bigrams without function characters, Chinese numerals
normalised to digits, list numbering removed) that occur in the chunk text.

| Situation | Outcome | Action |
|---|---|---|
| Cited chunks cover at least 60% of the answer's units | `SUPPORTED` | none |
| Less than 60%, and a set of at most 3 retrieved chunks covers at least 60% and gains at least 20 points | `REPAIRED` | cite that set instead (backend-chosen, rank order) |
| No retrieved chunk covers even 30% | `UNSUPPORTED` | `ENFORCE`: hand off with `UNSUPPORTED_ANSWER`; otherwise unchanged |
| Otherwise | `UNCHANGED` | keep the model's citations |

Mode `RAG_CITATION_SUPPORT_MODE` = `OFF` | `REPAIR` (default) | `ENFORCE`. Outcomes are counted in
`rag.citation.support{outcome}`. The unit is lexical on purpose: it can tell whether the answer's words come
from the evidence, not whether the answer is complete or true.

## Calibration (offline, before choosing thresholds)

278 correct answers captured from corpus-v1 runs (all with chunk text and retrieval snapshots):

| Measure | Value |
|---|---|
| Support of the cited chunks, correct answers | median 0.95, 10th percentile 0.80, minimum 0.11 |
| Support of the union of all retrieved chunks, correct answers | minimum 0.50 |
| Support of the union, answers that were found but missed points (31) | median 0.92, minimum 0.38 |

Correct and incomplete answers overlap almost completely, so support cannot rank answer quality; it only
finds citations that do not contain the answer. The one cited-support outlier (0.11) was CORP-033, the
wrong-citation case above.

## Replay of the real implementation

The shipped Java class was run over 521 captured answered responses from 15 corpus-v1 runs (several
retrieval modes, nomic and bge-m3), using each run's retrieval snapshot and chunk text.

| Outcome | Count |
|---|---|
| `SUPPORTED` | 512 |
| `UNCHANGED` | 6 |
| `REPAIRED` | **3** (evidence in the cited chunks 0 → 1.0, 0.5 → 1.0, 0 → 1.0) |
| `UNSUPPORTED` | 0 |

No repair made a citation worse. Fully-evidenced citations went from 472 to 475 of 521.

## Live runs (bge-m3, corpus-v1, Top-K=5, 700/100)

| Mode | Answerable /45 | `UNSUPPORTED_ANSWER` | Citation correctness |
|---|---|---|---|
| OFF | 41 | 0 | 0.933 |
| REPAIR | 44 | 0 | 1.000 |
| ENFORCE | 43 | 0 (not triggered) | 1.000 |

**Do not read the 41 → 44 as an effect of the check.** The OFF run's extra failures (CORP-023, CORP-026) were
`CITATION_MISSING` produced by the model before the support check runs, and the same questions were answered
differently in other runs. An unchanged configuration scored 44, 44, 44, 43, and 41 across backend restarts,
so up to 3 cases is run-to-run variation. The replay above is the controlled comparison: same model output,
two policies.

## Limits

- The effect is small on this corpus: the failure mode was 3 of 521 answers (0.6%) here, and 3 of 98 on the
  earlier 7-document fixture. It protects the "every answer cites its evidence" promise rather than raising
  scores.
- `ENFORCE` was never triggered, so its false-rejection rate on other corpora is unknown; offline it would have
  rejected 0 of 278 correct answers. It is off by default for that reason.
- Lexical support is weak for answers that paraphrase heavily or are mostly in another language; thresholds
  were calibrated on Chinese answers over Chinese sources.
- It does not check that the answer is complete (31 incomplete answers had median support 0.92) or true.
