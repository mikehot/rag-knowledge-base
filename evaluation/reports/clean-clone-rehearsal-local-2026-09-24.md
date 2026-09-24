# Clean-Clone Rehearsal — 2026-09-24

Roadmap item A2: from a fresh clone, follow only the README/DEMO and record every gap.

## Environment

- Fresh `git clone` of `172ce5c` into a scratch directory; macOS, Docker 29.8, JDK 25.0.3, `curl`, `jq`.
- LM Studio at `localhost:1234`: `google/gemma-4-26b-a4b-qat` loaded with Enable Thinking off, `text-embedding-nomic-embed-text-v1.5`.
- Maven and the Docker image were already cached on this machine, so first-run download time was **not** measured.
- A one-line `docker-compose.override.yml` renamed the DB container to avoid clashing with this machine's existing `rag-knowledge-base-db` dev container (see finding 7). The dev container and its volume were not started or modified.

## Timeline (warm cache)

| Step | Time |
|---|---|
| `git clone` → `docker compose up -d db` | 1 s |
| `./mvnw spring-boot:run` → `/readyz` UP | ~10 s |
| Upload `sample_faq.md` → `ready` (2 chunks) | a few seconds |
| First cited answer (`found=true`, `sample_faq.md`) | 3.5 s request latency |

Machine time from clone to first cited answer was under one minute. In this run, wall time was about 2.5 minutes, because of an error in the rehearsal's own polling script.

## Flows verified

| Flow | Result |
|---|---|
| Covered question | `found=true`, `grounded=true`, source `sample_faq.md` |
| Uncovered question | `found=false`, no sources, `INSUFFICIENT_CONTEXT` |
| Feedback on `requestId` | accepted |
| Agent tool catalog | exactly `search_knowledge`, `list_documents`, `get_document_status` |
| MCP smoke (`run_mcp_smoke.py`) | 16/16 |
| New employee, no grant | document list empty, detail 404, ask `found=false` |
| Grant USER READ via API | list shows the FAQ, ask `found=true` with citation |
| Revoke | list empty, ask `found=false`; employee delete attempt → 404 |
| Embedding endpoint down | ask returns `EMBEDDING_ERROR` (HTTP 200); reindex task `FAILED` after 3 attempts (~90 s), readable error |
| Endpoint restored + admin retry | task `SUCCEEDED` at attempt 4, document `ready`, cited answers resume |

After the README was rewritten, its Quick Start code blocks were extracted and executed **verbatim** in a second fresh clone. Upload, both questions, and the no-grant → grant → revoke employee sequence all passed in about 7 seconds.

## Findings and fixes

1. **README stopped at "backend running".** It had no steps to upload, ask, or show permissions. Fixed: the Quick Start now covers start → upload → wait for ready → covered/uncovered question → employee grant/revoke, all verified verbatim.
2. **DEMO step 0 ended with `cd backend`, so the step 1 upload (`@sample_faq.md`) failed silently** (`curl -s` hides the error). Fixed: all commands run from the repository root, and the backend starts with `(cd backend && ./mvnw spring-boot:run)`.
3. **DEMO gave no polling command, and the list response is `.data.items[]`.** Fixed with an explicit command.
4. **DEMO step 5 referenced `/private/tmp/rag-eval-admin.json` without saying how to create it.** Fixed.
5. **DEMO ACL step required the evaluation fixture scripts.** Fixed: it now points to a curl-only grant/revoke flow, with the fixture path kept as the full option.
6. **DEMO failure-recovery step was descriptive only.** Fixed with concrete commands and the observed timings.
7. **`docker-compose.yml` hard-codes `container_name`**, so a second checkout on the same machine conflicts. It is documented in the README prerequisites. Removing the fixed names would rename this machine's existing dev containers, so the change is left to an explicit decision.
8. **README did not name the default chat model or the Gemma Thinking-off requirement.** Fixed in the prerequisites.
9. **Defect: a failed or in-progress reindex takes an indexed document offline.** Reindex sets the document to `processing`/`failed`. Old chunks stay in the table, but retrieval requires `status = 'READY'`, so the document is unanswerable until a retry succeeds. This was confirmed live: with the embedding endpoint restored but before retry, the covered question returned `RETRIEVAL_MISS`. In practice, a transient provider outage during a batch reindex would take the whole knowledge base offline. The replace path restores the old searchable version on failure; reindex does not. **Fixed on the same day:** retrieval no longer filters on document status (chunks only exist for a committed version, swapped in one transaction), and `markFailed` keeps `chunkCount`. The integration test `previousIndexStaysSearchableWhileReindexIsProcessingAndAfterItFails` fails without the fix and passes with it; a live replay (index → embedding down → reindex FAILED → endpoint restored, before retry) now returns a cited answer. A trade-off remains: during an embedding-model migration, old vectors keep serving until the rebuild completes (see `docs/ARCHITECTURE.md`).

## Cleanup

Rehearsal backends, containers, volumes, uploads, and demo credentials were removed. Port 8080 was released, and the existing dev containers were untouched.
