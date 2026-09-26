# Cloud Provider Check — DeepSeek Chat + Local Embedding (2026-09-26)

## Why

The project claimed OpenAI-compatible provider portability but had never run against a cloud model. Freelance clients will usually choose a cloud model, and ROADMAP B3 asks for a quality/latency/cost comparison against the local stack.

## Configuration record

- Chat: DeepSeek API (`https://api.deepseek.com`), models `deepseek-flash` and `deepseek-v4-pro`, `AI_MAX_TOKENS=2400`, `AI_RESPONSE_FORMAT=json_object`.
- Embedding: local LM Studio `text-embedding-nomic-embed-text-v1.5` (768 dimensions), because DeepSeek has no embeddings API. `AI_EMBEDDING_API_KEY` was set to empty, so the cloud key is not sent to the local server.
- Retrieval: VECTOR, Top-K=5, similarity threshold 0.35, chunk size/overlap 700/100 (unchanged defaults).
- Data: the same seven-document synthetic fixture and ACL grants as the 2026-09-24 chunking A/B (`prepare_api_fixture.py`). Only synthetic content was sent to the cloud provider.
- Datasets and scoring: `answer-quality-v1` ×3 (rubric-v2), `golden-v1` ×1, `retrieval-stress-v1` ×1, scored by `run_eval.py`.
- Each model ran on a fresh no-volume pgvector database and a fresh backend built from the working tree (after `ae8ded4`, plus the response-format change below).
- The API key was loaded from a private file (`~/.config/rag-kb/cloud.env`, mode 0600) and never printed. Backend logs contain no `sk-` or `Authorization` strings.

## Finding: `json_schema` is not portable

The backend sends `response_format: {type: "json_schema", strict: true}`. DeepSeek rejects it (HTTP 400, "This response_format type is unavailable now") but accepts `{type: "json_object"}`. A new setting, `AI_RESPONSE_FORMAT`, fixes this:

- `json_schema` (default): unchanged behavior.
- `json_object`: sends the portable format.
- Any other value fails at startup.

In both modes, `StructuredOutputParser` still validates the full contract (fields, types, citation index range, found/grounded consistency) and fails closed. Schema enforcement moves from the provider to the backend; it is not relaxed. Two provider unit tests cover the new mode and the startup check.

## Results

| Config | answer-quality ×3 (gate ≥10 each) | Golden | Stress | Median API latency | Mean tokens/question | ACL leak | Structured-output failures |
|---|---|---|---|---|---|---|---|
| Local Gemma 4 26B, Thinking off (2026-09-24, two sessions) | 9,10,9 / 8,10,9 — **fail** | 17 / 15 | 8 / 8 | ~1.5 s | ~1,580 | 0 | 1 (fail-closed) |
| **deepseek-flash** | **10, 10, 10 — pass** | 17/20 | 8/8 | 1.0–1.6 s | ~1,650 | 0 | 0 |
| deepseek-v4-pro | 10, 10, 10 — pass | 17/20 | 8/8 | 3.0–4.0 s | ~1,810 | 0 | 0 |

The local baseline row comes from the 2026-09-24 chunking A/B report, not from the same session. Local latency was measured on a single Mac and cloud latency over the public internet; neither is an SLA.

**Where the remaining failures are.** Both DeepSeek models fail only the same retrieval-bound cases, all with `INSUFFICIENT_CONTEXT`: QUALITY-002/006 and RAG-003/010/014. QUALITY-001 (the low-battery alert point) is intermittently incomplete with local Gemma but passed in all six DeepSeek captures. So answer-completeness problems disappear with a stronger chat model, and the quality ceiling on this fixture is now set by retrieval (the blended FAQ chunk documented in the chunking A/B). A larger model (v4-pro) adds latency and tokens without changing any score.

## Cost estimate

DeepSeek list prices on 2026-09-26 (USD per million tokens, cache miss; off-peak / peak): `deepseek-flash` input 0.15/0.30 and output 0.60/1.20; `deepseek-v4-pro` input 0.66/1.32 and output 1.98/3.96. Source: <https://api-docs.deepseek.com/quick_start/pricing>.

The captures record total tokens per question, not the input/output split, so costs are given as a range: all-input at the lower bound, all-output at the upper bound.

- `deepseek-flash`, ~1,650 tokens per question: **$0.25–2.0 per 1,000 questions**. RAG prompts are dominated by retrieved context (input tokens), so the realistic figure is near the lower end, well under $1 per 1,000.
- `deepseek-v4-pro`, ~1,810 tokens per question: $1.2–7.2 per 1,000 questions.

The embedding model runs locally, so embedding cost is not included.

## Decision

- Keep local LM Studio as the README default, since the Quick Start must run without an account. Document `deepseek-flash` + local embedding as the verified cloud configuration.
- Under the frozen gate, `deepseek-flash` is the first configuration to pass answer-quality (3 × ≥10/12, ACL 0, schema failures 0). The gate's structured-output probe step is not applicable here, because the probe sends `json_schema`; backend-reported `STRUCTURED_OUTPUT_INVALID` counts (0) were used instead.
- Retrieval improvements stay in Phase B on a realistic corpus, since the remaining misses are retrieval-bound.

## Limitations

This used a seven-document synthetic fixture, one session per model, and public-internet latency. Cost is a list-price estimate from total tokens. The provider's data retention and logging were not assessed; only synthetic data was sent, per the provider checklist in `docs/DEPLOYMENT_RUNBOOK.md`.
