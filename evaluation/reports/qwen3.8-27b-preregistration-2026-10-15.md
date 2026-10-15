# Pre-registration: second model, `qwen/qwen3.8-27b`, local only

Committed before any run. Covers four already-established measurements, swapping only the chat model.
Three cells run in this repo (`rag-knowledge-base`); three run in `rag-agent-python`, which has its own
copy of this file for traceability. Local only — no cloud model is tested here.

## Why

The whole evaluation suite so far has measured one chat model, `google/gemma-4-26b-a4b-qat`. Every
number in `PORTFOLIO.md` and the delivery material is conditional on that model. A client choosing between
models, and the "model selection" section of the acceptance-eval handbook (not yet written), both need a
second data point measured the same way, not a second model's marketing claims. `qwen/qwen3.8-27b` is the
model the user picked from the three already present locally (`qwen/qwen3.6-27b`,
`qwen/qwen3.8-27b-splash` were not chosen).

## What stays fixed

Embedding `text-embedding-bge-m3`, retrieval VECTOR Top-K=5, chunk 700/100, `AI_TEMPERATURE=0`, citation
mode REPAIR, same fixture/corpus and same code as the 2026-10-13/14/15 reports. Only the chat model
changes: `AI_MODEL_ID=qwen/qwen3.8-27b` (Java) and `RAG_AGENT_LLM_MODEL=qwen/qwen3.8-27b` (Python agent).

## Required pre-flight, before the battery below (stop and report if any of these fails — do not improvise a fix)

1. Load `qwen/qwen3.8-27b` from the LM Studio UI with Thinking **off**, and confirm with one plain
   (non-tool) request that the response carries no `reasoning_content` / no `<think>` block. `lms load`
   and any JIT reload reset Thinking to on, so this is checked again after any reload, not just once.
2. One `/api/ask` call to check `AI_RESPONSE_FORMAT=json_schema` (the Java default) is accepted. DeepSeek
   needed `json_object` instead (2026-09-26 report); if Qwen rejects `json_schema` the same way, switch to
   `json_object` and record that as a configuration fact, not a quality finding.
3. One Python-agent `/ask` call on a question that needs exactly one search, to confirm the model actually
   emits an OpenAI-style tool call rather than describing a search in plain text. The agent's loop depends
   on real tool calls; a model that narrates instead of calling fails every cell below for a reason that
   has nothing to do with answer quality.

## Why a fresh Gemma run too

The 2026-10-13/14 corpus-v2 numbers were only kept as markdown summaries; the raw per-run capture files
were not committed and the eval stack's `down` discards them. `run_repeated_eval.py compare` needs the raw
summaries on both sides, so cell A re-runs Gemma in the same session, immediately adjacent to the Qwen
run, same code and fixture, rather than reusing the old published numbers as-is.

## Four cells

**A. `corpus_v2.jsonl` (45 answerable, 12 refusal, Java `POST /api/ask`).** `run_repeated_eval.py run`,
3 runs, backend restarted before every run (matching the 2026-10-13 "restarted" row, not the
single-process row). Gemma re-run and Qwen run in the same session. Verdict from
`run_repeated_eval.py compare --across-datasets` on `answerable_ok`, reported as that tool prints it
(DISTINGUISHABLE / WITHIN VARIATION / INSUFFICIENT RUNS) — no separate threshold invented here. Refusals
12/12 and ACL leakage 0 are sanity checks, not part of the comparison; a miss on either is reported as a
correctness problem, not folded into "Qwen scored lower."

**B. `cross_document_v1.jsonl` (12 compound questions) + `tool_calls_heldout_v1.jsonl` (27, control).**
Same config as `cross-document-preregistration-2026-10-14.md`: snippet length 800, 6 model turns, 4 tool
calls, cells Java `/api/ask`, agent `single`, agent `decompose`. One run per cell (matching that
precedent). The original decision rule applies again, per model: decomposition is credited with a benefit
only if it passes at least 3 more of the 12 than `single` and the control is not worse by more than 1.
Qwen's `decompose` pass count is also reported next to Gemma's 12/12 descriptively — one run per cell is
too thin to call that a comparison, and this file says so rather than implying one.

**C. `multi_turn_v2.jsonl` (10 conversations, enforcement on — the shipped setting, not re-toggled).** Two
runs, same as `multi-turn-preregistration-2026-10-14.md`. Reported descriptively against Gemma's
enforcement-on numbers (6/8, 6/8 correct-with-session; 8/8, 8/8 searched on turn 2; 0/10, 0/10
`no_retrieval`), not gated — the original report characterized rather than graded this set and this file
keeps that.

**D. `revocation_v1.jsonl` (5 cases, 2 of them never valid; enforcement on only).** Two runs. This one *is*
gated, because it is a safety property and the enforcement code is model-independent in principle but was
only ever exercised against one model:

- Predicted: 0 of 3 valid runs leak the revoked fact, with a retrieval in every run — matching Gemma.
- The open question from the 2026-10-15 report was whether the fixed-refusal path (triggered when the
  model still does not retrieve after one added instruction) would ever fire on a live model; it never did
  for Gemma. This run reports its firing rate for Qwen explicitly, whether zero or not.
- If Qwen leaks even once in a valid run, that is the headline finding of this whole pre-registration,
  reported first, regardless of how cells A-C come out.

## What will be reported, whichever way it falls

All four cells' tables, in the same shape as the Gemma reports they compare against, plus the pre-flight
results and any configuration change they forced (format fallback, retry settings). Any case where the
existing literal-match rubric looks wrong for Qwen's wording (the kind of gap already seen inside Gemma's
own day-to-day runs, e.g. CORP2-003) is read and reported in prose next to the score, not silently
rescored.

## Limits

One author, one machine, local only, synthetic small hand-written sets, literal scoring. The rubric and
every dataset were written and tuned against Gemma's answers; a wording gap for Qwen is a real risk this
design cannot rule out, only read case by case after scoring (see above). Cell B is one run per cell, below
the three-run floor this repo otherwise uses for a comparison claim. Cell D rests on three valid cases;
the other two were never answerable by either model's single retrieval and stay excluded. No latency or
memory/VRAM figures are collected here; if the delivery handbook needs them later, that is a separate,
unregistered measurement.
