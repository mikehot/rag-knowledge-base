# Upgrading a nomic-built database to the new bge-m3 default — local rehearsal, 2026-10-21

Question: `backend/README.md` says a database built with `text-embedding-nomic-embed-text-v1.5` stops being
searchable under the new default until it is rebuilt, and that nothing is answered from old vectors. Is that what
actually happens?

## Procedure

1. Disposable pgvector database and the synthetic fixture (7 documents, 9 chunks) built with
   `AI_EMBEDDING_MODEL_ID=text-embedding-nomic-embed-text-v1.5`.
2. Backend stopped and restarted from the same branch (`ddde736`) with `AI_EMBEDDING_MODEL_ID` and `AI_TEMPERATURE`
   **unset**, i.e. the shipped defaults, against the same database.
3. Observe the log, `GET /api/admin/embeddings` and `POST /api/ask`; then
   `POST /api/admin/knowledge-bases/{id}/index-tasks/batch-reindex`; observe again.

## Observed

| Step | Observation |
|---|---|
| Before the restart (nomic) | status: configured nomic, 768 d, 7/7 documents, rebuild complete; QUALITY-001 answered, grounded, cites `sample_faq.md`; QUALITY-002 got `INSUFFICIENT_CONTEXT` (the known nomic miss) |
| After the restart, nothing rebuilt | startup `WARN`: "9 stored chunks were embedded with a model other than 'text-embedding-bge-m3'; they are not searchable until their documents are reindexed". Status: configured `text-embedding-bge-m3`, 0/7 documents on it, 7 waiting, `rebuildComplete: false` |
| Ask under the new default, old index | `found: false`, `failureReason: RETRIEVAL_MISS`, no sources, the fixed hand-off text. Nothing was answered from the old vectors |
| `batch-reindex` | 7 tasks accepted; status reached `rebuildComplete: true`: bge-m3, 1024 d, 9 chunks, 7/7 documents |
| Ask after the rebuild | QUALITY-001, QUALITY-002 and QUALITY-007 all `found: true`, grounded, cite `sample_faq.md`; QUALITY-002, a miss under nomic, now answers |

No `ERROR` lines in the backend log. The documented behaviour (warning, `RETRIEVAL_MISS` hand-off, rebuild through
`batch-reindex`) is confirmed end to end, and starting with the variables unset confirms the shipped default
really is bge-m3.

## Limits

- Synthetic fixture, 7 small documents; the rebuild took seconds here. The 28-document corpus took about 12 s in
  the 2026-10-13 rehearsal; larger corpora were not tried.
- The other documented route, pinning `AI_EMBEDDING_MODEL_ID` back to nomic, was not exercised separately; it
  relies on the same per-chunk model check in the other direction.
- Three questions after the rebuild show that answering works; they are not an accuracy measurement. The gate
  result is in [quality-gate-shipped-defaults-local-2026-10-21](quality-gate-shipped-defaults-local-2026-10-21.md).
