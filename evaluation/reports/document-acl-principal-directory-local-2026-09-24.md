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

## Device and evidence limits

The new Flutter client was built but was not installed or exercised on-device in this follow-up. The non-system-manager UI flow therefore remains pending real-device verification. A prior device-control attempt was interrupted after the foreground app changed unexpectedly; no further device input was issued. This report makes no claim of a successful employee question/refusal on-device, production ACL security, or answer-quality success.
