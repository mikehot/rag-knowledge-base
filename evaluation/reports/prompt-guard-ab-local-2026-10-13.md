# Prompt-injection guard — quality and cost A/B, local, 2026-10-13

Question: does adding the safety rule and data boundaries to the prompt (commit `2270d2d`) degrade answer
quality or cost? Compared against the pre-change backend (`494b83e`, run from a git worktree on port 8081)
against the same database, users, fixture, and models, in the same session.

## Configuration

| Item | Value |
|---|---|
| Chat / embedding | `google/gemma-4-26b-a4b-qat` / `text-embedding-nomic-embed-text-v1.5`, LM Studio |
| Enable Thinking | OFF — structured-output probe 4/4 `contractValid`, `finishReason=stop`, 0.5–3.3 s |
| Retrieval | VECTOR, Top-K=5, threshold 0.35, chunk 700/100, `AI_MAX_TOKENS=2400` |
| Fixture | base fixture (7 synthetic documents), disposable pgvector container, no volume |
| Variants | **old** = `494b83e`; **v1** = long safety paragraph; **v2** = shortened paragraph (shipped) |

## Results

| Variant | answer-quality-v1 (12) ×3 | golden-v1 (20) | stress-v1 (8) | mean tokens/question | answer right, citation wrong |
|---|---|---|---|---|---|
| old | 9, 10, 10 | 16 | 8 | 1584 | 0 / 46 |
| v1 | 10, 9, 9 | 15 | 7 | 1854 (**+17.0%**) | 1 / 46 |
| v2 (shipped) | 10, 9, 9 | 15 | 8 | 1786 (+12.7%) | 2 / 52 |

ACL leakage 0 and schema failures 0 in every run. `UNSAFE_OUTPUT` never fired.

## Reading

- **No variant passes the answer-quality gate** (needs ≥ 10/12 in three consecutive runs). The pre-change
  baseline does not either; Q002/Q006 fail in every run of every variant (known chunk-mixing miss).
- v1 broke the frozen rule "mean tokens must not rise more than 15%", so the rule paragraph was shortened;
  v2 is +12.7%. Most of the remaining overhead is the repeated random boundary token, which could be trimmed
  further.
- By the letter of the frozen rule, golden-v1 is one case lower than the baseline (15 vs 16). The documented
  session-to-session spread on golden is ±2, so this is not distinguishable from noise on this sample. It is
  recorded rather than explained away.
- "Answer right, citation wrong": the answer's facts are correct but the backend-mapped citation points at a
  chunk that does not contain them (e.g. RAG-008, RAG-004, INJ-001). 0/46 for old vs 3/98 for v1+v2 is not
  statistically distinguishable (Fisher exact p ≈ 0.5), so the prompt change cannot be blamed — or cleared.
  Either way it exposes a real gap: the backend validates that a cited index is in range, but not that the
  cited chunk supports the answer. Tracked as a follow-up.

## Not claimed

Only one model, 7 synthetic documents, and 3 runs per variant. This is a regression screen for a security
control, not a quality candidate selection.
