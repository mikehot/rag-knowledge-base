# Flutter ACL and Index-Task Device Acceptance — 2026-09-24

## Scope and environment

- Used the isolated Android debug package `com.example.rag_knowledge_base_app.verify`; the everyday app package was not replaced.
- Device OS: Android 16 / API 36. Backend and PostgreSQL/pgvector ran as local disposable services on loopback-only ports 8089 and 55490. The database had no persistent volume.
- All users and documents were synthetic. The fixture contained seven indexed documents. The LM Studio embedding endpoint was changed only in the disposable backend process; persistent LM Studio settings were not modified.
- This is local device/API acceptance evidence, not a production, public-deployment, accessibility, or performance claim.

## ACL grant and revoke

1. In the Flutter knowledge-base screen, opened `finance-policy.md` → **Manage document permissions**, selected the synthetic employee, and granted `READ`.
2. A read-only employee document-list request then returned six documents and included `finance-policy.md` (baseline: five).
3. In the same Flutter dialog, selected **Revoke**, confirmed the explicit warning, and revoked the grant.
4. A fresh employee document-list request returned five documents and no longer included `finance-policy.md`.

The UI grant/revoke path and immediate list-visibility change passed. This device run did not submit an employee question or independently test citations after revocation; the separate 2026-09-23 API acceptance report covers search/list revocation behavior.

## Persistent index-task failure and recovery

1. From the device document menu, requested reindex for one synthetic document while the disposable backend's embedding URL pointed to an unavailable loopback port.
2. The Flutter **Index tasks** panel displayed the failure reason, attempt count `3/3`, recorded duration, and **Safe retry** action. The persisted task reached `FAILED`; recorded duration was 90,385 ms.
3. Restored the backend's embedding URL to the already-running LM Studio API, then tapped **Safe retry** in the device UI.
4. The panel and a fresh administrator read-only API check reported `SUCCEEDED`, attempt `4/6`, and recorded duration 247,181 ms. The document returned to `ready`; all seven fixture documents were ready, with zero processing or failed documents.

No task status was edited directly in SQL. The backend's original retry/backoff behavior was unchanged.

## Small UI correction found during acceptance

During the injected retry delay, a task is persisted as `PENDING` with a non-empty prior failure message. The previous UI label called this `排队中` while also showing `失败：…`. The Flutter label now reports `等待重试` for that exact state and keeps a new, error-free `PENDING` task as `排队中`. Four focused unit cases cover pending-with-error, pending-without-error, blank error, and the remaining task states. This mapping was unit-tested; the post-fix device session completed on a `SUCCEEDED` task, so the transient label itself was not re-observed on-device.

## Verification and cleanup

- `flutter analyze`: passed.
- `flutter test --no-pub --concurrency=1`: 12 tests passed.
- `flutter build apk --debug --no-pub --dart-define=API_BASE_URL=http://localhost:8089`: passed; the isolated `.verify` APK installed and launched on the device.
- The grant/revoke result was checked through fresh employee API reads; task failure and recovery were checked through the Flutter panel and a fresh administrator API read.
- Temporary credentials, manifest, uploads, backend, database container, and ADB reverse mapping were removed after acceptance. No screenshots, credentials, model-I/O logs, or document bodies were added to the repository.

## Limits

- Only the synthetic administrator UI flow was exercised; the non-system-admin document-manager path and principal-directory error presentation remain open.
- ACL visibility was checked with the employee document-list API after UI mutations; employee ask/citation behavior was not re-run in this device session.
- No video, public demo, customer document, production SLA, or answer-quality gate is implied.
