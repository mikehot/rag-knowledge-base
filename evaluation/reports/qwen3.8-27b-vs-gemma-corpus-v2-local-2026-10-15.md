# corpus-v2: qwen/qwen3.8-27b vs Gemma, both 4-bit MLX — local, 2026-10-15

Pre-registered in `qwen3.8-27b-preregistration-2026-10-15.md` (committed before any run; Cell A only — B,
C, D not run in this session). All three required pre-flight checks passed first: Thinking off
(`reasoning_content` empty, `reasoning_tokens` 0), `AI_RESPONSE_FORMAT=json_schema` accepted by Qwen
(probe 4/4), and the Python agent received a real `search_knowledge` tool call from Qwen, not narrated
text.

## Configuration

Embedding `text-embedding-bge-m3`, retrieval VECTOR Top-K=5, chunk 700/100, `AI_TEMPERATURE=0`, citation
mode REPAIR. Both chat models at 4-bit MLX (`gemma-4-26B-A4B-it-QAT-MLX-4bit`,
`Qwen3.8-27B-MLX-4bit`) — the only 4-bit variant either model has on disk, so quantization is not a
confound. 3 runs each, backend restarted before every run (same discipline as the 2026-10-13 "restarted"
row), questions asked in a fixed (unshuffled) order both times. Gemma was re-run fresh in this session
rather than reusing the 2026-10-13/14 published numbers, because those runs' raw per-case captures were
not kept (only the markdown summaries), and `compare` needs the raw summaries on both sides.

## Result

| Run | Gemma answerable | Gemma refusals | Qwen answerable | Qwen refusals | Gemma seconds | Qwen seconds |
|---|---|---|---|---|---|---|
| 1 | 41/45 | 12/12 | 42/45 | 12/12 | 218.2 | 607.9 |
| 2 | 42/45 | 12/12 | 42/45 | 12/12 | 177.7 | 587.6 |
| 3 | 41/45 | 12/12 | 44/45 | 12/12 | 199.8 | 577.5 |

ACL leakage 0 in all 6 runs. Gemma: mean 41.33, range 41-42, stdev 0.58. Qwen: mean 42.67, range 42-44,
stdev 1.15.

**`run_repeated_eval.py compare` verdict (all three metrics): WITHIN VARIATION.**

```
answerable_ok: A mean 41.33 [41-42] | B mean 42.67 [42-44]  -> WITHIN VARIATION (ranges overlap at 42)
refusals_ok:   A mean 12 [12-12]    | B mean 12 [12-12]     -> WITHIN VARIATION
all_ok:        A mean 53.33 [53-54] | B mean 54.67 [54-56]  -> WITHIN VARIATION
```

Qwen's mean is numerically higher (+1.34 answerable), and its best run (44/45) matches the best Gemma has
ever scored on this set, but the tool's conservative rule does not call this a win: the three-run ranges
overlap. **No demonstrated accuracy difference between the two models on corpus-v2 at this sample size.**

### The two cases the tool flagged as "clearly different" (≥0.5 pass-rate gap)

**CORP2-002** (Deployment rolling-update `maxUnavailable`/`maxSurge`, both default 25%) — clearly better
in Gemma. Reading both answers: Gemma's answer names both fields explicitly (`maxUnavailable`,
`maxSurge`) and states "25%" for each, passing the rubric's three required points. Qwen's answer states
"默认值为 25%" twice, correctly, for both the unavailable and surge limits, but never writes the field
names `maxUnavailable` / `maxSurge` (or `最大不可用` / `峰值`) — so it fails 2 of 3 required points under
literal-substring scoring despite getting the actual numbers right in all three runs. **This is the
rubric-wording risk the pre-registration flagged in advance, not a knowledge gap**: the rubric was tuned
against Gemma's phrasing habits.

**CORP2-022** (how long until a written contract is required, and what happens after a year unsigned) —
clearly better in Qwen, and this one is real. Qwen's answer states all three parts (one month to sign, the
doubled-wage penalty between one month and a year, and the "deemed indefinite-term contract" consequence
past a year) in all three runs, worded almost identically each time. Gemma states the full three-part
answer only in run 1; runs 2 and 3 drop the "deemed indefinite-term" consequence and describe only the
doubled-wage penalty. Gemma was already flaky on this case in the original 2026-10-13 corpus-v2 run; here
it is flaky again, while Qwen is consistent.

### A claim from the pre-flight check does not hold up

Before this run, a single ad hoc call to Qwen on CORP2-001 ("旧的 ReplicaSet 默认留几份") got the right
answer, where Gemma's known history on that exact case (2026-10-14 reports) was wrong in most runs. **That
contrast does not survive a controlled re-run**: in this session's three fresh Gemma runs, Gemma also
answered CORP2-001 correctly every time. The earlier single-call result was real but was not evidence of a
Qwen-specific strength — it's consistent with the unexplained day-to-day wording drift already documented
in `corpus-v2-paraphrase-local-2026-10-13.md`'s addenda. Flagging this as a correction rather than quietly
dropping it.

### Not pre-registered, reported because the numbers were already in hand

- **Tokens**: Qwen used fewer mean tokens per run (2129, 2129, 2125) than Gemma (2597, 2608, 2597) — about
  18% less, consistent with Qwen's answers reading more concise in the cases read above.
- **Wall-clock time**: Qwen took roughly 3x longer per 57-question run (578-608s vs 178-218s for Gemma) on
  this machine, despite using fewer tokens — a throughput difference, not an answer-length difference. If
  response latency matters to how this is delivered, that is a real cost Qwen carries that this
  pre-registration did not plan to measure and only captured because `run_repeated_eval.py` times every
  run by default.

### Shared weakness

CORP2-045 (cross-document completeness: a data-breach question needing both statutes) failed in all 3
runs for both models — the same cross-document gap already documented in the original corpus-v2 report.
Not a model-specific result.

## What this shows

On corpus-v2 accuracy, Qwen and Gemma are **statistically indistinguishable at 3 runs each** — overlapping
ranges, no verdict either way. The one place Qwen is clearly and consistently better (CORP2-022) is real;
the one place Gemma is clearly better (CORP2-002) is a rubric-literalism artifact against content Qwen got
right. Qwen is measurably slower on this hardware for the same question set. None of this supports
switching the delivery default to Qwen on quality grounds alone; it does mean a client who cares about
response latency should see the ~3x number before Qwen is proposed as "the same model, free."

## Limits

Three runs per model (the tool's own minimum for any verdict), one day, one machine, one author, fixed
question order (not shuffled, so an order effect is not ruled out). The rubric was written and tuned
against Gemma's phrasing over many earlier sessions and was not re-tuned for Qwen; CORP2-002 above is a
concrete instance of that risk, and other Qwen answers may be marked wrong for the same reason without
having been read individually (only the two flagged cases and the previously-known failures were read
here). Cells B (cross-document), C (multi-turn), and D (revocation) from the pre-registration were not run
in this session.
