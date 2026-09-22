# Structured Output Provider Probe v1 — Local Evidence (2026-09-22)

## Scope

This probe sent two synthetic questions directly to the local LM Studio OpenAI-compatible endpoint. It used the same strict `rag_answer` JSON Schema as the Java provider, but did not include project documents, prompts, credentials, or retrieved chunks. The checked-in runner is `evaluation/probe_structured_output.py`; the raw JSON result stayed outside Git under `/private/tmp`.

The probe is a provider capability check, not an answer-quality evaluation. It records only HTTP status, contract validity, finish reason, message-field presence, token usage, response size, and latency.

## Results

| Model | Probe | Max tokens | HTTP | Content | Contract | Finish reason | Total tokens | Latency |
|---|---|---:|---:|---|---|---|---:|---:|
| `google/gemma-4-26b-a4b-qat` | single point | 2400 | 200 | invalid JSON | fail | `length` | 2438 | 36.6s |
| `google/gemma-4-26b-a4b-qat` | multi point | 3200 | 200 | invalid JSON | fail | `length` | 3251 | 46.6s |
| `qwen/qwen3.8-27b` | single point | 2400 | 200 | empty `content` | fail | `stop` | 131 | 3.7s |
| `qwen/qwen3.8-27b` | multi point | 3200 | 200 | empty `content` | fail | `stop` | 129 | 2.7s |

Additional safe metadata:

- Gemma returned non-empty content, but it was not valid JSON and consumed the entire completion budget in both probes.
- Qwen returned `message.content` empty while `message.reasoning_content` was present. The current Java provider reads `message.content` only, so this model is not compatible with the current answer contract based on this probe.
- No HTTP or provider-level error was returned. The failures are output-contract failures, not connectivity failures.

## Decision

1. Keep `AI_MODEL_ID=google/gemma-4-26b-a4b-qat` unchanged for now; no candidate model has passed the minimum structured-output gate.
2. Keep backend fail-closed behavior. Do not parse truncated JSON heuristically and do not promote `reasoning_content` to an answer field without a deliberate provider contract change.
3. Keep `RAG_HYBRID_EXPERIMENT_ENABLED=false` and the default VECTOR retrieval path unchanged. This probe does not justify a retrieval change.
4. Repeat the probe at least three times for any candidate model, then run the same `answer-quality-v1` and `retrieval-stress-v1` gates before changing the default model.

## Evidence boundary

- This is a small local LM Studio probe, not a production reliability or latency SLO.
- The questions contain no project knowledge, so the result isolates provider output behavior rather than retrieval quality.
- A model that passes this probe still needs ACL, citation, refusal, latency, token, and end-to-end quality verification.
