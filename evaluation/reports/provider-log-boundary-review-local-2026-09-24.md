# Provider Log Boundary Review — 2026-09-24

## Finding

LM Studio's official `lms log stream` documentation says the model log stream can show the exact formatted input sent to the model and the output returned by the model: <https://lmstudio.ai/docs/cli/serve/log-stream>.

For this RAG service, model input can include retrieved document chunks and the system/user prompt. Provider model I/O logs must therefore be treated as sensitive data, even when application logs omit document and question bodies. The server-only stream (`lms log stream --source server`) is the safer option when diagnosing server lifecycle/HTTP behavior, but it is not evidence that model I/O is disabled or redacted.

## Local verification boundary

- At the beginning of the review, the API and Docker daemon were unavailable. After the user opened the applications, Docker started and the LM Studio local API became available; the Chat and Embedding models were loaded temporarily for the synthetic disposable demo and unloaded after it.
- The installed app was LM Studio 0.4.25 (Build 1). Its General settings expose an "Open app logs" entry; the Developer settings page exposed runtime log verbosity but no visible model-I/O redaction, retention, or disable control. No setting was changed.
- A short-lived `lms log stream --source model --filter input,output --json` process observed unique synthetic input and output sentinels from one request. The local probe reported only marker visibility booleans; it did not emit raw stream lines to the terminal or write them to disk.
- Metadata-only inventory found 68 dated files in LM Studio's local `server-logs` directory, with modification times spanning 2026-03-19 through 2026-09-24. All 68 enumerated log files had mode `0644`; the log/month directories had mode `0755`, and the home directory had mode `0750`. This confirms server-log files persist locally over the observed period and that group-read/traverse permission bits are present (actual reachability depends on group membership and ACLs). No log contents were read, so this does not establish whether server logs contain prompts, document chunks, or other model I/O.
- The live probe establishes that the on-demand model-I/O stream can expose request input and model output. The local review did not open the Developer Logs UI or any log file, and did not establish a retention policy, rotation behavior, per-user reachability, or model-I/O persistence behavior.
- No customer content or non-synthetic prompt/response was intentionally inspected or retained in this review.

## Operational rule

- Never expose model I/O logs in screenshots, recordings, tickets, or public reports when real or sensitive data may be present.
- If model I/O inspection is necessary, use only an explicitly approved isolated test with synthetic documents/accounts and record only a sanitized pass/fail conclusion.
- Before handling real customer data, verify the exact installed version's log storage, access, retention, redaction, and disablement controls. If those controls are insufficient or unclear, do not send sensitive content to that provider configuration.

## Status

Documentary risk review: **PASS** (official capability and data sensitivity are documented).

Live model-I/O stream exposure check: **PASS** (synthetic input and output markers were both visible in the local stream).

Installed-instance server-log inventory: **PASS, metadata only** (dated log files persist locally; observed file/directory permission bits are recorded above).

Installed-instance log-content classification, model-I/O persistence, retention/rotation policy, and effective local-user access review: **OPEN** (no log contents were inspected and no policy/control was verified). Treat the local log directory as potentially sensitive. Do not send sensitive customer content to this configuration until its log content and access/retention controls are assessed; any permission tightening needs an explicit, separate change approval.
