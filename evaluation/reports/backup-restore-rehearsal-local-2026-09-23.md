# Local Backup/Restore Rehearsal — 2026-09-23

## Result

PASS for one manual local restore of a PostgreSQL custom-format dump plus the uploaded-file directory into a fresh disposable environment.

This is not evidence of production backup automation, encryption, off-site replication, point-in-time recovery (PITR), retention/rotation, recovery time/objective, or disaster-recovery SLA.

## Isolation

- Source and target were separate PostgreSQL 16.15/pgvector containers with no persistent volumes, bound only to local ports 55486 and 55487.
- The restored Spring Boot service listened on local port 8090 and used a separate extracted upload directory.
- Only synthetic actors and sanitized repository fixtures were present. The development database on port 5432 was not connected to or modified.
- The custom-format dump, upload archive, credentials, manifest, source/target containers, and extracted directories were removed after verification.

## Procedure and checks

1. Prepared the fixture through the existing authenticated API against the isolated source instance; confirmed documents reached READY.
2. Exported the source database as a PostgreSQL custom-format dump and separately archived the configured upload storage directory.
3. Restored the dump into a newly created empty target database and extracted the upload archive into a new target directory.
4. Started the application against only the restore target. Readiness returned `UP`; Flyway validated all 13 migrations and reported the restored schema already current.
5. Used synthetic administrator and employee accounts to verify restored data through application APIs:
   - administrator document list: 7 synthetic documents;
   - employee document list: 5 ACL-authorized documents;
   - restricted finance/HR fixtures remained absent for the employee; observed ACL leakage: 0;
   - 7 persisted indexing tasks were present and all reported `SUCCEEDED`.
6. Ran `evaluation/run_mcp_smoke.py` against the restored service: 16/16 checks passed.

No raw credentials, document contents, answers, task IDs, or request IDs are retained here. The test did not exercise an actual production backup scheduler, cloud object storage, encrypted archives, point-in-time recovery, or a documented restore-time target.

## Follow-up

Add an operator-owned scheduled backup and retention policy only when the deployment target is selected; then test encrypted/off-site backup access, restore from that target, and measured RPO/RTO. Keep provider-side prompt/document logging review as a separate privacy gate.
