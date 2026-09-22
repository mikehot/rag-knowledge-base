# Vector Diversity Answer-Quality A/B（本地）

> 日期：2026-09-22
> 数据集：`answer-quality-v1`，12 题
> 环境：PostgreSQL 16.15 + pgvector、LM Studio Gemma 4 26B + Nomic Embedding、同一组 disposable ACL 凭据
> 范围：只比较默认 `VECTOR` 与受控 `VECTOR_DIVERSITY`，不改变默认配置

## 结论

`VECTOR_DIVERSITY` 没有通过默认切换门槛，继续保持实验开关关闭。它在相同服务、同一数据集和相同 ACL 条件下没有提高质量门通过率，引用和答案要点覆盖反而更低，延迟略高，并出现额外非目标来源。

这不是“代码不可运行”的结论：实验首次运行暴露了 `ask_log` 的数据库 `CHECK` 约束未包含新模式，导致 500；新增 Flyway V12 后重启复测，实验组 12/12 HTTP 200、API code=0、schema failure=0、ACL leakage=0。

## 同条件端到端结果

| 指标 | `VECTOR` | `VECTOR_DIVERSITY` |
|---|---:|---:|
| HTTP/API 成功 | 12/12 | 12/12 |
| 质量门通过 | 8/12（66.67%） | 8/12（66.67%） |
| 可回答题通过率 | 50.00% | 50.00% |
| 引用覆盖率 | 75.00% | 62.50% |
| 引用正确率 | 75.00% | 50.00% |
| 预期答案要点覆盖 | 64.58% | 50.00% |
| 拒答正确率 | 100.00% | 100.00% |
| Structured Output 失败 | 0 | 0 |
| ACL leakage | 0 | 0 |
| API 中位延迟 | 11248.5 ms | 11720.5 ms |

`VECTOR_DIVERSITY` 的中位 API 延迟约高 4.2%。在 `QUALITY-001` 中还引入了 `release-notes-v2.md` 这一非目标来源；`QUALITY-002` 的向量边界缺口仍未被端到端解决。两组结果受本地模型非确定性影响，因此不能只凭一次 8/12 断言稳定质量提升；但当前证据已经足够否决默认切换。

## 验证边界

- 受保护诊断只读取 rank、filename、locator、similarity、阈值和失败分类，不把问题、Prompt 或 Chunk 正文写入报告。
- `VECTOR_DIVERSITY` 仅通过 `RAG_HYBRID_EXPERIMENT_ENABLED=true` 和 `X-RAG-Retrieval-Mode: vector-diversity` 开启；普通请求仍使用 `VECTOR`。
- 该实验不等价于生产级 Reranker，也不证明 BM25/Hybrid Search 的收益。

## 下一步

1. 保持默认 `VECTOR`、Top-K=5 和实验开关关闭。
2. 把 `QUALITY-002` 作为受控检索边界案例，把 `QUALITY-006` 作为模型结构化输出/答案判定案例，继续分离召回问题和生成问题。
3. 优先完成 Docker-backed/CI 的 V12 迁移与集成回归，再决定是否进入只读 MCP adapter；不因局部 Q002 找回就直接加入 Reranker 或 Hybrid Search。
