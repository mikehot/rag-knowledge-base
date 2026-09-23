# QUALITY-002 / QUALITY-006 Failure Classification — Local Evidence (2026-09-23)

## Scope and evidence used

This report classifies the disposable local evidence recorded on 2026-09-23. It does not change application behavior. Evidence sources are:

- The seven-case ACL-aware chunk evidence report in `chunk-evidence-topk-ab-local-2026-09-23.md`.
- The two repeated full `answer-quality-v1` API captures each for Top-K=5 and Top-K=8 in that report.
- The earlier protected candidate report for `QUALITY-002` and the 2026-09-23 Gemma end-to-end report.
- Current `AskService` control flow and the sanitized `sample_faq.md` fixture.

After that analysis, a separate fixed-context provider replay was performed against the local LM Studio API. It reconstructed the current `AskService` prompt contract and supplied only the relevant exact FAQ section for each case—no retrieval candidates or distractor chunks. The run used the same Gemma model, JSON Schema, 2400-token ceiling, and Thinking-off condition as the disposable end-to-end evaluation, with five sequential calls per case. Response text was evaluated in memory and not saved.

## Classification

| Case | Expected-source answer-point chunks | Top-K=5 repeated outcomes | Top-K=8 repeated outcomes | Classification |
|---|---|---|---|---|
| `QUALITY-002` | Both installation points appear at candidate rank 7 in the 2026-09-23 chunk capture. The earlier document-only capture placed `sample_faq.md` at rank 6. | Failed in both runs. | Passed in run 1; failed in run 2. | Primary cause is evidence ranking beyond K=5; answer generation is also non-repeatable when the evidence is admitted at K=8. |
| `QUALITY-006` | All three return/shipping points appear at candidate rank 6 in the 2026-09-23 chunk capture. | Failed in both runs. | Passed in run 1; failed in run 2. | Primary cause is evidence ranking beyond K=5; answer generation is also non-repeatable when the evidence is admitted at K=8. |

The fixture itself contains the expected information: appointment and first-tier-city installation are free; non-human quality defects are exchangeable within 15 days; shipping is paid by the merchant for quality issues and by the buyer otherwise.

## Fixed-context generation replay

| Case | Calls | HTTP / JSON Schema valid | `found` + grounded + citation 1 | All expected answer points | Mean total API tokens | Mean latency |
|---|---:|---:|---:|---:|---:|---:|
| `QUALITY-002` | 5 | 5/5 | 5/5 | 5/5 | 479.8 | 1.66 s |
| `QUALITY-006` | 5 | 5/5 | 5/5 | 5/5 | 520.4 | 1.41 s |

All calls finished with `finish_reason=stop`. The first `QUALITY-002` call took about 4.13 s while later calls took about 1.04 s, consistent with a local warm-up effect; the mean is descriptive only. This small controlled replay demonstrates that the current provider can return complete, cited structured answers when each question receives its relevant source section without distractor chunks. It does not prove that arbitrary context compositions or future runs will be deterministic.

## Is this an application-side refusal gate?

The recorded API failure reason is `INSUFFICIENT_CONTEXT`, not `RETRIEVAL_MISS`. In `AskService`, `RETRIEVAL_MISS` is emitted before generation only when there are no hits or the top vector similarity is below the configured threshold. These requests reached the generation stage and were then returned as insufficient context from the model's structured answer. Thus the evidence does not point to the application's empty-result/threshold short-circuit as the cause.

This does not prove that a model-side abstention is irrational: at K=5, the relevant answer-point terms were absent from the selected chunks. The refusal is consistent with the context that the model received, even though relevant evidence existed just outside the context boundary.

## What the repeats establish—and do not establish

- At K=5, both target cases failed in both full-set repeats.
- At K=8, both passed in the first full-set repeat and failed in the second. Overall rubric pass count also varied 11/12 to 9/12, while the two K=8 runs averaged the same 10/12 as K=5.
- The fixed-context replay passed 5/5 for both questions, so it does not reproduce the K=8 failures. The contrast points to retrieved-context composition/order and/or sampling interaction, rather than an inability to answer from the source material.
- These results cannot isolate sampling variance from distractor/context interference: the fixed replay intentionally used only the relevant section, while the end-to-end K=8 prompt included additional ranked chunks. A replay of the exact full K=8 prompts would be required for stronger causal attribution.
- The chunk metric is exact lexical presence within expected source documents; it establishes that benchmark answer-point terms occur in the candidate chunks, not semantic sufficiency or production relevance.

## Decision

1. Keep vector Top-K=5 and the existing similarity threshold unchanged. The K=8 comparison did not produce a repeatable full-set quality gain.
2. Do not change the refusal contract or relax fail-closed behavior. The application-side early retrieval gate is not implicated by these two `INSUFFICIENT_CONTEXT` results.
3. Do not implement Hybrid Search or a reranker from this evidence. It identifies the K=5 boundary but does not show a stable net quality gain.
4. Do not keep tuning the local model based on this two-case sample. If a retrieval candidate is proposed later, compare it against the full versioned quality and stress sets. If exact causal isolation becomes necessary, replay identical full Top-K=8 prompts rather than only the relevant source section.

## Evidence boundary

All evidence is from synthetic local fixtures and a local Gemma/LM Studio provider. There is no production-customer or deployment claim. This report is a diagnosis, not a change to retrieval or refusal behavior.
