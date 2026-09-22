# Enterprise RAG V0.1 Disposable Demo Report

Date: 2026-09-22
Environment: isolated PostgreSQL/pgvector database `rag-demo-db` on port
55432, current source backend on port 8082, Flyway schema v12, local
OpenAI-compatible Provider. The existing Docker project database on port 5432
was not modified.

## Result

PASS: the API-level Demo completed in an isolated environment. The report
contains aggregate fields only; credentials, JWTs, answers, document text and
request IDs were not written here.

| Flow | Result |
|---|---|
| Login | HTTP 200 / API code 0 |
| Upload `sample_faq.md` | HTTP 200 / API code 0 |
| Persistent indexing | 1 document `READY`; 1 task `SUCCEEDED`; 2 chunks |
| Authorized question | HTTP 200; `found=true`; `grounded=true`; 1 source |
| User feedback | HTTP 200 / API code 0 |
| Out-of-scope question | HTTP 200; `found=false`; 0 sources; fail-closed `STRUCTURED_OUTPUT_INVALID` |
| Disposable employee login | HTTP 200 / API code 0 |
| Employee document list | HTTP 200; visible document count `0` |
| Employee question | HTTP 200; `found=false`; 0 sources; `RETRIEVAL_MISS` |
| MCP discovery | HTTP 200; protocol `2026-07-28` |
| MCP tool catalog | HTTP 200; exactly 3 read-only tools |
| MCP `list_documents` call | HTTP 200; tool call succeeded |
| MCP identity override | HTTP 200; tool error result rejected the override |
| Unauthenticated MCP request | HTTP 401 |

## What this proves

- The ingestion path can migrate an empty PostgreSQL database, create a
  persistent indexing task, publish two chunks, and reach `READY`.
- An authorized user receives a grounded answer with backend-owned citation
  metadata.
- A model/provider contract failure is fail-closed: it does not become a
  fabricated answer or citation.
- Server-side ACL filtering is effective for a user with no document access;
  the employee receives neither document visibility nor retrieved sources.
- The read-only MCP adapter preserves authentication and the application-owned
  Tool Registry boundary.

## Limitations

This was an isolated local API Demo, not a Flutter device recording, public
deployment, load test, customer dataset, or production SLO. The out-of-scope
question exercised the provider's invalid structured-output path, so the
observed refusal reason was `STRUCTURED_OUTPUT_INVALID`; this is evidence of
safe failure handling, not proof of stable model quality. The existing
structured-output and answer-quality reports remain the source of truth for
model capability gates.
