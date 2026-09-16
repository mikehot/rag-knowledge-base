# 作品集说明：Enterprise RAG Knowledge Base

> 本文件只描述已实现能力和当前可核对证据。规划中的企业能力以 `ROADMAP.md` 为准。

## 一句话定位

基于 Java / Spring Boot、PostgreSQL + pgvector 和 Flutter 的企业资料问答 MVP，正在升级为具备 ACL、审计、评测、部署和 Agent 扩展边界的企业知识库项目。

## 当前已实现能力

- RAG 后端：文档解析、Chunk、Embedding、pgvector Top-K 检索和相似度拒答。
- 文档类型：PDF、DOCX、TXT、Markdown。
- 可追溯回答：返回文档名、定位和原文片段，引用由后端检索结果生成。
- AI 集成：OpenAI-compatible Chat + Embedding Provider，可配置本地或云端服务。
- Flutter：问答、文档上传、处理状态轮询、删除和来源片段弹窗。
- 基础工程：JWT 单用户演示、统一响应、上传校验、错误处理、Token 记录、每日限制和 Docker Compose。

## 当前验证证据（2026-09-16）

- `flutter analyze --no-pub`：通过。
- `flutter test --no-pub --concurrency=1`：通过，但当前只有一个 Widget smoke test。
- `docker compose config --quiet`：通过，仅证明 Compose 配置可解析。
- `./mvnw test`：JDK 25.0.3 下通过，10 tests、0 failures/errors。
- PostgreSQL 16.15 + pgvector 0.8.6：扩展、业务表和 HNSW cosine 索引已验证。
- LM Studio：Gemma 4 26B + Nomic Embedding 真实完成上传、2 个 Chunk 入库、资料内回答与引用、资料外拒答和删除。
- 删除验证：document/chunk 行和原始上传文件都被清理，删除后再次查询返回拒答。

历史上记录过 Debug APK 构建通过，但本轮未复现，公开展示前仍需要重新执行并保存当前证据。

## 目标演示流程

当前首先要验证 MVP 基线：

1. 启动 PostgreSQL + pgvector、后端和模型服务。
2. 上传 `sample_faq.md` 并等待文档进入 `ready`。
3. 提问资料内问题，返回 `found=true` 和有效来源。
4. 提问资料外问题，返回 `found=false` 且不编造。
5. 删除文档，确认后续检索无法再命中。

企业知识库 V0.1 在此基础上增加：用户/部门/角色/ACL、文档版本生命周期、结构化审计与反馈、20 题评测和部署 Runbook。

## FDE 能力证明目标

完成后的 Case Study 应证明：

- 能从业务问题定义用户、权限、数据和成功指标；
- 能设计并实现可运行的企业 AI 系统；
- 能用 Eval、日志、延迟、Token 和反馈分析质量；
- 能处理 Provider 超时、检索失败、权限拒绝和文档更新；
- 能完成部署、演示、运维交接和英文技术说明；
- 能用业务采用和工作流改善说明价值，而不是只展示框架名称。

## 当前限制

- 单用户 JWT，不具备企业 RBAC/ACL。
- 没有版本化 20 题评测集和回归报告。
- 没有 Hybrid Search、Reranker、Agent Tool 或 MCP。
- 当前真实模型证据只覆盖单用户样例，尚未覆盖并发、权限隔离和长期运行。
- 没有公开 Demo、部署 Runbook、完整架构图和英文 Case Study。

因此当前准确称呼仍是“RAG MVP / 企业知识库 V0.1 建设中”，不应提前包装成已经生产落地的 Agent 平台。
