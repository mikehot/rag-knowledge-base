# MCP Read-Only Smoke Report

Date: 2026-09-22
Environment: local Spring Boot source service on `http://localhost:8081`,
PostgreSQL/pgvector through Docker Desktop, LM Studio available but not used
by the `list_documents` smoke call.
Command:

```bash
python3 evaluation/run_mcp_smoke.py \
  --base-url http://localhost:8081 \
  --credentials /private/tmp/rag-eval-admin.json \
  --output /private/tmp/mcp-readonly-smoke-local.json
```

## Result

PASS: 16/16 aggregate checks passed.

Validated:

- `server/discover` returns JSON-RPC 2.0 and protocol version `2026-07-28`.
- `tools/list` returns exactly the three approved read-only tools:
  `search_knowledge`, `list_documents`, and `get_document_status`.
- The catalog returns `ttlMs=0` and `cacheScope=private` for ACL-dependent
  tool discovery.
- An authenticated `list_documents` call succeeds through the existing tool
  boundary.
- An attempted `tenantId` identity override is rejected by the closed tool
  schema and returns an MCP tool error result.
- A `Mcp-Method`/JSON-RPC method mismatch returns JSON-RPC `-32600`.
- An unauthenticated request is rejected with HTTP 401.

## Boundary

This is a source-service HTTP smoke check, not a complete MCP conformance or
SDK interoperability certification. The adapter is stateless and intentionally
does not expose sessions, Tasks, Resources, Prompts, OAuth metadata,
notifications, a model-driven Agent loop, or write tools. The smoke client
also does not claim that any external MCP host has completed a full handshake.

The protocol target is the stateless MCP `2026-07-28` revision described in the
[official MCP release notes](https://blog.modelcontextprotocol.io/posts/2026-07-28/).
