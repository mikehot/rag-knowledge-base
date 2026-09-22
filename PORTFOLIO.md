# 作品集说明：Enterprise RAG Knowledge Base

> 本文件只描述已实现能力和当前可核对证据。规划中的企业能力以 `ROADMAP.md` 为准。

## 一句话定位

基于 Java / Spring Boot、PostgreSQL + pgvector 和 Flutter 的企业资料问答项目，正在以可验证的 ACL、审计、评测、部署和只读 Agent/MCP 边界交付企业知识库 V0.1。

## 当前已实现能力

- RAG 后端：文档解析、Chunk、Embedding、pgvector Top-K 检索和相似度拒答。
- 文档类型：PDF、DOCX、TXT、Markdown。
- 可追溯回答：返回文档名、定位和原文片段，引用由后端检索结果生成。
- Structured Output Contract：模型只提交答案、found/grounded 和资料片段编号；后端校验 JSON、引用范围和 ACL 后生成最终来源字段，非法结构或缺失引用安全转人工。
- AI 集成：OpenAI-compatible Chat + Embedding Provider，可配置本地或云端服务。
- Flutter：问答、文档上传、处理状态轮询、删除和来源片段弹窗。
- 企业边界：JWT 携带 tenant/user 上下文，用户、部门、角色、知识库 membership 和 document ACL 已落地；文档列表、详情和向量检索在服务端执行权限过滤。
- 交付治理：文档版本与持久化索引任务、失败重试、审计事件、requestId、分段耗时、Token/成本指标、健康探针和用户反馈已实现；具体验证状态见 [PROGRESS.md](PROGRESS.md)。
- 交付材料：系统架构、Discovery Brief、5–10 分钟 Demo、部署/故障 Runbook，以及中英文 Case Study 已整理在 [docs/](docs/)；内容明确区分本地证据、演示能力和生产限制。

## 当前验证证据（2026-09-20）

- `flutter analyze --no-pub`：通过。
- `flutter test --no-pub --concurrency=1`：通过，但当前只有一个 Widget smoke test。
- `docker compose config --quiet`：通过，仅证明 Compose 配置可解析。
- `./mvnw test`：本地 JDK 25.0.3 + Docker Desktop 下 109 个测试通过，0 failures/errors/skipped；包含 14 个 PostgreSQL/Testcontainers 集成测试。
- PostgreSQL 16.15 + pgvector 0.8.6：扩展、业务表和 HNSW cosine 索引已验证。
- LM Studio：Gemma 4 26B + Nomic Embedding 真实完成上传、2 个 Chunk 入库、资料内回答与引用、资料外拒答和删除。
- 删除验证：document/chunk 行和原始上传文件都被清理，删除后再次查询返回拒答。
- 评测契约：`evaluation/datasets/golden_v1.jsonl` 包含 20 题，另有 8 题 `retrieval_stress_v1.jsonl`；`run_eval.py` 通过数据完整性和响应评分检查，历史本地 Golden 两次 20/20、压力集最新 8/8 通过；最新压力集答案点、引用、拒答均 100%，ACL leakage=0。STRESS-003 的生成预算问题已通过默认 `AI_MAX_TOKENS=2400` 修复并由完整套件验证。
- 评测采集：`evaluation/run_api_eval.py` 已提供外部 credentials map 和 `/api/ask` JSONL 采集入口；真实用户/ACL fixture 已在 disposable tenant 验证，原始响应不入库。
- Agent Tool：`GET /api/agent/tools` 和 `POST /api/agent/tools/execute` 已提供三个只读工具，继承 tenant/用户/ACL，限制未知参数和单次三调用预算，并记录 allow/deny/error 审计。
- MCP：无状态 `POST /mcp` 适配层和标准库 HTTP smoke 已验证发现、列表、调用、身份参数覆盖拒绝、Header 不匹配和未认证 401；本地 smoke 16/16 通过，但不声称完整 SDK/client conformance。
- 隔离 Demo：在独立 PostgreSQL/pgvector 数据库中完成上传、持久化索引、授权回答、引用、反馈、资料外 fail-closed、员工 ACL 拒绝和 MCP 只读边界；聚合记录见 [evaluation/reports/demo-v0.1-disposable-local-2026-09-22.md](evaluation/reports/demo-v0.1-disposable-local-2026-09-22.md)。

历史上记录过 Debug APK 构建通过，但本轮未复现，公开展示前仍需要重新执行并保存当前证据。

## 目标演示流程

当前首先要验证 MVP 基线：

1. 启动 PostgreSQL + pgvector、后端和模型服务。
2. 上传 `sample_faq.md` 并等待文档进入 `ready`。
3. 提问资料内问题，返回 `found=true` 和有效来源。
4. 提问资料外问题，返回 `found=false` 且不编造。
5. 删除文档，确认后续检索无法再命中。

企业知识库 V0.1 的交付材料已整理完成；管理 UI、干净 disposable Demo 记录、公开部署和真实客户基线仍需后续补证据。

## FDE 能力证明目标

完成后的 Case Study 应证明：

- 能从业务问题定义用户、权限、数据和成功指标；
- 能设计并实现可运行的企业 AI 系统；
- 能用 Eval、日志、延迟、Token 和反馈分析质量；
- 能处理 Provider 超时、检索失败、权限拒绝和文档更新；
- 能完成部署、演示、运维交接和英文技术说明；
- 能用业务采用和工作流改善说明价值，而不是只展示框架名称。

## 当前限制

- 尚无前端管理页、批量导入、用户/部门停用和更细的知识库管理员权限矩阵。
- 已有两次版本化 20 题真实 API 回归，以及历史和最新均 8/8 的检索压力集证据；V10 已增加仅管理员/审计员可读的候选排序与相似度诊断，并提供 Recall@1/3/5 评分脚本；更大语料评测、云端成本对照和独立模型评分仍未完成。
- 尚无生产级 Hybrid Search 和 Reranker；MCP 目前是经过边界验证的无状态只读适配层，没有模型驱动 loop 或写操作。
- 当前真实模型证据主要覆盖样例知识和索引任务；多用户真实演示、并发和长期运行仍需补证据。
- 尚无公开可访问 Demo、Flutter 设备截图/录屏、真实客户生产部署和长期运营基线；当前已有一次隔离 API Demo 聚合记录，不能替代上述证据。

因此当前准确称呼仍是“RAG MVP / 企业知识库 V0.1 建设中”，不应提前包装成已经生产落地的 Agent 平台。
