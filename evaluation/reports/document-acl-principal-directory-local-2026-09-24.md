# Document ACL Principal Directory — Local Acceptance — 2026-09-24

## Scope and isolation

- Used a new PostgreSQL/pgvector container without a persistent volume, loopback-only backend ports, and synthetic accounts/documents. The default database on port 5432 was not used.
- The fixture contained seven sample/synthetic documents. A non-`SYSTEM_ADMIN` synthetic user received `MANAGE` on `sample_faq.md`; another synthetic employee's `READ` grant on that document was then revoked.
- No customer content, credentials, answer text, or model-I/O logs were added to the repository.

## Finding and narrow fix

Before the fix, the non-system document manager could list the managed document and read its ACL (HTTP 200), but the Flutter dialog fetched candidate identities from `/api/admin/users`, which correctly returned HTTP 403 to that user. The same mismatch applied to department and role candidates.

Added `GET /api/documents/{documentId}/acl/principals?type=USER|DEPARTMENT|ROLE`. The service first requires `MANAGE` on that exact document, scopes candidates to the caller's tenant, returns only active users/departments (roles are tenant-scoped; the role table has no status field), and exposes only the fields needed by the picker. The Flutter ACL dialog now uses this document-scoped endpoint. Existing `/api/admin/*` directory endpoints remain system-admin-only.

## Verification

- In the disposable API fixture, a document manager received HTTP 200 for user, department, and role candidates; `/api/admin/users` remained HTTP 403. An employee without `MANAGE` received HTTP 404 from the document-scoped candidate endpoint.
- PostgreSQL integration coverage exercises a non-system user with a document-level `MANAGE` grant, confirms that same-tenant candidates are returned, excludes a second-tenant user, and confirms that a user without `MANAGE` is denied.
- Full backend suite: 119 passed, 0 failures/errors/skips. Flutter analysis: no issues; Flutter tests: 12 passed; isolated `.verify` Debug APK build passed.
- After revoking the employee's `READ` grant, one `/api/ask` attempt persisted `found=false` but ended as `STRUCTURED_OUTPUT_INVALID` (2,957 tokens). Its retrieval snapshot contained five chunks from four other employee-authorized synthetic files and no `sample_faq.md`. This supports the ACL retrieval boundary, but it is **not** a completed refusal/citation UI acceptance because generation failed.

## Android device follow-up — 2026-09-24

- Installed the current build as the separate package `com.example.rag_knowledge_base_app.verify` on an Android 16/API 36 device. The everyday package remained separate; the foreground was verified at the launcher before opening `.verify`.
- Used a fresh no-volume pgvector container and loopback-only backend ports 55492/8089. The fixture contained two synthetic EMPLOYEE users, one synthetic department, one READY document, and an initial document-level `MANAGE` grant for the manager. No model or question-generation endpoint was called.
- Confirmed through the app/API that the manager had only the `EMPLOYEE` role and was not a `SYSTEM_ADMIN`. The Flutter ACL dialog loaded same-tenant USER, DEPARTMENT, and ROLE candidates; the synthetic reader, synthetic department, and Employee role were visible in their respective pickers.
- Granted the synthetic reader `READ` through Flutter. Its fresh `GET /api/documents` response changed to include the fixture document. Revoked that grant through the UI confirmation; the reader's list no longer included the document, and its document-scoped principal lookup returned HTTP 404 because it had no `MANAGE` permission.
- Granted `READ` to the Employee role through Flutter; the synthetic reader then saw the document through role inheritance. Revoking the role grant removed it again. Department candidates were loaded and displayed, but department grant/revoke was not exercised on-device.
- Backend suite: 119 passed, 0 failures/errors/skips. Flutter analysis: no issues; Flutter tests: 12 passed; current isolated `.verify` Debug APK built and installed successfully.
- The manager was logged out. The temporary database/container, backend, ADB reverse mapping, UI hierarchy captures, and in-memory synthetic credentials/tokens were removed. The `.verify` package remains installed but logged out, distinct from the everyday app. No screenshot, credential, answer text, customer data, or provider log was retained.

## Remaining evidence limits

This verifies candidate loading and USER/ROLE grant/revoke effects on document-list visibility for one synthetic tenant; the department candidate list was displayed but its grant path was not tested. Cross-tenant exclusion and no-`MANAGE` denial also have PostgreSQL/API coverage, but cross-tenant candidates were not part of this single-tenant device fixture. Employee question/citation behavior after revoke was not tested here; a separate attempt ended with `STRUCTURED_OUTPUT_INVALID`, so this does not establish a successful refusal, production ACL security, or answer-quality success.
