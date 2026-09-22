# Case Study：从 RAG Demo 到可治理的企业知识库 V0.1

> 本案例使用脱敏样例和本地验证环境，不代表真实客户生产上线，也不构成准确率、SLA 或 ROI 承诺。

## 1. 背景

原始项目能够把文档切片、向量化并回答问题，但企业交付还需要回答更多问题：员工是否只能看到授权资料？回答能否引用来源？文档更新失败如何恢复？模型输出不完整怎么办？上线后如何判断系统变慢、变贵或答错？

## 2. 目标

建立一个可以运行、解释、评测、部署和交接的企业知识库闭环：

> 管理员上传文档 → 持久化索引 → 员工按权限提问 → 后端返回可校验引用 → 记录审计/延迟/Token/反馈 → 用版本化评测集复核质量。

## 3. 方案

- Java/Spring Boot 保留企业 API、事务、权限、生命周期和运维接口。
- PostgreSQL + pgvector 统一保存业务、ACL、Chunk、审计、问答观测和任务状态。
- `index_task` 提供幂等入队、有限重试、失败状态、耗时和重启恢复。
- 检索阶段先执行 tenant/user/department/role/knowledge-base/document ACL，再进行向量 Top-K。
- 模型只返回受约束的 `answer`、`found`、`grounded` 和 `sourceIndexes`；后端二次校验并生成最终引用。
- 三个只读 Agent Tool 和最小无状态 MCP 适配层复用同一身份、ACL、参数和审计边界。

架构细节见 [ARCHITECTURE.md](ARCHITECTURE.md)，部署和故障处理见 [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md)。

## 4. 验证结果

| 维度 | 当前证据 | 结论边界 |
|---|---|---|
| 后端回归 | 本地 Docker-backed 109 tests，0 failures/errors/skipped | 代码和集成回归通过，不等于生产 SLA |
| Golden Dataset | 20 题数据契约通过；历史真实本地运行有 20/20；当前默认 Top-K=5 的一次质量结果为 16/20 | 样例数据，不是客户准确率 |
| Retrieval Stress | 8 题历史真实基线 8/8，ACL leakage=0 | 小规模压力集 |
| Answer Quality | 12 题 VECTOR/VECTOR_DIVERSITY A/B 均质量门 8/12；默认不切换多样性检索 | 本地 Provider 稳定性仍是限制 |
| Structured Output | Gemma/Qwen 当前独立探针未通过能力门禁 | 不切换模型、不宽松解析 |
| ACL | 跨租户/未授权路径真实和集成测试，ACL leakage=0 | 尚未覆盖所有真实组织继承规则 |
| MCP | 只读 HTTP smoke 16/16；四个 adapter 单元测试通过 | 不是完整 MCP SDK/client conformance |

## 5. 业务价值假设

项目目前不虚构 ROI，而是定义待客户数据验证的指标：

- 员工查找资料的平均时间是否下降；
- 有引用回答的采纳率和有帮助反馈率是否提升；
- 资料外拒答和 ACL 拒绝是否减少错误传播；
- 文档更新到可检索的时间、失败重试成功率和人工介入次数；
- 单次问答 Token、延迟和 Provider 成本是否在预算内。

上线前需要用真实用户、真实文档和至少 7 天运营基线重新测量，不能把本地样例数据当作业务收益。

## 6. 交付中的关键取舍

1. 没有因为“主流”而把 Java 后端重写成 Python；Python 只承担评测和实验。
2. 没有因为一次局部召回缺口就打开 Hybrid Search、Reranker 或提高默认 Top-K。
3. 没有把模型生成的文件名当作可信引用；引用由后端 ACL 可见结果映射。
4. 没有先做写 Agent；只读 Tool/MCP 先通过权限、预算、审计和失败路径验证。
5. 没有把本地 LM Studio 能运行包装成生产部署；Provider 日志和模型输出稳定性仍需治理。

## 7. 下一阶段

- 用当前 [DEMO.md](DEMO.md) 完成一次干净 disposable 环境演示并保存非敏感截图/记录。
- 用真实业务文档补充评测集和反馈闭环。
- 在第三方 MCP SDK/client 验证明确需求后，再决定是否做完整互操作支持。
- 只有当评测、P95、成本和上下文干扰同时通过门槛，才考虑 Hybrid Search 或 Reranker。
