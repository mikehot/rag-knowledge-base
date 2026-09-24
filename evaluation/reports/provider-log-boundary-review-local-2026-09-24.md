# Provider Log Boundary Review — 2026-09-24

## Finding

LM Studio's official `lms log stream` documentation says the model log stream can show the exact formatted input sent to the model and the output returned by the model: <https://lmstudio.ai/docs/cli/serve/log-stream>.

LM Studio's 0.3.26 release announcement shows server logs saved under `~/.lmstudio/server-logs`: <https://lmstudio.ai/blog/lmstudio-v0.3.26>. The official documentation reviewed describes stream sources and filters, but does not specify a local log-retention or rotation contract; this is not proof that the app has no internal cleanup behavior.

For this RAG service, model input can include retrieved document chunks and the system/user prompt. Provider model I/O logs must therefore be treated as sensitive data, even when application logs omit document and question bodies. The server-only stream (`lms log stream --source server`) is the safer option when diagnosing server lifecycle/HTTP behavior, but it is not evidence that model I/O is disabled or redacted.

## Local verification boundary

- At the beginning of the review, the API and Docker daemon were unavailable. After the user opened the applications, Docker started and the LM Studio local API became available; the Chat and Embedding models were loaded temporarily for the synthetic disposable demo and unloaded after it.
- The installed app was LM Studio 0.4.25 (Build 1). Its General settings expose an "Open app logs" entry; the Developer settings page exposed runtime log verbosity but no visible model-I/O redaction, retention, or disable control. No setting was changed.
- A short-lived `lms log stream --source model --filter input,output --json` process observed unique synthetic input and output sentinels from one request. The local probe reported only marker visibility booleans; it did not emit raw stream lines to the terminal or write them to disk.
- Initial metadata-only inventory found 68 dated files in LM Studio's local `server-logs` directory, with modification times spanning 2026-03-19 through 2026-09-24. All 68 enumerated log files had mode `0644`; at that point the log/month directories had mode `0755`, and the home directory had mode `0750`. No log contents were read.
- A separate synthetic persistence probe loaded one already-downloaded model, sent one request with unique synthetic input/output markers, and scanned only newly appended bytes in the `server-logs` directory. Both markers were found in 4,055 appended bytes from one log file. No pre-existing bytes, raw log lines, prompt, response, or marker values were printed or retained. The probe unloaded the model it had loaded and verified no model remained loaded.
- A follow-up account check identified `staff` as GID 20. The current account's primary GID is 20; among existing `/Users` home directories, it was the only account with UID >= 500 in `staff`. The explicitly listed `staff` member resolved to UID < 500. Directory Services returned `eServerError` during full enumeration of other local accounts, so accounts without a home directory or network-directory accounts remain unverified.
- After reviewing that boundary, the `server-logs` root directory was changed from `0755` to owner-only `0700`; it remains owned by the current account (UID 501, GID 20). `ls -lde` showed no ACL entries. Existing files remain `0644`, but the owner-only parent directory prevents group/other traversal through the normal path. Owner permission bits are present; an application write after the change was not tested. A read-only search of `/etc/newsyslog.conf` and `/etc/newsyslog.d` found no explicit LM Studio or `server-logs` rule; this does not rule out application-managed cleanup or rotation.
- The local review did not open the Developer Logs UI or inspect log contents. It confirms that this synthetic request's input/output markers persisted, but does not classify all historical log contents or establish a configured retention policy, rotation behavior, redaction control, or complete per-user reachability.
- No customer content or non-synthetic prompt/response was intentionally inspected or retained in this review.

### Follow-up metadata-only check and restart attempt

- A follow-up check found the `server-logs` root at `0700`; the six dated month directories remain `0755`, and all 68 files remain `0644`. Their aggregate size was about 41.8 MB, with modification times from 2026-03-19 through 2026-09-24. Only metadata was read.
- With the user's approval, LM Studio was normally quit to test permission persistence across restart. The app then failed to reopen: LaunchServices returned `-10827` (`kLSNoExecutableErr`) although `Contents/MacOS/LM Studio` exists; direct execution exited with status 134. `Info.plist` passes `plutil -lint`, but `codesign --verify --deep --strict` reports an invalid signature. Spotlight found no second installed copy. No repair, replacement, or reinstall was attempted.
- After the failed relaunch, the `server-logs` root still reports `0700`; all 68 files remain `0644`. This confirms the filesystem mode survived the app exit, but not a successful application restart or directory recreation. `lms server status` reports that the local API server is not running; no request was sent and no server was started.
- The folder/file metadata does not establish application-managed rotation or retention: the inventory shows dated monthly directories and historical files, but does not prove a maximum age, size cap, or deletion policy. No log content was inspected.

## Operational rule

- Never expose model I/O logs in screenshots, recordings, tickets, or public reports when real or sensitive data may be present.
- This installed configuration demonstrably persists at least portions of synthetic model input and output in a local server-log file. Do not send sensitive customer content through it until access, retention, redaction, and disablement controls are explicitly assessed and accepted.
- If model I/O inspection is necessary, use only an explicitly approved isolated test with synthetic documents/accounts and record only a sanitized pass/fail conclusion.
- Before handling real customer data, verify the exact installed version's log storage, access, retention, redaction, and disablement controls. If those controls are insufficient or unclear, do not send sensitive content to that provider configuration.

## Status

Documentary risk review: **PASS** (official capability and data sensitivity are documented).

Live model-I/O stream exposure check: **PASS** (synthetic input and output markers were both visible in the local stream).

Installed-instance server-log inventory: **PASS, metadata only** (dated log files persist locally; observed file/directory permission bits are recorded above).

Synthetic model-I/O persistence check: **CONFIRMED** (both unique input and output markers were found only in newly appended bytes from one local server-log file; raw log text was not inspected or retained).

Local filesystem access mitigation: **APPLIED** (`server-logs` root is owner-only `0700`; files were not changed or deleted). The mode remained `0700` after a normal app quit, but the app could not relaunch because its signature is invalid; persistence across a successful restart or directory recreation remains unverified.

Full log-content classification, accounts outside the observed `/Users` homes, application-managed rotation/retention, redaction, and disablement controls: **OPEN**. Treat the local log directory as sensitive. Do not send sensitive customer content to this configuration until the remaining controls are assessed and accepted; any further permission change or log deletion needs explicit, separate approval.
