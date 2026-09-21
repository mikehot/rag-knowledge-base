# Answer Quality v1 Backend A/B（本地端到端联调）

日期：2026-09-21  
环境：本地 Spring Boot 8080 VECTOR、隔离 Spring Boot 8081 KEYWORD_RRF、PostgreSQL 16.15 + pgvector 0.8.6、LM Studio Gemma 4 26B、Nomic Embedding  
数据：`answer-quality-v1` 12 题；同一 credentials、同一数据库和 ACL fixture；只切换检索模式  
原始捕获与受保护诊断：均保留在 `/private/tmp`，未写入仓库

## 结论

KEYWORD_RRF 在这组 12 题上显著改善了候选召回和引用覆盖，但没有提高最终答案质量门槛：VECTOR 与 KEYWORD_RRF 均为 9/12 通过，答案可回答子集均为 5/8 通过。KEYWORD_RRF 还出现 2 次 `STRUCTURED_OUTPUT_INVALID`，平均 Token 从 1522.58 增加到 1805.50。

因此继续保持默认 VECTOR，不打开 `RAG_HYBRID_EXPERIMENT_ENABLED`，也不把当前 bounded in-memory scorer 直接产品化为 Hybrid Search。

## 答案质量与契约

| 指标 | VECTOR | KEYWORD_RRF |
|---|---:|---:|
| 请求数 / HTTP 失败 | 12 / 0 | 12 / 0 |
| 确定性整体通过 | 9/12 | 9/12 |
| ANSWER 通过率 | 5/8（62.5%） | 5/8（62.5%） |
| Citation coverage | 75.00% | 87.50% |
| Citation correctness | 75.00% | 87.50% |
| Expected answer-point coverage | 70.83% | 72.92% |
| Quality gate pass rate | 75.00% | 75.00% |
| REFUSE + ACL 拒答正确率 | 4/4 | 4/4 |
| ACL leakage | 0 | 0 |
| Schema failure | 0 | 0 |

失败定位：

- VECTOR：`QUALITY-001` 漏掉低电量 App 提醒；`QUALITY-002` 未召回安装服务资料；`QUALITY-006` 未召回退换货资料。
- KEYWORD_RRF：`QUALITY-002` 找到资料但漏掉“一线城市免费”；`QUALITY-004` 发生 `STRUCTURED_OUTPUT_INVALID` / `INSUFFICIENT_CONTEXT`；`QUALITY-006` 只覆盖部分退换货运费要点。

这说明 `QUALITY-002` 主要暴露召回/排序问题，而 `QUALITY-001`、`QUALITY-004`、`QUALITY-006` 同时暴露生成完整性和结构化输出稳定性问题；不能只靠换检索器解决。

## 检索与资源

| 指标 | VECTOR | KEYWORD_RRF | 变化 |
|---|---:|---:|---:|
| Recall@1 | 12.50% | 87.50% | +75.00pp |
| Recall@3 | 87.50% | 100.00% | +12.50pp |
| Recall@5 | 87.50% | 100.00% | +12.50pp |
| 首个目标平均排名 | 2.7143 | 1.2500 | 改善 |
| HTTP P50 / P95 | 11825 / 13239 ms | 11623 / 12496 ms | 小样本波动 |
| 平均 Token | 1522.58 | 1805.50 | +18.58% |
| 失败分类 | INSUFFICIENT_CONTEXT=6 | INSUFFICIENT_CONTEXT=3；STRUCTURED_OUTPUT_INVALID=2 | 需关注生成稳定性 |

两种模式的受保护诊断均为 12/12，ACL leakage=0。KEYWORD_RRF 先执行服务端 tenant、知识库、生命周期、owner、部门、角色和文档 ACL 过滤，再进行候选融合。

## 决策与下一步

1. 保持 `RAG_HYBRID_EXPERIMENT_ENABLED=false`，默认 VECTOR 不变。
2. 优先修复 `QUALITY-002` 的召回缺口，以及 `QUALITY-004` / `QUALITY-006` 的生成完整性和 Structured Output 稳定性。
3. 扩充第二批跨文档、相似术语和更长答案案例，再复测质量门槛；在答案质量没有明确提升前，不引入生产级 FTS、Reranker 或更复杂 Agent 链路。
4. 本次 API 捕获只暴露 Token，没有直接暴露实际费用，因此不作成本结论。
