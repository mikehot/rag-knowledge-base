# Pre-registration: third model, cloud `GLM-5.3-Flash-Event` (SCNet) + local `text-embedding-bge-m3`

Committed before any run. Covers the four established evaluation cells (Cell A in `rag-knowledge-base`,
Cells B/C/D in `rag-agent-python`), swapping only the chat model to cloud `GLM-5.3-Flash-Event` via
SCNet (`https://api.scnet.cn/api/llm/v1`) while keeping the retrieval pipeline 100% identical to the
local Gemma 4 26B (`google/gemma-4-26b-a4b-qat`) and Qwen 3.8 27B (`qwen/qwen3.8-27b`) evaluations.

## Why

The `corpus-v2` and Python-agent evaluations (`2026-10-13` through `2026-10-15`) have measured two local
4-bit MLX models (`google/gemma-4-26b-a4b-qat` and `qwen/qwen3.8-27b`), while the earlier cloud check
(`2026-09-26`, `deepseek-flash` / `deepseek-v4-pro`) ran on the 7-document synthetic fixture with
`nomic-embed-text-v1.5`. We have not yet measured a cloud chat model on the 28-document enterprise
`corpus-v2` benchmark and the Python agent's cross-document, multi-turn, and mid-session ACL-revocation
suites under the shipped `text-embedding-bge-m3` retrieval configuration.

## What stays fixed

- **Embedding & Retrieval**: Local LM Studio `text-embedding-bge-m3` (`AI_EMBEDDING_BASE_URL=http://localhost:1234/v1`,
  `AI_EMBEDDING_API_KEY=""`), retrieval mode `VECTOR` Top-K=5, similarity threshold 0.35, chunk size/overlap
  700/100, citation support mode `REPAIR`. Because embeddings are computed locally by the exact same
  `text-embedding-bge-m3` model on the same 28-document corpus fixture, the retrieved Top-5 chunk set for
  every deterministic single-turn question in Cell A is 100% identical to the Gemma 4 26B and Qwen 3.8 27B runs.
- **Chat model configuration**:
  - `AI_BASE_URL=https://api.scnet.cn/api/llm/v1` (Java) / `RAG_AGENT_LLM_BASE_URL=https://api.scnet.cn/api/llm/v1` (Python)
  - `AI_MODEL_ID=GLM-5.3-Flash-Event` (Java) / `RAG_AGENT_LLM_MODEL=GLM-5.3-Flash-Event` (Python)
  - `AI_TEMPERATURE=0`
  - `AI_REASONING_EFFORT=none` / `RAG_AGENT_LLM_REASONING_EFFORT=none` (Thinking disabled, `reasoning_tokens=0`, matching the Thinking-off condition required for Gemma and Qwen)
  - `AI_RESPONSE_FORMAT=json_object` (Java backend): as documented for DeepSeek (`2026-09-26`), `GLM-5.3-Flash-Event` wraps `json_schema` responses in Markdown code fences, whereas `json_object` returns raw JSON. `StructuredOutputParser` enforces the identical schema contract (`answer`, `found`, `grounded`, `sourceIndexes`) on the backend and fails closed.

## Required pre-flight checks (before the battery below)

1. Verify local `text-embedding-bge-m3` returns 1024-dim vectors and `AI_EMBEDDING_API_KEY` is empty so the cloud API key never leaves the cloud chat client.
2. Verify `GLM-5.3-Flash-Event` with `AI_REASONING_EFFORT=none` returns empty `reasoning_content` (`reasoning_tokens=0`) on a plain chat completion.
3. Verify `probe_structured_output.py` passes 4/4 under `AI_RESPONSE_FORMAT=json_object` and `AI_REASONING_EFFORT=none`.
4. Verify `GLM-5.3-Flash-Event` emits native OpenAI `tool_calls` (`search_knowledge`) rather than narrating a search in plain text.

## Four cells

**A. `corpus_v2.jsonl` (45 answerable, 12 refusal, Java `POST /api/ask`).** `run_repeated_eval.py run`,
3 runs, fixed question order, backend restarted before every run. Compared against the 2026-10-15
3-run summaries for Gemma 4 26B (`mean 41.33`, range `41–42`) and Qwen 3.8 27B (`mean 42.67`, range
`42–44`) using the conservative non-overlapping-range rule (`DISTINGUISHABLE` / `WITHIN VARIATION`).
Refusals `12/12`, ACL leakage `0`, and schema failures `0` are required correctness gates.

**B. `cross_document_v1.jsonl` (12 compound questions) + `tool_calls_heldout_v1.jsonl` (27 cases, regression control).**
Same configuration as `cross-document-preregistration-2026-10-14.md`: backend `RAG_AGENT_SNIPPET_CHARS=800`,
agent limits 6 turns / 4 tool calls. Run Java `POST /api/ask` baseline, agent policy `single` (×3 runs),
and agent policy `decompose` (×3 runs), plus held-out control under `single` and `decompose`. The
original decision rule applies: decomposition is credited with a benefit only if its mean pass count
beats `single` by at least 3 of 12 and held-out control is not worse by more than 1.

**C. `multi_turn_v2.jsonl` (10 two-turn conversations: 8 follow-ups, 2 negatives).** Backend snippet
length 360 (default), 3 runs with `RAG_AGENT_ENFORCE_RETRIEVAL=true` (and comparison against `false`),
measuring turn-2 accuracy with session vs alone, turn-2 search rate, `no_retrieval` refusals, and
negative-conversation non-leakage.

**D. `revocation_v1.jsonl` (5 mid-session ACL revocation cases).** 3 runs with `RAG_AGENT_ENFORCE_RETRIEVAL=true`
and 3 runs with `RAG_AGENT_ENFORCE_RETRIEVAL=false`. Safety gate: with enforcement `on`, 0 valid runs may
repeat the revoked fact on turn 2, and every valid turn 2 must execute a fresh retrieval under the
post-revocation permissions.

## Post-run semantic audit & benchmark expansion (Deliverable 2B, separate from frozen deterministic scores)

After recording the unmodified literal-rubric scores for Cells A–D above, we will run an independent
LLM-as-Judge semantic audit (`GLM-5.3-Flash-Event` with `reasoning_effort="high"`) on all `corpus-v2`
failures to separate true retrieval/generation misses from literal-substring rubric artifacts (such as
`CORP2-002` and `CORP2-045`), without altering the primary deterministic scores.
