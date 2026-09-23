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
  "demo.admin": {"username":"managed-admin","password":"provided-outside-repo","roleCodes":["SYSTEM_ADMIN"]},
  "demo.outsider": {"username":"managed-outsider","password":"provided-outside-repo"},
  "demo.auditor": {"username":"managed-auditor","password":"provided-outside-repo","roleCodes":["AUDITOR"]}
}
```

The map must be backed by real users whose departments, roles, knowledge-base memberships, and document ACLs match the dataset fixture. The admin credential must belong to a `SYSTEM_ADMIN` or document manager. The `POST /api/documents/{id}/acl` and `DELETE /api/documents/{id}/acl/{aclId}` endpoints can provision document grants using a document manager token. The collector fails before making API calls when an actor is missing.

For a disposable local tenant, `prepare_api_fixture.py` can create or reuse the actor users, idempotently reconcile the requested `roleCodes` (including the Golden defaults `demo.admin=SYSTEM_ADMIN` and `demo.auditor=AUDITOR`), upload `sample_faq.md` plus synthetic policy/stress documents, wait for indexing, and grant only the shared FAQ to non-admin actors:

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

## Run the answer-quality extension set

`datasets/answer_quality_v1.jsonl` adds 12 focused cases to the frozen 20-case baseline: 8 answerable questions covering battery, installation, warranty, returns, password recovery, and temporary passwords, plus 2 out-of-scope refusals and 2 ACL-filtered refusals. Together with `golden_v1`, the repository now has 32 versioned cases without rewriting the historical baseline.

Run the dataset contract check:

```bash
python3 evaluation/run_eval.py \
  --dataset evaluation/datasets/answer_quality_v1.jsonl \
  --expected-case-count 12
```

For a live capture, use the same external fixture/credentials flow as Golden, then score the responses:

```bash
python3 evaluation/run_api_eval.py \
  --dataset evaluation/datasets/answer_quality_v1.jsonl \
  --credentials /private/tmp/rag-quality-credentials.json \
  --output /private/tmp/rag-quality-v1-responses.jsonl

python3 evaluation/run_eval.py \
  --dataset evaluation/datasets/answer_quality_v1.jsonl \
  --expected-case-count 12 \
  --responses /private/tmp/rag-quality-v1-responses.jsonl \
  --output /tmp/rag-quality-v1-report.json
```

The optional `quality_rules` are a deterministic rubric separate from the HTTP/Structured Output contract. They can require full expected-answer-point coverage, grounded output, no unexpected cited documents, and a fail-closed refusal. The report exposes `quality_gate_pass_rate`, `quality_gate_failure_count`, per-case `quality_errors`, and `unexpected_sources`. This is evidence-based answer checking, not an LLM judge and not a claim of factual correctness beyond the sanitized fixture.

The first real backend-owned VECTOR/KEYWORD_RRF comparison for this set is recorded in [reports/answer-quality-v1-backend-ab-local-2026-09-21.md](reports/answer-quality-v1-backend-ab-local-2026-09-21.md). It is a small local-provider sample: use it to separate retrieval misses from generation/Structured Output failures, not as a production SLO.

For a repeatability check on targeted cases, use the separate stability collector. It keeps duplicate case IDs plus a `repeat` field and must not be passed to `run_eval.py`, whose contract requires one response per case:

```bash
python3 evaluation/run_stability_eval.py \
  --dataset evaluation/datasets/answer_quality_v1.jsonl \
  --credentials /private/tmp/rag-online-ab-credentials.json \
  --case-id QUALITY-002 \
  --case-id QUALITY-006 \
  --repeats 3 \
  --retrieval-mode vector \
  --output /private/tmp/rag-quality-targeted-vector-stability.jsonl
```

Run the same command with `--retrieval-mode keyword-rrf` and a separate output path. Summarize `found`, `grounded`, `failureReason`, source filenames, latency, and token usage by case/mode/repeat; keep the raw JSONL and credentials outside Git. Use an employee-scoped credential map matching the fixture ACL, not a full-access administrator, for a formal quality conclusion.

Summarize one or more capture files without printing answer text or request IDs:

```bash
python3 evaluation/summarize_stability_eval.py \
  --input /private/tmp/rag-quality-targeted-vector-stability.jsonl \
  --input /private/tmp/rag-quality-targeted-keyword-stability.jsonl \
  --output /tmp/rag-quality-targeted-stability-summary.json
```

The summary reports per-mode and per-case outcome variants, failure reasons, source filenames, P50/P95/max latency, and token usage. `outcome_stable=true` only means the captured contract outcome and cited filenames did not vary; it does not prove answer correctness.

The collector was added as an evidence-gathering tool, not as a new quality score. A valid formal run requires the same actor identities and permissions used by the dataset, the backend-owned experiment flag enabled only for the keyword side, and separate output files for each retrieval mode. If the credential map is unavailable, record the gate as pending rather than recreating users or inferring passwords.

## Run the read-only MCP smoke check

`run_mcp_smoke.py` is a standard-library-only HTTP client for the bounded
`POST /mcp` adapter. It logs in with one disposable administrator credential,
then checks `server/discover`, `tools/list`, a successful `list_documents`
call, identity-argument rejection, protocol/header mismatch handling, and
unauthenticated access. It validates the exact allowlist
`search_knowledge`, `list_documents`, and `get_document_status`, plus the
private/no-cache catalog hints. The credential file and aggregate JSON output
must remain outside Git:

```bash
python3 evaluation/run_mcp_smoke.py \
  --base-url http://localhost:8081 \
  --credentials /private/tmp/rag-eval-admin.json \
  --output /private/tmp/mcp-readonly-smoke-local.json
```

This is a repeatable local HTTP smoke check for the repository's adapter
boundary, not an official MCP SDK certification or a full transport/auth
conformance suite. The adapter targets the stateless MCP `2026-07-28` revision;
see the [official release notes](https://blog.modelcontextprotocol.io/posts/2026-07-28/).
The checked-in result is [reports/mcp-readonly-smoke-local-2026-09-22.md](reports/mcp-readonly-smoke-local-2026-09-22.md).

## Run the structured-output provider probe

Before changing `AI_MODEL_ID`, run the provider probe against synthetic questions. It sends the same strict JSON Schema used by the Java provider, but it does not send project documents and never writes model content to the report. The report records only contract validity, finish reason, token usage, response size, and latency:

```bash
python3 evaluation/probe_structured_output.py \
  --model google/gemma-4-26b-a4b-qat \
  --model qwen/qwen3.8-27b \
  --repeats 1 \
  --output /tmp/structured-output-probe-v1-local.json
```

Treat `contractValid=true` for every probe as the minimum capability signal. A `finishReason=length`, invalid content JSON, or a missing contract field is a provider/model failure and must remain fail-closed. One local run is not enough to change the default model; repeat the probe and then rerun the same answer-quality and stress gates with the candidate model. Do not commit the JSON output when it contains environment-specific results.

The latest local comparison is recorded in [reports/structured-output-probe-v1-local-2026-09-22.md](reports/structured-output-probe-v1-local-2026-09-22.md). It does not change the default model or retrieval path.

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

The scorer is deterministic and only treats the protected diagnostic response as retrieval evidence. It does not infer relevance from the answer text or citation presence. The PostgreSQL/HTTP path is now verified locally; a new aggregate retrieval report is still withheld until the Docker-backed Java integration gate is executable in CI.

## Run the offline keyword candidate benchmark

`datasets/keyword_candidates_v1.jsonl` is a retrieval-only projection of the stress fixture. It intentionally contains no `expected_answer_points` or `refusal_match_terms`; the runner rejects those fields and derives keyword candidates only from the question and the sanitized fixture corpus. ACL visibility is applied before scoring.

Run:

```bash
python3 evaluation/run_keyword_candidate_benchmark.py \
  --iterations 20 \
  --output /tmp/keyword-candidates-v1-local.json
```

The benchmark compares raw CJK character n-grams/ASCII terms with generic-question-word normalization. It reports document Recall@1/3/5, expected-source rank, ACL leakage, refusal candidates, candidate latency, and Top-K context interference. The current local result is recorded in [reports/keyword-candidates-v1-local-2026-09-21.md](reports/keyword-candidates-v1-local-2026-09-21.md). It is a candidate-stage signal only; it does not modify PostgreSQL, the online retrieval path, Top-K=5, or the Reranker decision.

## Inspect vector candidates beyond production Top-K

When a protected Top-K snapshot shows a suspected miss, use the evaluation-only vector diagnostic to inspect a larger ACL-filtered candidate pool. It reuses the employee fixture's tenant, knowledge-base, department, role, and document ACL predicates, calls only the local embedding endpoint, and returns metadata/similarity—not chunk content:

```bash
python3 evaluation/run_vector_candidate_diagnostic.py \
  --manifest /private/tmp/rag-quality-fixture-support-20260922.json \
  --case-id QUALITY-002 \
  --candidate-k 50 \
  --output /private/tmp/vector-candidate-quality-002-20260922.json
```

This diagnostic answers whether the expected document is ranked just beyond the production boundary. It must not be used to change `AskService`, Top-K, ACL predicates, or the default retrieval mode. The current Q002 result is recorded in [reports/vector-candidate-diagnostic-quality-002-2026-09-22.md](reports/vector-candidate-diagnostic-quality-002-2026-09-22.md).

To check whether the actual ACL-visible ranked chunks contain the dataset's expected answer-point terms, use `run_chunk_evidence_diagnostic.py` with the same disposable database/manifest and one or more metadata-only vector reports:

```bash
COMPOSE_FILE=/path/to/disposable-compose.yml python3 evaluation/run_chunk_evidence_diagnostic.py \
  --dataset evaluation/datasets/golden_v1.jsonl \
  --dataset evaluation/datasets/answer_quality_v1.jsonl \
  --case-id RAG-003 --case-id RAG-010 --case-id QUALITY-002 \
  --diagnostics /private/tmp/golden-vector-candidates.json \
  --diagnostics /private/tmp/quality-vector-candidates.json \
  --manifest /private/tmp/rag-eval-manifest.json \
  --db-name rag_eval --context-budgets 5 8 10 \
  --output /private/tmp/chunk-evidence-diagnostic.json
```

Chunk text is fetched into process memory only; output is restricted to chunk IDs, ranks, locators, expected-point IDs, and coverage metadata. Only lexical matches inside `expected_source_documents` count as evidence; off-source matches are reported separately. This is not a semantic relevance or answer-quality score. Run `python3 -m unittest discover -s evaluation -p 'test_*.py'` for its local unit checks. The disposable 2026-09-23 chunk-evidence and Top-K comparison is recorded in [reports/chunk-evidence-topk-ab-local-2026-09-23.md](reports/chunk-evidence-topk-ab-local-2026-09-23.md); its mixed repeat results do not justify changing the Top-K=5 default.

For an answer-label-independent adjacent-chunk candidate experiment, add `--compare-adjacent-window`. The selector uses only the ACL-filtered vector candidate rank, filename, and `chunk#N` locator; answer-point labels are applied only after selection by the evidence scorer. It holds the context chunk budget constant and makes at most one adjacent-chunk substitution per case. This remains candidate-level evidence, not permission to change runtime retrieval. The 12-case local diagnostic and its limitations are recorded in [reports/adjacent-chunk-selection-answer-quality-local-2026-09-23.md](reports/adjacent-chunk-selection-answer-quality-local-2026-09-23.md).

To compare a bounded document-diversity selector without calling a model:

```bash
python3 evaluation/run_vector_rerank_benchmark.py \
  --dataset evaluation/datasets/retrieval_stress_v1.jsonl \
  --diagnostic /private/tmp/vector-candidate-retrieval-stress-v1-20260922.json \
  --context-k 5 \
  --output /private/tmp/vector-rerank-retrieval-stress-v1-20260922.json
```

The benchmark compares current Vector order, diversity-first selection, and a one-chunk-per-document cap. It is candidate-level evidence only: it does not evaluate generated answer quality and must not enable a runtime reranker by itself.

## Run the matched-case fusion benchmark

After a protected diagnostics capture and the keyword benchmark are available, compare document-level vector candidates with normalized keyword candidates and RRF variants:

```bash
python3 evaluation/run_retrieval_fusion_benchmark.py \
  --diagnostics /private/tmp/rag-stress-native-4000-diagnostics-20260921.jsonl \
  --keyword-report /tmp/keyword-candidates-v1-local.json \
  --output /tmp/retrieval-fusion-v1-local.json
```

This is an offline matched-case simulation. It applies the same fixture ACL expectations and evaluates Recall@1/3/5, context interference, refusal candidates, and ACL leakage. The result is recorded in [reports/retrieval-fusion-v1-local-2026-09-21.md](reports/retrieval-fusion-v1-local-2026-09-21.md). A ranking signal is not permission to enable the runtime path: the next gate is an authenticated online A/B comparison with the same request, context budget, latency, token, and failure metrics.

## Run the authenticated online retrieval A/B

The online runner reuses a captured `/api/ask` vector response and protected retrieval diagnostics, then queries only ACL-visible READY chunks from the local database to evaluate normalized keyword ranking and keyword-weight-2 RRF on the same eight cases:

```bash
python3 evaluation/run_online_retrieval_ab.py \
  --responses /private/tmp/rag-online-ab-vector-20260921.jsonl \
  --diagnostics /private/tmp/rag-online-ab-vector-diagnostics-20260921.jsonl \
  --manifest /private/tmp/rag-online-ab-fixture-20260921.json \
  --output /tmp/retrieval-online-ab-v1-local.json
```

This is an evaluation-only candidate comparison. The keyword/fused candidates are not injected back into `AskService`, so the runner must not be used to claim end-to-end Hybrid Search quality, token savings, cost savings, or production latency. The current local result is recorded in [reports/retrieval-online-ab-v1-local-2026-09-21.md](reports/retrieval-online-ab-v1-local-2026-09-21.md). Raw responses, diagnostics, fixture manifests, and credentials remain outside the repository.

## Run the backend-owned paired A/B

The backend-owned experiment is disabled by default. For a disposable local environment, restart the backend with:

```bash
RAG_HYBRID_EXPERIMENT_ENABLED=true docker compose up -d --build backend
```

Capture the same dataset twice with the same external credentials, changing only the retrieval mode:

```bash
python3 evaluation/run_api_eval.py \
  --dataset evaluation/datasets/keyword_candidates_v1.jsonl \
  --credentials /private/tmp/rag-online-ab-credentials.json \
  --retrieval-mode vector \
  --output /private/tmp/rag-online-ab-vector-api.jsonl

python3 evaluation/run_api_eval.py \
  --dataset evaluation/datasets/keyword_candidates_v1.jsonl \
  --credentials /private/tmp/rag-online-ab-credentials.json \
  --retrieval-mode keyword-rrf \
  --output /private/tmp/rag-online-ab-keyword-rrf-api.jsonl
```

For the bounded vector-diversity experiment, use the same command with
`--retrieval-mode vector-diversity` and a separate output path. The mode uses
the backend's ACL-filtered vector candidate pool and is still disabled by
default; it must be scored as a separate candidate strategy, not merged into
the keyword-RRF report.

For the experimental adjacent-chunk candidate from the offline answer-quality
diagnostic, enable its dedicated gate and capture a separate response file:

```bash
RAG_CONTEXT_SELECTION_EXPERIMENT_ENABLED=true docker compose up -d --build backend
python3 evaluation/run_api_eval.py \
  --dataset evaluation/datasets/answer_quality_v1.jsonl \
  --credentials /private/tmp/rag-online-ab-credentials.json \
  --retrieval-mode vector-adjacent \
  --output /private/tmp/rag-online-ab-vector-adjacent-api.jsonl
```

Pair this with a `vector` capture against the same disposable fixture, repeat
the full 12 cases, score each capture with `run_eval.py`, collect protected
retrieval diagnostics, and turn the context-selection flag off afterward. The
mode uses only ACL-filtered vector hits and preserves the configured Top-K
context size; it is not enabled by default and must pass answer, citation,
refusal, ACL, structured-output, token, and latency gates before any adoption.

For the `answer-quality-v1` end-to-end gate, run both modes against the same
12-case dataset and then use `run_eval.py` for each response file. Keep the
response and protected-diagnostic captures outside the repository. The local
2026-09-22 result is recorded in
[reports/vector-diversity-answer-quality-ab-local-2026-09-22.md](reports/vector-diversity-answer-quality-ab-local-2026-09-22.md);
the experiment remains gated because it did not improve the quality gate and
reduced citation/answer-point coverage.

Collect protected diagnostics separately for both response files, then run the paired scorer:

```bash
python3 evaluation/run_backend_retrieval_ab.py \
  --vector-responses /private/tmp/rag-online-ab-vector-api.jsonl \
  --vector-diagnostics /private/tmp/rag-online-ab-vector-diagnostics.jsonl \
  --keyword-responses /private/tmp/rag-online-ab-keyword-rrf-api.jsonl \
  --keyword-diagnostics /private/tmp/rag-online-ab-keyword-rrf-diagnostics.jsonl \
  --output /tmp/backend-retrieval-ab-v1-local.json
```

The scorer compares answer/citation/refusal contract behavior, Structured Output failures, HTTP/API latency, stage timings, token usage, ACL leakage, retrieval ranking, and persisted retrieval mode without printing answers or chunk content. Estimated cost is not concluded when the API capture does not expose it. The current local result is recorded in [reports/retrieval-backend-ab-v1-local-2026-09-21.md](reports/retrieval-backend-ab-v1-local-2026-09-21.md). Turn the flag off after the experiment.

## Deliberate boundary

Raw live-model response captures remain outside the repository because they contain request IDs and environment-specific output. The checked-in aggregate reports prove the authenticated local Golden and stress runs in addition to the versioned datasets and deterministic runner. The backend now persists protected retrieval-level snapshots, and the collector/scorer for `SYSTEM_ADMIN`/`AUDITOR` diagnostics is implemented; the 2026-09-20 Structured Output/Agent HTTP report records the current contract evidence and remaining retrieval/provider failures. The answer-quality extension adds a deterministic rubric; an LLM-as-judge remains optional and must be reported separately. Retrieval changes such as BM25, hybrid search, or a reranker should be justified by the resulting failure categories.
