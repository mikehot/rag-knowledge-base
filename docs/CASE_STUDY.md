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
| 后端回归 | 2026-09-23 本地 Docker-backed 116 tests，0 failures/errors/skipped | 本地代码/集成回归通过，不等于 CI 新运行或生产 SLA |
| Golden Dataset | 历史数据契约和本地运行有 20/20；最近记录的 Top-K=5/Thinking-off 为 16/20 | 合成样例，不是客户准确率或上线门槛通过 |
| Retrieval Stress | 最近记录的 Thinking-off 运行 7/8；历史有 8/8；最近质量 A/B 的 ACL leakage=0 | 小样本、配置相关；不是生产安全保证 |
| Answer Quality | 修订 rubric 的 VECTOR 12 题集两次记录 10/12；Q002/Q006 两次均失败。source-preserving 离线候选无替换、覆盖仍 75%（6/8） | 评测工具可复跑，但质量门未通过；不启用 Hybrid、Reranker 或相邻策略 |
| Structured Output | LM Studio Gemma Thinking-off 合同探针 6/6；Thinking-on 重复探针曾发生 token 耗尽/无效 JSON | Provider 本地设置敏感；默认仍 fail-closed，不代表跨配置稳定 |
| ACL / Flutter 运营 | 2026-09-23 disposable API 实测：授权后员工搜索返回目标来源；撤权后搜索与文档列表均不再返回该文档，leakage=0。失败索引任务 attempt 3 后管理员 retry，attempt 4 成功。Flutter ACL/任务界面静态分析与模型测试通过 | 后端权限与任务恢复 API 已验收；Flutter 管理 UI 被 Android `INSTALL_FAILED_USER_RESTRICTED` 阻断，尚无真机交互证据 |
| MCP | 只读 HTTP smoke 16/16；四个 adapter 单元测试通过；恢复后服务再次 16/16 | 不是完整 MCP SDK/client conformance |

2026-09-22 的空 disposable API Demo 覆盖上传、持久化索引、授权回答/引用、反馈、拒答和员工无权限拒绝；2026-09-23 Android 设备证据覆盖新文件上传到 READY。随后在独立 disposable PostgreSQL 和上传目录中完成一次人工备份/恢复，恢复后 7 个任务状态、员工 ACL 隔离及 MCP smoke 均通过。该证据不包括生产备份调度、异地副本、加密或 PITR。Flutter ACL 管理 UI 仍未设备验收；以上完整业务流程仍待一次性连续重演，详见 [PROGRESS.md](../PROGRESS.md)、[备份恢复记录](../evaluation/reports/backup-restore-rehearsal-local-2026-09-23.md) 与 [DEMO.md](DEMO.md)。

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

- 用当前 [DEMO.md](DEMO.md) 完成一次干净 disposable 环境演示，增加 ACL 撤权后拒绝引用、失败任务安全重试及备份/恢复验收；只保留非敏感聚合记录。
- 用真实业务文档补充评测集和反馈闭环。
- 在第三方 MCP SDK/client 验证明确需求后，再决定是否做完整互操作支持。
- 只有当评测、P95、成本和上下文干扰同时通过门槛，才考虑 Hybrid Search 或 Reranker。
