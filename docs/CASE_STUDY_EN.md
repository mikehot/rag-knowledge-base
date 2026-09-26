# Enterprise RAG Knowledge Base — Case Study

> Verified on synthetic data in a local environment. This is not a customer deployment and makes no accuracy, SLA, or ROI claims. Last updated: 2026-09-24.

## One-line summary

I turned a Java/Spring Boot RAG demo into a governed enterprise knowledge-base slice. Employees retrieve only the documents they are authorized to see, every answer carries backend-owned citations, uncovered questions are handed off instead of invented, and each quality decision is backed by a versioned evaluation.

## The problem

A RAG demo is easy. An enterprise also needs answers to five questions. Can each employee see only what they are allowed to? Where did the answer come from, and can the model fake a citation? What happens when a question is not covered? What happens when indexing or the model provider fails? And how do you show the system is good, fast, and affordable?

## How it was built

Most of the implementation code was written by AI coding agents (OpenAI Codex and Claude Code). I owned the scope and requirements, architecture and trade-offs, evaluation design, code review, acceptance, and release decisions. The judgment calls below come from that way of working: the agents execute, and the human decides what to build, when to stop, and how to verify a claim.

## What I built

- **Permissions as a retrieval constraint.** Tenant, department, role, knowledge-base membership, and document ACLs are all enforced inside the vector-search SQL. Unauthorized content never reaches the model context.
- **Backend-owned citations, fail-closed.** The model returns only an answer, `found`/`grounded` flags, and source indexes, under a JSON Schema. The backend validates the output and maps each index to a filename, locator, and snippet. Invalid JSON, out-of-range indexes, contradictory flags, truncated output, and provider timeouts all return a stable failure reason and hand off to a human.
- **Recoverable indexing.** A persistent task queue provides idempotency, retries, and restart recovery. Chunks are swapped in one transaction only after every embedding succeeds, so a failed reindex keeps the previous version serving.
- **Operability.** Request IDs, stage timings, failure categories, token and estimated-cost metrics, audit queries, user feedback, and protected retrieval diagnostics.
- **Bounded agent surface.** Three read-only tools and a stateless MCP adapter reuse the same identity, ACL, call budget, and audit. No write tools.
- **Clients and delivery.** A Flutter app (Q&A, citations, ACL management, index-task panel), Docker Compose, a deployment/incident runbook, and a backup/restore rehearsal.

## Results

| Area | Evidence |
|---|---|
| Access control | PostgreSQL integration tests cover grant → answerable and revoke → refused (after revoke, the model is never called); every permission change is audited (who, when, what) in the same transaction as the change; verified with curl on a fresh clone; zero ACL leakage across all evaluation runs |
| Reliability | Provider outages return stable error codes; failed index tasks recover through admin retry; a failed reindex keeps serving the previous version (integration test plus live replay) |
| Engineering | 126 backend tests including Testcontainers integration tests, CI green, MCP read-only smoke 16/16, Android device acceptance for admin ACL and index tasks |
| Reproducibility | A fresh clone following the README verbatim reaches a cited answer in under a minute of machine time (warm caches) |
| Quality | Local Gemma: answer-quality 8–10/12 per capture, which **does not pass** my own gate (3 consecutive captures ≥ 10/12); golden 15–17/20; stress 8/8. Cloud DeepSeek flash with local embeddings: answer-quality 10/10/10, which **passes**; golden 17/20; stress 8/8; median latency about 1–1.6 s; under $1 per 1,000 questions at list price. See [PORTFOLIO.md](../PORTFOLIO.md) |

## Judgment calls

1. **Negative results count.** Keyword-RRF, diversity reranking, adjacent chunks, four chunk sizes, and heading-aware chunking were each compared on the same versioned datasets under a fixed configuration record. A 300-character chunk size fixed two known misses and passed the answer-quality gate, but golden and stress regressed. Under the pre-registered rules it was not adopted. No default changed, and every report is kept.
2. **Knowing when to stop.** On a seven-document fixture, Top-5 already covers more than half of all chunks, and two runs of the same configuration differ by ±2. Further retrieval tuning there is below the noise floor, so it moves to a realistic public corpus.
3. **Rehearsal beats self-testing.** Following only the README on a fresh clone surfaced eight documentation gaps and one real defect: retrieval required `status = READY`, so a single failed reindex took a document offline. It was fixed the same day, with a test that fails before the fix and passes after it.
4. **Compatibility is only known once tested.** Connecting DeepSeek revealed that it rejects strict `json_schema` output. The output format became a setting, and full backend validation was kept. With a stronger chat model only retrieval-bound misses remained, which points the next effort at retrieval, not at a pricier model: v4-pro was three times slower than flash with identical scores.
5. **Closing scope deliberately.** The local model server (LM Studio) persists part of its model I/O to local logs. Instead of investigating a third-party tool indefinitely, I restricted it to synthetic data and wrote a provider acceptance checklist for any provider that will handle real data.

## Limitations

- No production deployment, public demo, or real users; every number comes from local synthetic data.
- The quality gate is not yet passed, and the corpus is too small for trustworthy retrieval tuning.
- The ACL model is allow-only. Chunks do not record which embedding model produced them, so changing models requires a full rebuild.
- MCP is a minimal read-only adapter, with no third-party SDK conformance testing, agent loop, or write operations.

## Next

See [ROADMAP.md](../ROADMAP.md): a demo video (Phase A); a realistic public corpus with about 50 questions, a new baseline, and a quality/latency/cost comparison of multilingual embeddings and cloud models (Phase B); and an optional real MCP client demo (Phase C).

## Five-minute talk track

1. Start the stack from the README and explain that the provider is configuration: local LM Studio or a cloud OpenAI-compatible API.
2. Upload `sample_faq.md`, wait for `ready`, and ask a covered question. Show `found`, the backend-owned sources, the request ID, timings, and token usage.
3. Ask an uncovered question and show the refusal with no sources.
4. Create an employee: before the grant the answer is refused; after a READ grant it is cited; after revoke it is refused again. Explain that the filter runs in SQL before the model is called.
5. Close with the evaluation: what passed, what did not, and why no retrieval component was added. Do not show LM Studio model-I/O logs in any recording.
