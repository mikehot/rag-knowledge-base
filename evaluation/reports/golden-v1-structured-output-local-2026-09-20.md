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

## 2026-09-21 完整预算候选回归

为验证 `AI_MAX_TOKENS=4000` 是否值得替换默认值，在本地恢复临时评测账号后重新采集完整 Golden 和 Stress。候选运行参数为 `RAG_TOP_K=8`、`AI_MAX_TOKENS=4000`、`AI_MAX_RETRIES=0`、`AI_TIMEOUT_SECONDS=180`、`AI_DAILY_LIMIT=200`；默认配置文件没有修改。

| 集合 | 结果 | 关键指标 |
|---|---:|---|
| Golden v1（20 题） | 15/20 | 结构化 API envelope 完整；4 次 `STRUCTURED_OUTPUT_INVALID` fail-closed；拒答正确率 100%；ACL leakage=0；平均 API 15.9s，P95 69.2s，平均 Token 2117 |
| Retrieval Stress v1（8 题） | 8/8 | 答案点、引用覆盖/正确性、拒答正确率均 100%；ACL leakage=0；结构化失败 0；平均 API 10.7s，P95 11.9s，平均 Token 1927 |

本轮 Stress 的受保护 retrieval diagnostics 为 Recall@1/3/5=`75%/91.67%/100%`，期望来源首个排名均值为 `1.1667`，平均 Top-1 similarity=`0.7098`，平均 Top-1 margin=`0.0307`，候选数均值为 `6.375`，ACL leakage=0。也就是说，Stress 的主要目标资料在 Top-5 内，4000 预算的收益主要来自生成完成度，而不是把资料从检索层“找回来”。

本轮不能支持把 4000 直接升为默认：Stress 受益明显，但 Golden 低于此前 `Top-K=5 / 2400` 的 16/20 基线，而且出现 4 次模型 JSON 未完成。由于本轮同时改变了 Top-K、预算和评测时间，结果属于候选配置证据而非严格的单变量 A/B；下一步应优先按失败类别改进召回和模型路由，不继续盲目放大输出预算。

## 预算路由实现状态（2026-09-21）

已在后端加入确定性的 `QuestionComplexityClassifier` 和 Provider `max_tokens` 重载：识别“同时、分别、综合、以及”等明确多部分表达，或多个疑问点后，才允许选择 `AI_COMPLEX_MAX_TOKENS`。`AI_COMPLEX_ROUTING_ENABLED` 默认关闭，当前默认问答路径仍使用 2400；所有 Provider 实现必须显式支持预算参数，避免路由开关打开后静默忽略预算。

已补充普通问题、复杂问题、Provider 请求体和回归测试。完整 Maven 回归通过；Testcontainers 因本机 Docker API metadata 探测问题保持 13 个集成测试 skipped，未将其误报为通过。

## 3200 复杂问题路由真实回归

在 `RAG_TOP_K=5`、普通问题 `AI_MAX_TOKENS=2400`、复杂问题 `AI_COMPLEX_MAX_TOKENS=3200` 且 `AI_COMPLEX_ROUTING_ENABLED=true` 下完成了一次完整 HTTP 回归：

| 集合 | 结果 | 关键指标 |
|---|---:|---|
| Golden v1（20 题） | 13/20 | 2 次 `STRUCTURED_OUTPUT_INVALID`、2 次 `RETRIEVAL_MISS`、5 次 `INSUFFICIENT_CONTEXT`；平均 API 11.1s，P95 11.9s，平均 Token 1468 |
| Retrieval Stress v1（8 题） | 7/8 | 6 个可回答问题均通过；1 个 ACL 拒答出现模型 `found=true` 与拒答正文不一致；ACL leakage=0；平均 API 10.2s，P95 11.2s，平均 Token 1424 |

这轮结果不支持打开路由：Golden 低于 2400 基线，Stress 也出现语义不一致。后端已增加一致性 fail-closed：当模型标记 `found=true` 但正文是系统拒答文案时，统一归一化为 `found=false`、`grounded=false`、无来源和 `INSUFFICIENT_CONTEXT`。预算路由继续保持默认关闭。

## 2026-09-21 评测 Fixture 角色修复与 Top-K 对照

复核 RAG-014/RAG-020 的受保护检索快照时发现，之前的 disposable fixture 复用了已有 `candidate-admin` 用户，却没有按评测凭据中的 `roleCodes` 补齐角色；该用户实际只有 `EMPLOYEE`，而 Golden 的 `demo.admin` 要求 `SYSTEM_ADMIN`。因此部分“召回失败”其实是 ACL 安全裁剪后的空结果。`evaluation/prepare_api_fixture.py` 已改为对已有用户幂等补齐缺失角色，并为四个 Golden actor 提供安全默认角色；本次重新初始化后 `candidate-admin` 为 `EMPLOYEE,SYSTEM_ADMIN`。

修复 fixture 后，在默认 `RAG_TOP_K=5 / AI_MAX_TOKENS=2400` 下重新执行 Golden：

- 16/20 通过，answerable pass rate=`75%`，引用正确性=`81.25%`，答案点覆盖率=`79.17%`，ACL leakage=`0`。
- 7 次原始 `STRUCTURED_OUTPUT_INVALID`，其中 3 次影响可回答题，4 次是拒答路径但后端仍稳定返回拒答契约；没有新的 `RETRIEVAL_MISS`。
- 受保护检索诊断 Recall@1/3/5=`6.25%/75%/93.75%`；唯一未进入 Top-5 的可回答来源是 RAG-014 的 `sample_faq.md`，其余目标来源均在候选集内。

随后只把 `RAG_TOP_K` 调整为 8，保持预算、模型和 fixture 不变：

- RAG-014 的 `sample_faq.md` 进入 rank 8，单题回答和引用恢复正常。
- 完整 Golden 为 15/20，answerable pass rate=`68.75%`，答案点覆盖率=`88.54%`，原始结构化失败降为 4 次；平均 API 延迟约 `12.24s`，平均 Token=`1945`。
- 受保护诊断 Recall@1/3/5/8=`6.25%/75%/93.75%/100%`，但更多候选文档增加上下文干扰，RAG-009、RAG-010、RAG-013、RAG-014、RAG-015 出现答案点或引用退化。

结论：Top-K=8 能覆盖唯一检索缺口，但全局质量低于修复后的 Top-K=5，且 Token 成本更高；默认继续保持 Top-K=5。RAG-014 说明后续可以评估轻量 keyword/full-text 补召回，但当前证据还不足以直接引入复杂 Hybrid Search 或 Reranker。

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

1. 保持默认 `RAG_TOP_K=5 / AI_MAX_TOKENS=2400`，将 4000 作为复杂问题的候选预算，不直接发布为全局默认。
2. 读取受保护 retrieval diagnostics，拆分召回缺口、答案覆盖缺口和模型 JSON 截断，再决定 BM25、Hybrid Search、Reranker 或模型路由是否值得加入。
3. 增加受控的复杂问题预算/模型路由实验，并补齐 generation timeout/invalid-response 的可重复质量与成本门槛。
4. 只有安全边界和评测基线稳定后，再做只读 MCP adapter。
