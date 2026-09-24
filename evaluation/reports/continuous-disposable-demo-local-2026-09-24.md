# Continuous Disposable Demo — 2026-09-24

## Scope and isolation

- Started from a new empty PostgreSQL 16.15/pgvector container with no persistent volume, bound only to `127.0.0.1:55488`; the backend used `127.0.0.1:8089` and a new temporary upload directory.
- Used only the repository sample FAQ, synthetic policy/stress fixtures, and synthetic administrator/employee/outsider accounts. The default development database on port 5432 was not connected to or modified.
- Chat and Embedding were served by the locally configured LM Studio models. The 26B Chat model load response reported an effective context length of 226304 even though this run requested 8192; model settings were not treated as a controlled evaluation configuration. This was a functional demo, not a quality benchmark.

## Continuous API flow

- Empty database migrated through Flyway V13; `/readyz` returned HTTP 200.
- Fixture preparation indexed 7 documents. Authenticated document lists contained 7 for the administrator, 5 for the employee, and 1 for the outsider. The two restricted finance/HR documents were absent from both non-admin lists; observed ACL leakage was 0.
- The initial happy-path wording `整机保修期是多久？` was refused with `INSUFFICIENT_CONTEXT`; protected retrieval placed the FAQ outside Top-5. A diagnostic wording `智能门锁整机保修期几年？` brought `sample_faq.md` to rank 3 (similarity 0.632), after which the employee received a grounded answer with the expected citation and answer point. This demonstrates wording sensitivity and is not evidence that the known Top-5 quality gap is resolved.
- An out-of-scope equity-policy question returned `found=false` and no sources. A direct read attempt on a restricted policy returned 403/404, and the outsider's `get_document_status` tool call returned `PERMISSION_DENIED`.
- The employee's feedback write was accepted. The repository MCP HTTP smoke passed 16/16 checks.

## Controlled indexing failure and recovery

- In this disposable environment only, the backend was restarted with an unavailable Embedding URL and a single synthetic FAQ reindex was requested.
- The persisted task reached `FAILED` at attempt 3 of 3 with a failure message. The backend was restarted using the valid local Embedding URL; a SYSTEM_ADMIN retry advanced the same task to `SUCCEEDED` at attempt 4, and the document returned to `ready` (reported duration 159832 ms).
- The failure endpoint was not changed in LM Studio or repository configuration; no task state was edited directly in SQL.

## Provider log privacy probe

- A short-lived `lms log stream --source model --filter input,output --json` process observed unique synthetic input and output sentinels from one local Chat request. The probe printed only booleans; raw stream lines and model response bodies were neither printed nor written to disk.
- Result: current model I/O stream exposes both request input and generated output. Treat it as sensitive; do not use it with customer documents unless explicitly approved and controlled.
- This did not inspect Developer Logs UI, provider log files on disk, persistence/rotation, access control, redaction, or retention settings. Those remain open for the installed-instance privacy review.

## Cleanup and limits

- Stopped the temporary backend and database container, deleted synthetic credentials/manifest/MCP summary/upload directory, and unloaded only the Chat and Embedding models loaded by this run. Both test models were confirmed unloaded; no temporary container remained.
- The new Flutter operations screens were not exercised on device. This run does not pass the Golden/Answer Quality gates, establish a stable Thinking/configuration baseline, prove production backup/privacy controls, or support customer accuracy/SLA/ROI claims.
