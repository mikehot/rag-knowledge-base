# Backend-owned Retrieval A/B v1（本地端到端联调）

日期：2026-09-21  
环境：本地 Spring Boot、PostgreSQL 16.15 + pgvector 0.8.6、LM Studio Gemma 4 26B、Nomic Embedding  
数据：`keyword-candidates-v1` 8 题；同一外部 credentials、同一 ACL fixture、仅切换 `X-RAG-Retrieval-Mode`  
开关：`RAG_HYBRID_EXPERIMENT_ENABLED=true`；实验结束后应恢复 `false`

## 结论

后端 A/B 已真正进入同一个 `AskService` 生成、Structured Output 校验、引用验证、失败分类、问答日志和受保护诊断链路。keyword-RRF 在本轮小样本上提高了 Recall@1，未产生 ACL 泄漏或契约行为失败；但它仍是 bounded in-memory evaluation scorer，不是生产级 BM25/全文索引。

默认 VECTOR 路径保持不变，Hybrid Search 仍不直接启用。

## 端到端 API 对照

| 指标 | VECTOR | KEYWORD_RRF |
|---|---:|---:|
| 请求数 | 8 | 8 |
| HTTP/API 状态失败 | 0 | 0 |
| 行为契约失败 | 0 | 0 |
| 引用契约失败 | 0 | 0 |
| API 延迟 P50 / P95 / Max | 11586 / 16103 / 16103 ms | 12437 / 13815 / 13815 ms |
| 平均 Token | 1423.375 | 1429.25 |
| 失败分类 | INSUFFICIENT_CONTEXT=1；STRUCTURED_OUTPUT_INVALID=1 | INSUFFICIENT_CONTEXT=2 |

这里的“行为契约失败”只判断 ANSWER 是否有 `found=true`、`grounded=true`、来源，以及拒答是否 `found=false` 且无来源；不代表答案事实正确率。VECTOR 的一次 `STRUCTURED_OUTPUT_INVALID` 出现在 ACL 拒答题，但最终对外仍是安全拒答；keyword-RRF 对应请求以 `INSUFFICIENT_CONTEXT` 结束。

## 检索与权限对照

| 指标 | VECTOR | KEYWORD_RRF | 变化 |
|---|---:|---:|---:|
| Recall@1 | 75.00% | 91.67% | +16.67pp |
| Recall@3 | 91.67% | 100.00% | +8.33pp |
| Recall@5 | 100.00% | 100.00% | 0pp |
| 首个目标平均排名 | 1.1667 | 1.0000 | 改善 |
| 可回答上下文干扰率 | 60.00% | 60.00% | 不变 |
| 拒答候选数 | 2/2 | 2/2 | 不变 |
| ACL 泄漏 | 0 | 0 | 不变 |

两个模式的受保护诊断都记录了正确的 `retrievalMode`：VECTOR 或 KEYWORD_RRF。keyword 侧先使用服务端 tenant、知识库、生命周期、owner、部门、角色和文档 ACL 过滤，再进行候选融合；请求头不能覆盖用户身份边界。

## 阶段耗时

| 阶段 P95 | VECTOR | KEYWORD_RRF |
|---|---:|---:|
| Embedding | 2167 ms | 922 ms |
| Retrieval | 10 ms | 24 ms |
| Generation | 13921 ms | 12881 ms |

本轮 keyword-RRF 的 retrieval P95 只增加 14 ms，但这是 8 题、极小本地 fixture 和 bounded in-memory scorer 的结果；不能外推到企业文档规模，也不能据此宣称生产 P95 改善。API/模型抖动明显大于本地排序差异。

## 决策与下一步

1. 保持 `RAG_HYBRID_EXPERIMENT_ENABLED=false`，不新增 `pg_trgm`/simple FTS，不切换默认 Hybrid Search，不加入 Reranker。
2. 这组结果足以保留 keyword-RRF 作为候选方向，但还不能证明事实正确率提升；下一步应扩充包含真实答案核对的 Golden/压力评测，确认 Recall 提升没有带来上下文干扰或答案质量下降。
3. 若继续产品化，应把当前 bounded in-memory scorer 替换为有索引的 PostgreSQL/搜索引擎实现，并重新验证 ACL、迁移、延迟、成本和故障降级；在此之前只保留本地评测开关。
4. API 捕获只暴露 Token，没有直接暴露估算成本，因此本轮不作成本结论。

