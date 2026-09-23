# Gemma Structured Output Recheck — Local Evidence (2026-09-23)

## Scope

This recheck used `evaluation/probe_structured_output.py` against the local LM Studio OpenAI-compatible API with two synthetic questions and the same strict `rag_answer` JSON Schema as the backend provider. It sent no project documents, retrieved chunks, credentials, or user data. The report records response metadata only; raw output stayed outside the repository.

Before the probe, LM Studio server status was running on port 1234, but no model was loaded. The configured model `google/gemma-4-26b-a4b-qat` loaded successfully in 14.33 seconds (LM Studio reported 14.57 GiB loaded). The endpoint returned HTTP 200 for every probe call.

## Results

| Run | Probe | Max tokens | Contract | Finish reason | Completion tokens | Latency |
|---:|---|---:|---|---|---:|---:|
| Initial | single point | 2400 | pass | `stop` | 34 | 2.54 s |
| Initial | multi point | 3200 | pass | `stop` | 628 | 10.02 s |
| Repeat 1 | single point | 2400 | fail: invalid content JSON | `length` | 2399 | 34.69 s |
| Repeat 1 | multi point | 3200 | fail: invalid content JSON | `length` | 3199 | 48.06 s |
| Repeat 2 | single point | 2400 | fail: invalid content JSON | `length` | 2399 | 36.16 s |
| Repeat 2 | multi point | 3200 | fail: invalid content JSON | `length` | 3199 | 48.87 s |
| Repeat 3 | single point | 2400 | fail: invalid content JSON | `length` | 2399 | 35.31 s |
| Repeat 3 | multi point | 3200 | fail: invalid content JSON | `length` | 3199 | 46.41 s |

Overall, 2/8 calls passed: the initial pair passed, while all six repeated calls exhausted their completion-token budgets and returned invalid JSON. The HTTP/API connection and model loading succeeded; this is a model-output stability failure, not a server-connectivity failure.

## Decision

- Keep `AI_MODEL_ID=google/gemma-4-26b-a4b-qat`, the 2400-token default, and fail-closed parsing unchanged; this probe does not justify a model or configuration change.
- Do not relax JSON parsing or substitute `reasoning_content` for `message.content`.
- The configured model does not currently pass the repeatability gate. Diagnose supported reasoning/format settings or test an alternative model in isolation; any candidate still needs repeated schema probes plus `answer-quality-v1`, `retrieval-stress-v1`, ACL, latency, and token checks.
- Defer the planned ACL UI implementation until the model/output gate is resolved, as previously sequenced; no application code was changed in this recheck.

## Evidence boundary

This is a local provider capability probe, not a full RAG quality result, production SLO, or deployment claim. The sanitized aggregate JSON was written outside Git to `/private/tmp/rag-structured-output-probe-20260923.json` and `/private/tmp/rag-structured-output-probe-3x-20260923.json`.

## Follow-up: Gemma thinking configuration

The LM Studio UI showed `Enable Thinking = on` for the loaded `google/gemma-4-26b-a4b-qat` instance. The backend provider and its OpenAI-compatible request do not send a Gemma-specific `enable_thinking` field. Google's Gemma 4 guidance describes a dedicated template branch for disabling thinking on larger models; see the official [thinking guide](https://ai.google.dev/gemma/docs/capabilities/thinking).

For diagnosis only, `Enable Thinking` was set to off in the current unsaved LM Studio inference configuration. An initial probe from the restricted shell failed with `URLError`; a read-only request using the approved local-network path then confirmed this was sandbox isolation, not an LM Studio outage. The same six-call probe was rerun against `127.0.0.1:1234` while thinking was off:

| Run | Probe | Contract | Finish reason | Completion tokens | Latency |
|---:|---|---|---|---:|---:|
| 1 | single point | pass | `stop` | 25 | 2.98 s |
| 1 | multi point | pass | `stop` | 62 | 1.32 s |
| 2 | single point | pass | `stop` | 26 | 0.60 s |
| 2 | multi point | pass | `stop` | 131 | 2.67 s |
| 3 | single point | pass | `stop` | 26 | 0.62 s |
| 3 | multi point | pass | `stop` | 140 | 2.75 s |

All 6/6 responses were HTTP 200 and valid against the strict response contract. The LM Studio UI setting was then restored to its original `on` value; no preset was saved. This strongly implicates the thinking setting in the previous truncation, but it does not establish answer quality or production readiness. The next gate is to run the existing end-to-end answer-quality, retrieval-stress, and ACL checks with thinking disabled, then define a reproducible local LM Studio setting before any production-default change.
