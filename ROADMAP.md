# Enterprise RAG Knowledge Base — Roadmap

> Version: v3.0
>
> Last updated: 2026-09-24
> Previous version (with per-milestone experiment logs): [docs/ROADMAP_V2_ARCHIVE.md](docs/ROADMAP_V2_ARCHIVE.md)
> Verified status lives in [PROGRESS.md](PROGRESS.md); requirements in [REQUIREMENTS.md](REQUIREMENTS.md).

## 1. Why This Project Exists

This is the flagship portfolio project for an AI career transition. It has to work for two audiences.

| Audience | What they look for | What this project must show |
|---|---|---|
| **Hiring** (AI application engineer / FDE-style roles) | Engineering depth, security boundaries, evaluation method, honest trade-offs, English communication | Architecture and design decisions, ACL enforced at retrieval, fail-closed behavior, a reproducible evaluation with known limitations, English case study |
| **Freelance clients** (Upwork / domestic platforms) | A working demo, fast delivery, clear scope, running cost | A 3–5 minute video, one-command local start, cloud-model cost per 1k questions, a scoped "RAG knowledge base" service description |

One-line pitch: *An enterprise knowledge-base Q&A system (Spring Boot, pgvector, Flutter) where employees only retrieve documents they are allowed to see, every answer cites its sources, and unknown questions are handed off instead of invented, with evaluation, audit, and deployment runbooks.*

A reviewer should be able, in about 15 minutes, to watch the demo, start the system from the README, understand three design decisions, and see evaluation results with their limitations.

## 2. Product Scope (V0.1)

An administrator uploads documents; the system parses, chunks, embeds, and indexes them. An authenticated employee asks questions and retrieves only authorized knowledge. Answers are grounded in retrieved content and cite sources; uncovered questions return `found=false` and are handed to a human. Operators can inspect latency, token usage, failures, audit events, and feedback.

## 3. Technical Direction

- Java / Spring Boot backend; PostgreSQL + pgvector for metadata, audit, and vectors; Flutter client.
- Python only for evaluation and data tooling; no service rewrite.
- OpenAI-compatible provider interface for chat and embedding, so local (LM Studio) and cloud providers are configuration, not code.
- JDK: local and CI run JDK 25 (`.java-version`); `pom.xml` targets Java 17 bytecode.
- No Spring Boot 4 / Spring AI 2 migration during V0.1–V0.2.

## 4. Delivery Principles

- Permissions are a retrieval constraint, enforced in SQL before content reaches the model. Missing or stale permission context fails closed.
- Citations are built from retrieved metadata, never invented by the model.
- Every capability ships with a normal path, a failure path, tests, and evidence.
- Prompts, document text, credentials, and personal data stay out of logs by default. Local providers receive synthetic or public data only (see the provider checklist in `docs/DEPLOYMENT_RUNBOOK.md`).
- **Only optimize on data that can show a difference.** Retrieval tuning requires a corpus and question set large enough that the effect exceeds run-to-run noise.
- **Each work week should produce something a reviewer can see** (a demo, a document, a measurable result), not only internal evidence.

## 5. Milestone Status

| Milestone | Status | Summary |
|---|---|---|
| M1 Reproducible RAG baseline | `verified` | Upload → index → cited answer → refusal → delete, on JDK 25 + pgvector + LM Studio |
| M2 Identity, ACL, document lifecycle | `in-progress` | Tenant/department/role/document ACL enforced in SQL; lifecycle and index tasks; device evidence for USER/ROLE grant/revoke. Department grant/revoke and the post-revoke ask/citation path are covered by a PostgreSQL integration test (2026-09-24). Permission changes are audited in the same transaction (2026-09-26). Open: one device spot check |
| M3 Structured answers, audit, observability, feedback | `verified` | Structured-output contract, fail-closed handling, requestId/timings, audit, metrics, feedback; 138 backend tests |
| M4 Evaluation baseline | tooling `verified` | golden-v1 (20), answer-quality-v1 (12), stress (8); frozen gate in `evaluation/README.md`. Current default fails the gate (9–10/12) on a 7-document synthetic fixture |
| M5 Evaluation-driven retrieval | **paused** | Keyword-RRF, diversity, adjacent, chunk size, and heading-aware chunking all evaluated; none beats the default on this fixture. Resumes in Phase B on a realistic corpus |
| M6 Read-only Agent tools / MCP | `in-progress` (bounded) | Three read-only tools and a stateless MCP adapter; local smoke 16/16. Real-client demo is optional Phase C |
| M7 Delivery package | `in-progress` | Architecture, discovery brief, demo script, runbook, zh/en case study, backup/restore rehearsal. Clean-clone rehearsal done (2026-09-24; README/DEMO gaps fixed; found and fixed a reindex availability defect). Portfolio, README, and case studies refreshed (2026-09-24). Open: video |

## 6. Plan

### Phase A — V0.1 Showable (target: about 1 week)

Goal: a reviewer from either audience can see, run, and understand the project.

| # | Work | Done when |
|---|---|---|
| A1 | Department ACL grant/revoke and the post-revoke employee ask/citation path | Automated API/PostgreSQL tests cover department grant → visible/answerable → revoke → invisible, refused, no citation; one device spot check |
| A2 | Clean-clone rehearsal | From a fresh clone, following only the README: start, upload, authorized answer, refusal, permission denial, failure retry. Every README gap found is fixed; time to first answer is recorded |
| A3 | V0.1 quality statement | Current results (rubric-v2 scores, chunking A/B, known misses Q002/Q006) are stated as known limitations; the quality gate is tracked but **does not block V0.1** |
| A4 | Portfolio refresh | `PORTFOLIO.md` and the README opening rewritten for both audiences: pitch, architecture diagram, three design decisions, evaluation with limitations, how to run. Case studies synced. `CODEX_PROMPT.md` archived |
| A5 | Demo video and screenshots | 3–5 minute sanitized recording on synthetic data covering upload, cited answer, refusal, permission denial, and admin ACL; no provider logs or credentials on screen |

V0.1 exit: A1–A5 done, CI green, and PROGRESS/PORTFOLIO claims match evidence.

### Phase B — V0.2 Meaningful Evaluation (target: 1–2 weeks)

Goal: evaluation numbers that mean something, plus a cloud-cost story for clients.

| # | Work | Done when |
|---|---|---|
| B1 | Realistic public corpus | 20–50 Chinese documents with licenses that permit redistribution (e.g. public product manuals or policies), at least ~100k characters, with synthetic ACL groups; a versioned ~50-question dataset covering answers, multi-part answers, refusals, and ACL cases |
| B2 | Re-baseline, then retrieval work | Default config measured with the frozen gate procedure. Only then, in this order, with an offline screen first: chunking → multilingual embedding → keyword/RRF → reranker |
| B3 | Cloud provider comparison (chat part done 2026-09-26: DeepSeek flash passes the gate; see `evaluation/reports/cloud-provider-deepseek-local-2026-09-26.md`) | One cloud chat and embedding model through the OpenAI-compatible config, on public data only; quality, latency, and cost per 1k questions compared with the local stack |
| B4 | Public demo decision | Go/no-go on a small hosted demo (cloud model, synthetic/public data, rate-limited, disposable credentials). Not required for V0.2 |

### Phase C — Optional Differentiation

- C1: Connect a real MCP client (e.g. Claude Desktop or MCP Inspector) to `/mcp` with JWT and demo ACL-scoped `search_knowledge`.
- C2: English technical write-up of the design decisions and the evaluation lessons.

## 7. Not Doing

Stopped (low value for the goal):

- Retrieval tuning on the current 7-document synthetic fixture: it is saturated and below the noise floor.
- Further investigation of LM Studio's own log rotation, retention, or permissions; closed by scope decision.

Deferred until a measured need exists:

- fine-tuning; GraphRAG / Neo4j; complex multi-agent graphs;
- Kubernetes and multi-region; local GPU procurement; voice/WebRTC agents;
- a Python rewrite; Agent write operations without human approval.

## 8. Progress Rules

`PROGRESS.md` states: `planned`, `in-progress`, `verified`, `blocked`. A verified item records the commit, environment and commands, test/runtime evidence, the evaluation or demo artifact, the data boundary, and known limitations. Detailed experiment evidence goes to `evaluation/reports/`; PROGRESS and ROADMAP keep conclusions only.

## 9. Reference Decisions

- [Spring AI project and version compatibility](https://github.com/spring-projects/spring-ai)
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [pgvector: hybrid search with PostgreSQL full-text search](https://github.com/pgvector/pgvector)
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/17/ddl-rowsecurity.html)
- [OWASP Top 10 for LLM and GenAI Applications](https://genai.owasp.org/initiatives/top-10-for-llm-and-genai/)
