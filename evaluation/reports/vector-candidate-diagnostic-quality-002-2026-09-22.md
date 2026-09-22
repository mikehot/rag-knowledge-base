# Vector Candidate Diagnostic — QUALITY-002 (2026-09-22)

## Scope

This evaluation-only diagnostic inspected a larger vector candidate pool for `QUALITY-002` using the disposable `demo.employee` fixture. It used the same tenant, knowledge-base, lifecycle, department, role, and document ACL predicates as the backend retrieval query. The local embedding endpoint generated the question vector; PostgreSQL returned only candidate metadata and similarity. No production query, Top-K, or ACL code changed.

Configuration:

- Production context boundary: Top-K=5
- Diagnostic candidate limit: 50
- Visible candidate count: 7
- Actor: `demo.employee` with `SUPPORT` / `EMPLOYEE` fixture permissions
- ACL leakage: 0

## Result

| Rank | Filename | Locator | Similarity |
|---:|---|---|---:|
| 1 | `device-installation.md` | `chunk#1` | 0.634157 |
| 2 | `long-ops-manual.md` | `chunk#2` | 0.613863 |
| 3 | `long-ops-manual.md` | `chunk#1` | 0.594057 |
| 4 | `support-sla.md` | `chunk#1` | 0.550605 |
| 5 | `release-notes-v2.md` | `chunk#1` | 0.536526 |
| 6 | `sample_faq.md` | `chunk#2` | 0.533425 |
| 7 | `sample_faq.md` | `chunk#1` | 0.526589 |

The expected `sample_faq.md` is present at rank 6. The rank-5 to rank-6 similarity margin is approximately `0.0031`, so the Q002 miss is a narrow ranking-boundary problem rather than an ACL miss or an empty candidate pool. All visible candidates are above the current 0.35 similarity threshold; the threshold is not the cause of this failure.

Local stage timings were approximately 785 ms for embedding and 142 ms for the PostgreSQL query. These are diagnostic measurements only and are not production SLOs.

## Decision

1. Keep production VECTOR retrieval and Top-K=5 unchanged.
2. Do not globally increase Top-K to 6 or 8: earlier full-set comparisons did not show a stable overall quality improvement.
3. The next experiment is a bounded offline candidate-expansion/rerank comparison using the same ACL-aware candidate pool, measuring Q002 recovery, answerable-context interference, refusal candidates, latency, and token impact across the existing Golden/Stress gates.
4. Do not enable Hybrid Search or add a runtime Reranker until that experiment shows net quality improvement without ACL or refusal regressions.

## Evidence boundary

- This is one sanitized local employee-fixture question, not a global retrieval-quality claim.
- The diagnostic proves rank placement for Q002; it does not prove that adding more context will improve the generated answer.
- Chunk content was not written to the output report.
