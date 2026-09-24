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
| 后端回归 | 2026-09-24 本地 Docker-backed 123 tests，0 failures/errors/skipped | 本地代码/集成回归通过，不等于 CI 新运行或生产 SLA |
| Golden Dataset | 历史数据契约和本地运行有 20/20；最近记录的 Top-K=5/Thinking-off 为 16/20 | 合成样例，不是客户准确率或上线门槛通过 |
| Retrieval Stress | 最近记录的 Thinking-off 运行 7/8；历史有 8/8；最近质量 A/B 的 ACL leakage=0 | 小样本、配置相关；不是生产安全保证 |
| Answer Quality | 修订 rubric 的 VECTOR 12 题集两次记录 10/12；Q002/Q006 两次均失败。source-preserving 离线候选无替换、覆盖仍 75%（6/8） | 评测工具可复跑，但质量门未通过；不启用 Hybrid、Reranker 或相邻策略 |
| Structured Output | LM Studio Gemma Thinking-off 合同探针 6/6；Thinking-on 重复探针曾发生 token 耗尽/无效 JSON；后端现记录脱敏终止元数据，并对非正常终止 fail-closed | 新 metadata guard 已通过合成回归；撤权后失败尚未用新观测重放，不能推断根因或声称跨配置稳定 |
| ACL / Flutter 运营 | 9/23 API 测试：撤权后员工搜索和列表均不再返回目标文档。9/24 真机 `.verify`：管理员授权/撤权令员工列表 5→6→5；索引失败 3/3 后 UI 安全重试至 4/6 成功。非系统 EMPLOYEE 文档管理员真机加载 USER/DEPARTMENT/ROLE 候选；USER 与 Employee ROLE 的 READ 授权/撤权均改变合成员工列表可见性 | Flutter 主体目录最小路径已有设备证据；部门 grant/revoke 与跨租户真机路径未测（跨租户 API/SQL 测试通过）。撤权后的一次问答未通过结构化输出，不能声称完整拒答 |
| MCP | 只读 HTTP smoke 16/16；四个 adapter 单元测试通过；恢复后服务再次 16/16 | 不是完整 MCP SDK/client conformance |

2026-09-22 的 disposable API Demo 覆盖上传、持久化索引、授权回答/引用、反馈和拒答；2026-09-23 Android 设备证据覆盖新文件上传到 READY。随后独立完成本地人工备份/恢复。2026-09-24 API 连续演示验证授权引用、拒答、权限拒绝、反馈、MCP 16/16 和索引失败恢复；同日隔离 Android `.verify` 包验证管理员 ACL/索引任务流程，以及非系统 EMPLOYEE 文档管理员加载 USER/DEPARTMENT/ROLE 候选、通过 Flutter 授权/撤销 USER 与 Employee ROLE READ，并观察合成员工文档列表可见性变化。初始问法仍暴露 Top-5 措辞敏感，故不代表质量门通过。本机 LM Studio 合成持久化探针在一个 server-log 文件的 4,055 个新增字节中发现输入、输出标记；未读取旧日志或保留原文。日志文件权限位为 0644，owning `staff` 组访问边界、完整内容分类、脱敏及保留策略仍未闭环；敏感资料不得通过当前配置。后端现对结构化失败记录不含内容的 Provider 终止元数据，并对非正常终止 fail-closed；仅经合成测试验证，尚未重放此前撤权失败。真机未覆盖部门 grant/revoke、员工问答/引用或生产安全验证。完整边界见 [PROGRESS.md](../PROGRESS.md)、[连续演示记录](../evaluation/reports/continuous-disposable-demo-local-2026-09-24.md)、[主体目录真机报告](../evaluation/reports/document-acl-principal-directory-local-2026-09-24.md)、[结构化终止诊断报告](../evaluation/reports/structured-output-termination-diagnostics-local-2026-09-24.md)、[管理员 ACL/索引任务报告](../evaluation/reports/flutter-operations-acl-index-task-device-local-2026-09-24.md)、[Provider 日志复核](../evaluation/reports/provider-log-boundary-review-local-2026-09-24.md)、[备份恢复记录](../evaluation/reports/backup-restore-rehearsal-local-2026-09-23.md) 与 [DEMO.md](DEMO.md)。

同日后续将本机 LM Studio `server-logs` 根目录收紧为 owner-only `0700`；旧日志文件仍为 `0644`，通过父目录权限限制访问。应用重启或重建目录后此权限是否保持、以及应用内保留/轮转策略仍未验证；这属于本机缓解措施，不是产品级日志治理能力。

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

- 继续保持 Flutter 运营 UI 的合成设备回归；模型结构化拒答稳定后，再在设备上补验撤权后的员工问答/引用，并只保留非敏感聚合记录。
- 审查 LM Studio Developer Logs/model-I/O 持久化、访问、脱敏和保留控制；在配置不清楚或不可接受前，不发送敏感客户内容。
- 用真实业务文档补充评测集和反馈闭环。
- 在第三方 MCP SDK/client 验证明确需求后，再决定是否做完整互操作支持。
- 只有当评测、P95、成本和上下文干扰同时通过门槛，才考虑 Hybrid Search 或 Reranker。
