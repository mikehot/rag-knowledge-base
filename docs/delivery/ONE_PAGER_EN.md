# Enterprise Knowledge-Base Q&A — One Pager

An enterprise Q&A system where employees only retrieve documents they're permitted to see, every answer
cites its source, and a question the documents don't cover gets refused instead of invented. Deployable
locally, with the option to keep data from leaving your network.

## The problem

Once a company's policies, product manuals, and support FAQs go into an AI Q&A system, the real questions
are:

- Can an employee see information they're not supposed to?
- Where did the answer come from — could the model fabricate a citation?
- Will it guess an answer when the documents don't actually cover the question?
- If access is revoked, can a prior conversation still be used to extract the old answer?
- How do you measure whether it's accurate, fast, and affordable enough?

## Scope

| Included | Optional | Not promised |
|---|---|---|
| Document ingestion (PDF/Word/Markdown), versioned, rebuildable index | Local model deployment (data never leaves your network) | 100% accuracy — the system is designed to refuse and hand off to a human when the documents don't have an answer |
| Access control by department/role/document, with an audit trail on every permission change | Cloud model (verified against DeepSeek: higher quality, lower latency) | A fixed accuracy number not verified against your own documents |
| Citation-backed answers — citations are validated server-side, not just stated by the model | A standalone Python agent layer (multi-step tool calls, conversation memory, MCP integration) | Production SLA — this is a reproducible local/demo deployment today, not a live production system |
| Admin console (upload, permissions, index status), deployment and data-handling docs | | |

## How to accept delivery

You don't have to take our evaluation numbers on faith — the [Acceptance Handbook](ACCEPTANCE_HANDBOOK.md)
walks through running the same evaluation tooling against **your own documents and questions**, with clear
pass/fail criteria for permission isolation, refusal on missing evidence, citation verification, and
access revocation. For where your data goes and how long it's kept, see
[Data Handling](DATA_HANDLING.md).

## Technical notes

Java 17 / Spring Boot + PostgreSQL (pgvector) + a Flutter client. Permissions are enforced inside the SQL
retrieval query, not filtered at the UI layer. Citations are generated and validated server-side; any
non-compliant model output fails closed to a human handoff. Evaluation methodology, tool-call reliability,
and cost/latency comparisons are in section 5 of the handbook.

## How it was built

Implementation code was written primarily by AI coding agents (OpenAI Codex, Claude Code); requirements,
architecture, evaluation design, code review, and acceptance decisions were mine. Full background:
[PORTFOLIO.md](../../PORTFOLIO.md) and the [Case Study](../CASE_STUDY_EN.md).

## Contact

<!-- TODO: add contact details -->
