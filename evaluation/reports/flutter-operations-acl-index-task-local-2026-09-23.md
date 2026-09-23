# Flutter Operations API Acceptance — ACL and Index Tasks (2026-09-23)

## Scope and isolation

- Used a new PostgreSQL 16.15/pgvector disposable container on `127.0.0.1:55485`, an isolated backend on port `8088`, synthetic tenant users, and the repository's sanitized FAQ/stress fixtures.
- The existing development database on port `5432` was not used. The empty database migrated through V13; local retrieval experiments remained disabled.
- API responses, credentials, task/document IDs, answer bodies, and raw provider diagnostics were not retained in this report.

## ACL revoke flow

1. Granted the synthetic employee `READ` access to the sample FAQ.
2. The employee's authenticated read-only `search_knowledge` call returned the FAQ as a source.
3. Revoked that exact document ACL as the synthetic administrator.
4. A subsequent employee `search_knowledge` call no longer returned the FAQ, and the employee's document list no longer contained it.

Result: PASS for the API authorization boundary; observed ACL leakage was 0. This verifies server behavior, not the Flutter revoke dialog or an end-user device interaction.

## Persistent task status and retry flow

1. An isolated invalid Embedding endpoint was used to create a controlled indexing failure; the task reached `FAILED` at attempt 3 and exposed a failure message.
2. The backend was restarted against the same disposable database with the valid local Embedding provider.
3. A SYSTEM_ADMIN retried the failed task through the existing retry endpoint. It reached `SUCCEEDED` at attempt 4.

Result: PASS for the API/task recovery path. The failure was synthetic and isolated; no provider configuration or document in the development database was changed.

## Flutter device boundary

The Flutter debug build succeeded. For isolation, the test build used a temporary `.verify` Android application ID so it would not overwrite the existing app or its session. Android returned `INSTALL_FAILED_USER_RESTRICTED`; no install bypass was attempted. Therefore the new ACL and index-task Flutter screens are **not yet device-verified**.

The isolated backend, database, uploads, temporary credentials, manifests, scripts, and ADB reverse mapping were removed after the test. This is local synthetic acceptance evidence, not a public deployment or production security claim.
