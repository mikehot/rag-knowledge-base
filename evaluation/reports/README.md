# Evaluation Reports

Dated evidence from local, disposable environments on synthetic data. Every report records its configuration and limitations. The current conclusions are summarized in [PORTFOLIO.md](../../PORTFOLIO.md#评测结果与已知局限); older reports are kept for traceability and may be superseded.

## Start here

| Report | What it shows |
|---|---|
| [cloud-provider-deepseek-local-2026-09-26](cloud-provider-deepseek-local-2026-09-26.md) | Local Gemma vs DeepSeek flash / v4-pro on the same gate: quality, latency, tokens, cost; the `json_schema` portability finding |
| [chunking-ab-local-2026-09-24](chunking-ab-local-2026-09-24.md) | Chunk size A/B (700/400/300/200) under the frozen gate; why the default did not change |
| [heading-chunking-offline-replay-local-2026-09-24](heading-chunking-offline-replay-local-2026-09-24.md) | Offline replay rejecting heading-aware chunking before any code change |
| [clean-clone-rehearsal-local-2026-09-24](clean-clone-rehearsal-local-2026-09-24.md) | Fresh-clone rehearsal: README gaps and a reindex availability defect, both fixed |

## Delivery and operations

- [demo-v0.1-disposable-local-2026-09-22](demo-v0.1-disposable-local-2026-09-22.md), [continuous-disposable-demo-local-2026-09-24](continuous-disposable-demo-local-2026-09-24.md): end-to-end API demos
- [backup-restore-rehearsal-local-2026-09-23](backup-restore-rehearsal-local-2026-09-23.md): database dump and upload archive restored into a fresh environment
- [mcp-readonly-smoke-local-2026-09-22](mcp-readonly-smoke-local-2026-09-22.md): read-only MCP boundary checks
- [flutter-operations-acl-index-task-local-2026-09-23](flutter-operations-acl-index-task-local-2026-09-23.md), [flutter-operations-acl-index-task-device-local-2026-09-24](flutter-operations-acl-index-task-device-local-2026-09-24.md), [document-acl-principal-directory-local-2026-09-24](document-acl-principal-directory-local-2026-09-24.md): Flutter ACL and index-task acceptance on API and Android device

## Retrieval and answer-quality experiments (historical)

- Baselines: [golden-v1-local-2026-09-18](golden-v1-local-2026-09-18.md), [golden-v1-structured-output-local-2026-09-20](golden-v1-structured-output-local-2026-09-20.md), [retrieval-stress-v1-local-2026-09-18](retrieval-stress-v1-local-2026-09-18.md), [retrieval-stress-v1-local-2026-09-20](retrieval-stress-v1-local-2026-09-20.md)
- Keyword / fusion / hybrid: [keyword-candidates-v1-local-2026-09-21](keyword-candidates-v1-local-2026-09-21.md), [retrieval-fusion-v1-local-2026-09-21](retrieval-fusion-v1-local-2026-09-21.md), [retrieval-online-ab-v1-local-2026-09-21](retrieval-online-ab-v1-local-2026-09-21.md), [retrieval-backend-ab-v1-local-2026-09-21](retrieval-backend-ab-v1-local-2026-09-21.md), [answer-quality-v1-backend-ab-local-2026-09-21](answer-quality-v1-backend-ab-local-2026-09-21.md)
- Candidate ranking and context selection: [vector-candidate-diagnostic-quality-002-2026-09-22](vector-candidate-diagnostic-quality-002-2026-09-22.md), [vector-rerank-benchmark-v1-local-2026-09-22](vector-rerank-benchmark-v1-local-2026-09-22.md), [vector-diversity-answer-quality-ab-local-2026-09-22](vector-diversity-answer-quality-ab-local-2026-09-22.md), [chunk-evidence-topk-ab-local-2026-09-23](chunk-evidence-topk-ab-local-2026-09-23.md), [adjacent-chunk-selection-answer-quality-local-2026-09-23](adjacent-chunk-selection-answer-quality-local-2026-09-23.md)
- Failure analysis and rubric: [quality-001-rubric-diagnostic-local-2026-09-23](quality-001-rubric-diagnostic-local-2026-09-23.md), [quality-002-006-failure-classification-local-2026-09-23](quality-002-006-failure-classification-local-2026-09-23.md)

## Model and provider behavior

- Structured output: [structured-output-probe-v1-local-2026-09-22](structured-output-probe-v1-local-2026-09-22.md), [structured-output-gemma-recheck-local-2026-09-23](structured-output-gemma-recheck-local-2026-09-23.md), [gemma-thinking-off-end-to-end-local-2026-09-23](gemma-thinking-off-end-to-end-local-2026-09-23.md), [structured-output-termination-diagnostics-local-2026-09-24](structured-output-termination-diagnostics-local-2026-09-24.md)
- Provider data boundary: [provider-log-boundary-review-local-2026-09-24](provider-log-boundary-review-local-2026-09-24.md) (closed by scope decision)
