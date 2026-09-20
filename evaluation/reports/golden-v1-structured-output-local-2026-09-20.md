# Golden v1 Structured Output / Agent Boundary Local Verification

日期：2026-09-20

## 验证范围

本次验证针对 Structured Output Contract 和只读 Agent Tool 的真实本地运行链路：

- Docker PostgreSQL 16.15：`rag-knowledge-base-db` healthy，Flyway 11 migrations validated。
- LM Studio：OpenAI-compatible server `http://127.0.0.1:1234`，聊天模型 `google/gemma-4-26b-a4b-qat`，embedding 模型 `text-embedding-nomic-embed-text-v1.5`。
- Spring Boot：`/actuator/health` 返回 `UP`，Flyway schema version 10。
- HTTP 认证：`demo` 登录成功，匿名访问 Agent Tool 返回 401。

## Structured Output 真实单题结果

问题：夜间升级失败后，回滚需要在多长时间内完成？

- HTTP 200，`found=true`，`grounded=true`。
- 后端返回有效回答和 ACL 过滤后的引用 `long-ops-manual.md / chunk#2`。
- `failureReason=null`。
- 响应包含 `requestId`、`timings.embeddingMs/retrievalMs/generationMs`、`latencyMs` 和 `tokenUsage`。

这证明模型输出经过后端结构化解析、引用索引校验和后端来源重建后，可以完成真实闭环；模型没有成为 `sources`、`requestId` 或失败原因的权威来源。

## Native JSON Schema A/B 复核

Provider 已在 OpenAI-compatible Chat Completions 请求中增加 `response_format=json_schema`，schema 名称为 `rag_answer`，并将 `answer`、`found`、`grounded`、`sourceIndexes` 设为必填且禁止额外字段。后端原有 parser 和 fail-closed 分支仍保留，作为第二道校验。

在相同 5 个失败样本、`RAG_TOP_K=8`、`AI_MAX_TOKENS=2400` 条件下复核：

- 5 题均返回 HTTP 200，4 题完整通过答案点和引用检查。
- 结构化 schema failure 为 0；引用覆盖率/正确性为 80%，答案点覆盖率为 68.33%。
- 之前的 `GENERATION_ERROR` 不再出现；剩余组合题返回明确的 `STRUCTURED_OUTPUT_INVALID`，因为 Gemma 的 reasoning 消耗完 2400 token 后没有结束 JSON。
- 单独将本地实验预算提高到 `AI_MAX_TOKENS=4000` 后，组合题恢复为 `found=true`、`grounded=true`，两条引用正确，端到端约 5.3 秒；4000 尚未作为默认配置发布，仍需完整 Golden/Stress 回归和成本/延迟比较。

LM Studio 服务日志显示，失败请求的 `reasoning_tokens` 接近整个 completion budget，并以 `finish_reason=length` 结束；因此这部分属于模型推理预算/Provider 配置问题，不是后端结构化 parser 或 ACL 边界问题。

## Agent Tool 真实 HTTP 结果

`GET /api/agent/tools` 只暴露以下三项：

- `search_knowledge`
- `list_documents`
- `get_document_status`

已验证：

- 有效的文档列表、向量检索、文档状态均能从真实 PostgreSQL/LM Studio 返回。
- 未知工具返回 `TOOL_NOT_FOUND`。
- `tenantId`、`userId` 等身份覆盖参数返回 `INVALID_ARGUMENTS`。
- 单次超过 3 个调用返回 HTTP 400。
- 匿名访问被认证层拒绝。
- 返回只读文档元数据、状态和检索片段，不返回原始文件内容。

## 20 题复核结果

使用现有 `golden-v1` 和外部本地评测账号完成了 20 题 HTTP capture。第一次运行结果为 13/20，不能作为新的发布基线，原因已拆分如下：

- 2 题受已有评测账号日提问额度影响，返回 HTTP 429；使用临时 `AI_DAILY_LIMIT=200` 复核后两题均正确拒答。
- 2 题是召回排序问题：目标 `sample_faq.md` 没有进入当前 Top-K，分别对应门厚度和换货运费问题。
- 3 题是本地模型生成失败/超时，降低本次运行的最大输出并关闭重试后仍需单独优化本地 provider 参数或输出约束。
- 复核失败样本没有出现新的 Structured Output schema failure；ACL leakage 为 0。

因此，本次结果的结论是：契约、校验、降级和 Agent Tool 边界已具备真实 HTTP 证据；当前剩余问题属于评测隔离、召回排序和本地模型吞吐，不应直接把它们归因于 Tool 权限边界。

## 未完成的环境证据

`PostgresEnterpriseIntegrationTests` 本次仍为 `13 skipped / 0 failures`。Testcontainers 1.21.3 无法通过当前 Docker Desktop API 探测取得有效 server metadata，尽管 Docker CLI、Compose 和 Spring Boot JDBC 连接均正常。该环境问题需要单独修复后，才能把 Docker-backed Java 集成测试升级为执行证据。

## 下一步

暂不开放 MCP，也暂不加入写工具。下一步按失败类别处理：

1. 用受控的新评测身份或隔离数据库重跑 Golden/Stress，避免日限额污染结果。
2. 读取受保护 retrieval diagnostics，确认 Top-K 排序缺口，再决定 BM25、Hybrid Search 或 Reranker 是否值得加入。
3. 单独收敛 LM Studio 的 structured output、最大输出和超时配置，补齐 generation timeout/invalid-response 的可重复测试。
4. 只有安全边界和评测基线稳定后，再做只读 MCP adapter。
