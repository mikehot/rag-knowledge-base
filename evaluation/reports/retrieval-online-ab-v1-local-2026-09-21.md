# Online Retrieval A/B v1（本地受控联调）

日期：2026-09-21  
环境：Docker Compose PostgreSQL 16.15 + pgvector 0.8.6、Spring Boot backend、LM Studio OpenAI-compatible API、Gemma 4 26B、Nomic Embedding  
数据：`keyword-candidates-v1` 8 题、一次性本地 fixture、真实登录用户和文档 ACL  
范围：候选检索与融合排序；不改变默认线上链路

## 结论

本轮线上实验支持继续研究 keyword-weight-2 RRF，但不足以启用 Hybrid Search。融合侧使用了实际数据库中的 ACL 可见 Chunk，并在候选层取得更高的 Recall@1；但是融合候选没有重新注入 `AskService` 生成答案，因此尚未比较生成答案、引用、Structured Output 失败、Token、估算成本和端到端失败率。

默认向量链路保持不变，Top-K 仍为 5。

## 线上向量 API 基线

| 指标 | 结果 |
|---|---:|
| 请求数 | 8 |
| HTTP 状态失败 | 0 |
| API 延迟 P50 / P95 / Max | 12130.93 / 18165.33 / 18165.33 ms |
| 平均 Token 用量 | 1428.25 |
| 行为失败 | 1 |
| ACL 泄漏 | 0 |

行为失败为 `STRESS-007`：数据集期望拒答，但本次向量 API 返回了 `found=true`。这说明拒答行为仍需要作为后续后端 A/B 的独立指标，不能只看候选 Recall。

## ACL-aware 候选对照

keyword 侧先执行与后端 `ChunkJdbcRepository` 同边界的 tenant、knowledge base、READY、删除/停用和 owner/角色/部门/文档 ACL 过滤，再进行归一化 keyword 排序；融合使用向量候选与 keyword 候选的 document-level RRF，keyword 权重为 2。

| 候选方案 | Recall@1 | Recall@3 | Recall@5 | 首个目标平均排名 | 可回答上下文干扰 | 拒答候选 | ACL 泄漏 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 真实向量 API 诊断 | 75.00% | 91.67% | 100.00% | 1.1667 | 60.00% | 2/2 | 0 |
| ACL DB + normalized keyword | 91.67% | 100.00% | 100.00% | 1.0000 | 60.00% | 2/2 | 0 |
| keyword-weight-2 RRF | 91.67% | 100.00% | 100.00% | 1.0000 | 60.00% | 2/2 | 0 |

### 阶段耗时边界

keyword 的 ACL 查询与排序阶段在本机记录为 P50 0.323 ms、P95 169.72 ms；RRF 合并本身为 P50 0.0134 ms、P95 0.015 ms。这些是候选评测阶段耗时，不能与包含 Embedding、模型生成和网络等待的 `/api/ask` 端到端耗时直接比较，也不能作为生产 SLO。

## 权限 fixture 边界

本轮只使用本地 disposable fixture。实际可见 Chunk 数量为：`demo.employee=7`、`demo.outsider=2`、`demo.auditor=7`。评测过程未将问题、答案或 Chunk 正文写入仓库；原始响应和诊断 JSONL 保留在 `/private/tmp`。

## 决策与下一步

1. 不新增 PostgreSQL FTS/`pg_trgm` 扩展，不切换默认 Hybrid Search，不加入 Reranker。
2. 下一道门是后端自有的受控 A/B：在同一个认证请求、同一个 ACL、同一个上下文预算下，让向量候选和 keyword-weight-2 融合候选真正进入 `AskService`，比较答案正确性、引用、拒答、Structured Output 失败、P95、Token、估算成本和失败率。
3. A/B 必须有关闭开关和 fail-closed ACL 检查；只有在质量、延迟、成本和拒答行为同时达到门槛后，才讨论默认启用。

