# Golden Dataset and Offline Evaluation

This directory contains the first versioned evaluation baseline for the enterprise RAG project. `datasets/golden_v1.jsonl` contains exactly 20 sanitized cases based on the repository's sample FAQ and the current retrieval/ACL contracts.

The dataset is a test fixture, not production data. The `acting_user` identities are synthetic and do not imply that these users or the forbidden policy documents are seeded in a running database.

## What is covered

- 16 answerable questions grounded in `sample_faq.md`.
- 2 out-of-scope refusal cases.
- 2 ACL-filtered refusal cases for documents the acting user must not see.
- 1 combined multi-point question for coverage and citation checks.
- Expected answer points, source documents, acting user/department/role, forbidden documents, and refusal behavior for every case.

The deterministic checks are intentionally separate from model-based judging. They validate dataset integrity, response shape, answer-point coverage using declared terms, citation presence/correctness, refusal behavior, and ACL leakage. They do not claim that substring matching is a complete quality or groundedness judge.

## Run the dataset-only validation

From the repository root:

```bash
python3 evaluation/run_eval.py
```

This command makes no model, database, or network calls. It must report `dataset_valid: true`, `case_count: 20`, and the behavior counts.

## Score API responses

The repository includes `run_api_eval.py`, a standard-library-only collector for the real `/api/auth/login` and `/api/ask` endpoints. It accepts an external credentials map keyed by the synthetic `acting_user.id`; do not commit that file.

Example credentials file shape:

```json
{
  "demo.employee": {"username":"managed-employee","password":"provided-outside-repo"},
  "demo.admin": {"username":"managed-admin","password":"provided-outside-repo"},
  "demo.outsider": {"username":"managed-outsider","password":"provided-outside-repo"},
  "demo.auditor": {"username":"managed-auditor","password":"provided-outside-repo","roleCodes":["AUDITOR"]}
}
```

The map must be backed by real users whose departments, roles, knowledge-base memberships, and document ACLs match the dataset fixture. The admin credential must belong to a `SYSTEM_ADMIN` or document manager. The `POST /api/documents/{id}/acl` and `DELETE /api/documents/{id}/acl/{aclId}` endpoints can provision document grants using a document manager token. The collector fails before making API calls when an actor is missing.

For a disposable local tenant, `prepare_api_fixture.py` can create or reuse the actor users, upload `sample_faq.md` plus two synthetic policy documents, wait for indexing, and grant only the shared FAQ to non-admin actors:

```bash
python3 evaluation/prepare_api_fixture.py \
  --admin-credentials /private/tmp/rag-eval-admin.json \
  --credentials /private/tmp/rag-eval-credentials.json \
  --manifest /private/tmp/rag-golden-v1-fixture.json
```

This script is intentionally not a database reset or cleanup tool. Use a disposable tenant/database and review the manifest before running the evaluator. It does not run automatically as part of CI.

Capture responses:

```bash
python3 evaluation/run_api_eval.py \
  --base-url http://localhost:8080 \
  --credentials /private/tmp/rag-eval-credentials.json \
  --output /private/tmp/rag-golden-v1-responses.jsonl
```

The collector records API status, request ID, API and wall-clock latency, token usage, stage timings, the backend-validated `grounded` flag, failure reason, and sources. It does not print answers or credentials to stdout.

The runner accepts this flattened JSONL adapter output so that API collection and scoring remain separate. Each response row must contain:

```json
{"id":"RAG-001","answer":"...","found":true,"grounded":true,"sources":[{"filename":"sample_faq.md","locator":"产品规格"}],"statusCode":200}
```

Run:

```bash
python3 evaluation/run_eval.py \
  --responses /private/tmp/rag-golden-v1-responses.jsonl \
  --output /tmp/rag-golden-v1-report.json
```

The report retains per-case failures instead of hiding them behind an average. It includes answerable pass rate, citation coverage/correctness, expected answer-point coverage, refusal correctness, ACL leakage count, and schema failures.

The first two real local API runs are recorded in [reports/golden-v1-local-2026-09-18.md](reports/golden-v1-local-2026-09-18.md). Each run passed 20/20 cases, with 100% answerable pass rate, citation coverage/correctness, refusal correctness, and zero ACL leakage. Latency and token figures are local-provider evidence only, not production SLOs.

The separate retrieval stress set is `datasets/retrieval_stress_v1.jsonl`. It has 8 cases for cross-document answers, similar terminology, a firmware version document, a multi-chunk operations document, out-of-scope refusal, and ACL-filtered refusal. The historical 8/8 run is recorded in [reports/retrieval-stress-v1-local-2026-09-18.md](reports/retrieval-stress-v1-local-2026-09-18.md); the current V10 diagnostics run, including the corrected LM Studio model IDs and the single cross-document generation miss, is recorded in [reports/retrieval-stress-v1-local-2026-09-20.md](reports/retrieval-stress-v1-local-2026-09-20.md).

To validate or score it, pass its expected case count explicitly:

```bash
python3 evaluation/run_eval.py \
  --dataset evaluation/datasets/retrieval_stress_v1.jsonl \
  --expected-case-count 8
```

Use the same external credentials and fixture flow for a live stress capture:

```bash
python3 evaluation/run_api_eval.py \
  --dataset evaluation/datasets/retrieval_stress_v1.jsonl \
  --credentials /private/tmp/rag-stress-credentials.json \
  --output /private/tmp/retrieval-stress-v1-responses.jsonl
```

After a live ask capture, an external `SYSTEM_ADMIN` or `AUDITOR` credential can collect the protected V10 retrieval snapshots without sending new questions:

```bash
python3 evaluation/collect_retrieval_diagnostics.py \
  --responses /private/tmp/retrieval-stress-v1-responses.jsonl \
  --admin-credentials /private/tmp/rag-stress-credentials.json \
  --admin-actor-id demo.admin \
  --output /private/tmp/retrieval-stress-v1-diagnostics.jsonl
```

Score document-level Recall@1/3/5, first expected-source rank, Top-1 similarity, Top-1 margin, candidate count, and forbidden-document leakage:

```bash
python3 evaluation/run_retrieval_eval.py \
  --dataset evaluation/datasets/retrieval_stress_v1.jsonl \
  --expected-case-count 8 \
  --diagnostics /private/tmp/retrieval-stress-v1-diagnostics.jsonl \
  --output /private/tmp/retrieval-stress-v1-retrieval-report.json
```

The scorer is deterministic and only treats the protected diagnostic response as retrieval evidence. It does not infer relevance from the answer text or citation presence. A live retrieval report is not checked in until the V10 PostgreSQL/HTTP path is verified.

## Deliberate boundary

Raw live-model response captures remain outside the repository because they contain request IDs and environment-specific output. The checked-in aggregate reports prove the authenticated local Golden and stress runs in addition to the versioned datasets and deterministic runner. The backend now persists protected retrieval-level snapshots, and the collector/scorer for `SYSTEM_ADMIN`/`AUDITOR` diagnostics is implemented; a live V10 retrieval report remains pending Docker-backed verification. An independently identified LLM-as-judge or rubric-based groundedness score remains optional. Retrieval changes such as BM25, hybrid search, or a reranker should be justified by the resulting failure categories.
