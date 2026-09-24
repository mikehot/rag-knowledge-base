# Structured Output Termination Diagnostics — Local Evidence (2026-09-24)

## Why this change was made

A post-revocation disposable `/api/ask` record had `found=false`, no revoked-document evidence in its ACL-filtered retrieval snapshot, and `STRUCTURED_OUTPUT_INVALID` after a bounded retry. The aggregate record did not retain provider termination metadata or raw output, so it cannot establish whether the model hit its completion limit, stopped normally with malformed JSON, or failed another contract check. Raw prompts and model output remain intentionally unpersisted.

## Bounded implementation

- The internal provider response now carries a normalized `finish_reason` (`stop`, `length`, `content_filter`, `tool_calls`, `other`, or `unknown`) and the provider-reported completion-token count. `unknown` means the provider omitted or returned a blank value; an explicit unrecognized value maps to `other` and is rejected.
- The ask service logs only the request ID, generation attempt, normalized finish reason, completion-token count, and output character count when structured validation fails. It does not log the question, prompt, answer, document content, raw provider payload, or parser exception text.
- A response is accepted only when the provider reports normal `stop` or omits the finish reason (`unknown`, for legacy compatibility). Explicit interruption reasons and explicit unrecognized values fail closed as `STRUCTURED_OUTPUT_INVALID`, even if the returned text happens to be syntactically valid JSON. The existing bounded retry and public response contract are unchanged.
- No project database migration, retrieval default, Top-K, ACL logic, or persisted model setting changed.

## Verification

- Synthetic provider test confirmed that `finish_reason=length` and completion-token metadata are parsed.
- Synthetic provider/service tests confirmed that an explicit unrecognized finish reason is distinguished from a missing one and rejected even when the returned JSON is complete.
- A service regression test supplied syntactically complete JSON with `finish_reason=length`; the response was rejected and returned the existing safe fallback with no sources.
- Full Docker-backed backend suite: 123 tests passed, 0 failures, 0 errors, 0 skipped.

## Controlled replay — 2026-09-24

- Used a fresh, no-volume PostgreSQL/pgvector container and one-off backend bound to loopback-only ports. The disposable fixture contained two synthetic users and seven sample/synthetic documents; the normal development database was not used.
- Fixed the local diagnostic condition to Gemma 4 26B with LM Studio Thinking off and Nomic Embed Text v1.5 embeddings. Backend settings remained VECTOR, Top-K=5, similarity threshold 0.35, 2,400 max output tokens, and one Structured Output retry. No project runtime or retrieval default changed.
- The employee had exactly one direct `READ` grant on `sample_faq.md`. After revocation, the document ACL had zero grants and the employee document list omitted the FAQ.
- Replayed the synthetic FAQ question three times: `found=false`, `grounded=false`, `failureReason=INSUFFICIENT_CONTEXT` on all three; no citations were returned. Each protected retrieval snapshot contained five hits from other employee-authorized synthetic files, with top similarity 0.7248. The revoked FAQ appeared in neither retrieval hits nor citations (ACL leakage 0/3).
- `STRUCTURED_OUTPUT_INVALID` did not recur, so the new failure-only warning was not emitted and no `finish_reason` was observed in this replay. The previous one-off failure's cause therefore remains undetermined; this does not establish whether it was a completion-limit interruption or another schema/content failure.
- Raw prompt, answer text, and snippets were not retained. The one-off backend was stopped, the exact ephemeral database container was removed, the two diagnostic models were unloaded, LM Studio's original Thinking/model-loading preferences were restored, and temporary credentials, manifest, script, and uploaded fixture files were deleted.

This is local synthetic evidence only: it verifies ACL revocation and a fail-closed refusal for this replay, but it is not a quality-gate pass, a reproduction of the earlier structured-output failure, or a production guarantee. Do not infer a root cause or change retrieval/model defaults from this sample. If the failure recurs, use its sanitized `finish_reason`, completion-token count, and attempt number; otherwise obtain the exact original synthetic question/configuration before claiming an equivalent reproduction.
