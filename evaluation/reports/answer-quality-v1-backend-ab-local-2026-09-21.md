# Answer Quality v1 Backend A/B（本地端到端联调）

日期：2026-09-21  
环境：本地 Spring Boot 8080 VECTOR、隔离 Spring Boot 8081 KEYWORD_RRF、PostgreSQL 16.15 + pgvector 0.8.6、LM Studio Gemma 4 26B、Nomic Embedding  
数据：`answer-quality-v1` 12 题；同一 credentials、同一数据库和 ACL fixture；只切换检索模式  
原始捕获与受保护诊断：均保留在 `/private/tmp`，未写入仓库

## 结论

初始配对捕获中，KEYWORD_RRF 改善了引用覆盖，但没有稳定提高最终答案质量门槛：在补充合法自然表达匹配（如“一线城市安装服务免费”“七天无理由退货”）后，VECTOR 和 KEYWORD_RRF 均为 10/12，答案可回答子集均为 6/8。KEYWORD_RRF 还出现 2 次 `STRUCTURED_OUTPUT_INVALID`，平均 Token 从 1522.58 增加到 1805.50。

因此继续保持默认 VECTOR，不打开 `RAG_HYBRID_EXPERIMENT_ENABLED`，也不把当前 bounded in-memory scorer 直接产品化为 Hybrid Search。后续复测显示 Keyword-RRF 在本组答案质量题上有潜力，但仍需要重复捕获、P95 和成本证据，不能只凭单次本地模型结果切换默认路径。

## 答案质量与契约

| 指标 | VECTOR | KEYWORD_RRF |
|---|---:|---:|
| 请求数 / HTTP 失败 | 12 / 0 | 12 / 0 |
| 确定性整体通过 | 10/12 | 10/12 |
| ANSWER 通过率 | 6/8（75.0%） | 6/8（75.0%） |
| Citation coverage | 75.00% | 87.50% |
| Citation correctness | 75.00% | 87.50% |
| Expected answer-point coverage | 75.00% | 79.17% |
| Quality gate pass rate | 83.33% | 83.33% |
| REFUSE + ACL 拒答正确率 | 4/4 | 4/4 |
| ACL leakage | 0 | 0 |
| Schema failure | 0 | 0 |

失败定位：

- VECTOR：`QUALITY-002` 未召回安装服务资料；`QUALITY-006` 未召回退换货资料。原先记录的 `QUALITY-001` 是评测匹配词过严造成的误报，模型答案实际包含低电量 App 提醒，已修正数据集。
- KEYWORD_RRF：`QUALITY-004` 发生 `STRUCTURED_OUTPUT_INVALID` / `INSUFFICIENT_CONTEXT`；`QUALITY-006` 只覆盖 15 天换新要点，漏掉退换货运费的非质量问题责任方。Q002、Q005 的旧式固定短语匹配已补充自然表达，不再作为失败。

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

## Structured Output 重试补充

后续代码增加了 `AI_STRUCTURED_OUTPUT_RETRIES`，默认值为 1；仅当第一次模型输出无法解析为约定 JSON 时追加一次带修复指令的请求，成功则合并 Token/生成耗时，仍失败则返回原有 `STRUCTURED_OUTPUT_INVALID` 安全降级。针对性测试和完整后端回归均通过。

一次隔离 8081 实例的 12 题重试捕获中，`QUALITY-004` 恢复为完整答案，4 个拒答全部通过；但本地模型输出存在随机性，整体答案质量仍为 8/12，不能据此宣称质量提升。该捕获未作为新的正式 A/B 基线，默认服务仍保持 VECTOR。

## Prompt hardening 后新鲜复测（补充证据）

为验证多要点编号 Prompt 和 Structured Output 有界重试，使用同一 PostgreSQL/ACL fixture，在隔离 Spring Boot 8081 实例上重新捕获 12 题；`AI_DAILY_LIMIT=500`，只将 `RAG_HYBRID_EXPERIMENT_ENABLED=true` 用于接受 `X-RAG-Retrieval-Mode` 实验请求，默认 8080 服务未改变。原始 JSONL 保留在 `/private/tmp`，未写入仓库：

- VECTOR：`/private/tmp/rag-quality-v1-vector-prompt-hardening-20260921.jsonl`
- KEYWORD_RRF：`/private/tmp/rag-quality-v1-keyword-rrf-prompt-hardening-20260921.jsonl`

本次同时补充了评分器对合法自然表达的覆盖：例如“一线城市安装服务免费”“七天无理由退货”不应因为没有复现带数字空格的固定短语而误报。Q002、Q005 的旧式匹配误报被修正；Q006 的“非质量问题由买家承担”仍作为独立要点保留。

| 指标 | VECTOR | KEYWORD_RRF |
|---|---:|---:|
| 请求数 / HTTP 失败 | 12 / 0 | 12 / 0 |
| 确定性整体通过 | 10/12 | 11/12 |
| ANSWER 通过率 | 6/8（75.0%） | 7/8（87.5%） |
| Citation coverage / correctness | 75.00% / 75.00% | 100.00% / 100.00% |
| Expected answer-point coverage | 75.00% | 95.83% |
| Quality gate pass rate | 83.33% | 91.67% |
| REFUSE + ACL 拒答正确率 | 4/4 | 4/4 |
| ACL leakage / Schema failure | 0 / 0 | 0 / 0 |
| HTTP P50 / P95 | 13484 / 17395 ms | 13860 / 15788 ms |
| 平均 Token | 1714.42 | 1675.42 |

失败定位：VECTOR 的 Q002、Q006 仍为 `INSUFFICIENT_CONTEXT`，与数据库中存在且 ACL 可见的 `sample_faq.md` 内容对照后，属于向量召回缺口；KEYWORD_RRF 的 Q002 已完整回答，Q006 真实漏答“非质量问题由买家承担”。编号 Prompt 没有修复 VECTOR 召回，也没有稳定消除 KEYWORD_RRF 的多要点漏答。该结果是补充样本而非替换初始 A/B 基线，因为本地模型存在生成随机性，且评分器在复测前增加了合法自然表达。

本次两种模式均只出现 `INSUFFICIENT_CONTEXT`，没有新的 `STRUCTURED_OUTPUT_INVALID`；这不能证明重试已经解决 Provider 稳定性，只说明本次 12 题捕获未触发该失败分类。延迟和 Token 仍是 LM Studio 单机小样本，不作为生产 SLO 或成本结论。

## 员工 ACL 对齐定向重复稳定性捕获（补充证据）

本次使用 `prepare_api_fixture.py` 配置的本地 disposable `demo.employee` 账号，实际用户具有 `SUPPORT` 部门和 `EMPLOYEE` 角色；fixture 只授予 `sample_faq.md` 和四份压力文档的用户级 READ，未授予 `finance-policy.md` 或 `hr-policy.md`。原始捕获和凭据均保留在 `/private/tmp`，未写入仓库。每种模式针对 `QUALITY-002`、`QUALITY-006` 各重复 3 次，12 次请求全部 HTTP 200、API code 0：

| case / mode | found | grounded | failureReason | outcome variants | API P50 / P95 ms | 平均 Token |
|---|---:|---:|---|---:|---:|---:|
| Q002 / VECTOR | 0/3 | 0/3 | `INSUFFICIENT_CONTEXT` 3 | 1 | 10014 / 10797 | 1379.00 |
| Q006 / VECTOR | 0/3 | 0/3 | `INSUFFICIENT_CONTEXT` 3 | 1 | 10533 / 10658.1 | 1430.00 |
| Q002 / KEYWORD_RRF | 0/3 | 0/3 | `STRUCTURED_OUTPUT_INVALID` 3 | 1 | 9820 / 9993.7 | 1380.00 |
| Q006 / KEYWORD_RRF | 0/3 | 0/3 | `STRUCTURED_OUTPUT_INVALID` 3 | 1 | 9706 / 9783.4 | 1430.00 |

本轮只验证了员工权限边界下的重复行为，不是 12 题全量质量门槛。Q002 在 VECTOR 下稳定未找到可用上下文，在 KEYWORD_RRF 下稳定触发结构化输出失败；Q006 在 VECTOR 下稳定未找到可用上下文，在 KEYWORD_RRF 下稳定触发结构化输出失败。没有来源泄漏，也没有证据支持打开默认 Hybrid Search。评测结束后已恢复 `RAG_HYBRID_EXPERIMENT_ENABLED=false`。
