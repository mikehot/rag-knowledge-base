# PROGRESS

> Last evidence review: 2026-09-21
> Source of truth for implemented and verified status. Planned capabilities live in `REQUIREMENTS.md` and `ROADMAP.md`.

## 状态定义

- `planned`：已有范围和验收条件，尚未实现。
- `in-progress`：已开始实现或验证，尚未通过完整门槛。
- `verified`：代码、自动检查、运行证据和文档满足门槛。
- `blocked`：存在明确外部依赖，已记录阻塞条件。

## 当前总览

| 里程碑 | 状态 | 当前结论 |
|---|---|---|
| 1. 可复现 RAG 基线 | `verified` | JDK 25、真实 pgvector + LM Studio 上传/命中/拒答/删除闭环通过 |
| 2. 身份、ACL、文档生命周期 | `in-progress` | Flyway V1-V11、ACL、最小管理 API、可回滚替换和持久化索引任务已落地；PostgreSQL CI、真实 Provider 重试和重启恢复已验证，管理 UI 等次要范围仍未完成 |
| 3. 结构化回答、审计、观测、反馈 | `verified` | requestId、总/分段耗时、稳定失败分类、超时/参数校验/401/403 契约、V6/V7 观测字段、V8 用户反馈、健康/就绪探针、反馈/ACL 拒绝/Token/估算成本指标、受保护检索诊断和 Structured Output Contract 已落地；本地 Docker-backed 全量回归 98 passed/0 skipped，OpenAI-compatible Provider 已发送原生 JSON Schema，并完成 LM Studio 真实 A/B 复核 |
| 4. 评测基线与检索压力集 | `verified` | `golden-v1` 历史两次 20/20、修复评测 fixture 后默认 Top-K=5 为 16/20、压力集 8/8；ACL leakage=0；V10 受保护诊断及 Recall@1/3/5 评分已落地，Top-K=8 对照未通过全局质量门槛 |
| 5. Hybrid Search / Reranker | `planned` | 只在评测证明需要后启动 |
| 6. Agent Tool / MCP | `in-progress` | 三个只读 Agent Tool、闭合参数 schema、ACL/租户继承、空结果/超时/未知工具/预算/审计测试已落地；MCP 适配器和真实 Agent loop 尚未开放 |
| 7. 交付包 / FDE Case Study | `in-progress` | Roadmap 与需求基线已形成；架构图、Demo、Runbook、Case Study 未完成 |

## 已实现（代码静态核对）

### 后端

- Spring Boot 3.5.7、Java 17 target、Maven wrapper、Dockerfile。
- `/api/auth/login`、文档上传/列表/详情/删除、`/api/ask`。
- JWT 鉴权已携带 `userId + tenantId`；统一响应、全局中文错误、上传白名单和大小限制。
- PDFBox / POI / TXT/Markdown 解析。
- PostgreSQL 持久化索引任务通过 `@TransactionalEventListener(AFTER_COMMIT)` 触发，并由定时轮询恢复未完成任务。
- 配置化 Chunk、Embedding、Top-K 和相似度阈值。
- PostgreSQL + pgvector Chunk 表、HNSW cosine 索引和向量查询。
- OpenAI-compatible Chat 与 Embedding Provider，Java `HttpClient` 使用 HTTP/1.1。
- 低相似度短路、`found=false` 拒答、来源返回、Token 记录和每日提问限制。
- 所有 HTTP 响应返回 `X-Request-Id`；合法调用方 UUID 会被保留，无效值会被安全替换，并在请求结束后清理 MDC。
- 问答响应返回 `requestId`、总耗时和 embedding/retrieval/generation 分段耗时；失败原因限定为 `RETRIEVAL_MISS`、`INSUFFICIENT_CONTEXT`、`EMBEDDING_TIMEOUT`、`EMBEDDING_ERROR`、`RETRIEVAL_ERROR`、`GENERATION_TIMEOUT`、`GENERATION_ERROR`；Provider 超时与普通失败保留不同类型。
- Chat、Embedding 和 AskService 共用超时分类规则，能识别直接或嵌套的 `HttpTimeoutException` / `SocketTimeoutException`；不会把底层异常正文返回给调用方。
- 未认证请求统一返回 HTTP 401 + `code=401`；已认证但无权限请求统一返回 HTTP 403 + `code=403`；参数校验/JSON 格式错误统一返回 HTTP 400 + `code=400`，均有 MockMvc 契约测试。
- `ask_log` 记录 tenant、requestId、结果状态、失败分类、总/分段耗时、模型 ID、Top-K 和相似度阈值，不记录异常原文、Prompt 或文档正文。
- `PUT /api/ask/{requestId}/feedback` 支持 `HELPFUL` / `NOT_HELPFUL` 和可选原因；只允许原提问用户在同一 tenant 内创建或修改，单答案保持一条反馈，原因限制 500 字并清理控制字符。
- `/actuator/health`、`/livez`、`/readyz` 提供不含组件详情的公开状态探针；`/actuator/metrics` 和 `/actuator/prometheus` 需要认证且仅允许 `SYSTEM_ADMIN` / `AUDITOR`。
- Micrometer 记录问答结果/失败分类、总/分段耗时、Token、按配置单价估算的成本、反馈提交、持久化 ACL 拒绝及索引任务结果/耗时；标签只使用固定的 result/failure/stage/rating/action/resource，不包含 tenant、用户、问题、正文、Prompt 或原始异常。
- Flyway V1-V11 管理 RAG、企业身份/ACL、幂等约束、审计、持久化索引任务、问答观测、用户反馈、检索诊断和检索模式 schema；V9 修复旧库 `app_user.created_at` 默认值兼容性，V10 保存不含问题正文的 Top-K 检索快照，V11 保存 VECTOR/KEYWORD_RRF 模式；Hibernate 只做 schema validate。
- V2 已包含 tenant、department、role、user-role、knowledge base、membership 和 document ACL。
- 文档列表/详情和 Chunk 向量查询在 SQL 阶段执行 tenant + user/department/role + knowledge-base/document ACL 过滤。
- `/api/ask` 已执行 Structured Output Contract：模型只返回 `answer`、`found`、`grounded`、`sourceIndexes`；OpenAI-compatible Provider 同时发送 `response_format=json_schema`；后端继续校验结构和引用编号，再生成 `sources`、`requestId`、`failureReason`、`timings`。
- `/api/agent/tools` 已建立应用自有只读 Tool Registry，只注册 `search_knowledge`、`list_documents`、`get_document_status`；单次最多 3 个调用，拒绝未知字段、越权知识库、越权文档和所有写操作。
- 文档 ACL 管理 API 已支持按 USER/DEPARTMENT/ROLE 授权或撤权；操作要求文档 `MANAGE`，权限版本递增，越权按隐藏资源返回 404。
- 上传和删除要求 `MANAGE`；无权限删除统一返回 404，避免暴露资源存在性。
- 文档上传已计算 SHA-256 checksum；同一知识库内相同 checksum 的未删除文档会幂等返回已有文档。
- 文档生命周期已支持停用/启用/reindex/软删除；reindex 会递增内容版本并保留旧 Chunk，直到新 Chunk 成功后原子切换；停用/软删除会从列表、详情和检索中隐藏。
- 替换上传使用版本化文件路径并递增 `contentVersion`；新内容解析与 Embedding 成功后才原子切换 Chunk 和清理旧文件，处理失败会恢复旧元数据、旧版本和旧可检索状态。
- V3 增加同一 tenant + knowledge base + checksum 的未删除文档唯一索引。
- V4 增加 `audit_event`，用于记录权限拒绝、资源类型、资源 ID、原因和时间。
- V5 增加 `index_task`；上传、替换和单个/批量 reindex 均先持久化任务，再异步领取执行。
- 索引任务以 `documentId:contentVersion` 幂等；使用 `FOR UPDATE SKIP LOCKED` 安全领取，默认最多自动执行 3 次，并支持失败任务人工追加 3 次机会。
- 管理 API 支持批量重建、按状态查询任务、查看失败原因/次数/开始结束时间/耗时，以及人工重试；所有操作要求目标知识库 `MANAGE`。
- 最小管理 API 已包含部门列表/创建、角色列表、用户列表/创建、用户角色授予/撤销、知识库列表/创建/启停、知识库 membership 查询/授权/撤权。
- 部门、用户、用户角色和知识库生命周期管理要求 `SYSTEM_ADMIN`；知识库 membership 管理要求该知识库 `MANAGE` 权限。
- 角色矩阵已明确：`SYSTEM_ADMIN` 是租户级管理；`KNOWLEDGE_ADMIN` 仅作为业务角色预留，需通过具体知识库 `MANAGE` 授权生效；`EMPLOYEE` 默认只读授权范围；`AUDITOR` 可只读查询本 tenant 审计事件，不获得业务写权限。
- `GET /api/admin/audit-events` 支持 user/action/resource/outcome/time/limit 筛选，结果强制绑定当前 tenant；仅 `SYSTEM_ADMIN` / `AUDITOR` 可查询，越权查询返回 403 并记录拒绝审计。
- `GET /api/admin/retrieval-diagnostics/{requestId}` 仅允许 `SYSTEM_ADMIN` / `AUDITOR` 查询当前 tenant 的 Top-K rank、filename、locator、similarity、阈值和失败分类；不返回问题正文、Prompt、Chunk 正文或普通用户答案字段，越权查询返回 403 并记录拒绝审计。
- 旧单用户数据库通过 Flyway baseline 升级；默认演示用户幂等补齐 SYSTEM_ADMIN 与默认知识库 MANAGE 权限。

### Flutter

- Riverpod MVVM、dio/retrofit、json_serializable、file_picker。
- 「问答 / 知识库」两个 Tab。
- 聊天气泡、加载状态、答案卡、来源片段弹窗和失败重试。
- 文档上传、列表、处理状态轮询和删除。
- Android Debug 明文 HTTP 配置及固定的 AGP/Gradle/Kotlin 工具链。

### 本地编排

- Docker Compose 定义 pgvector 与 backend，并透传 RAG/AI 配置。
- 根目录、后端和 Flutter 启动说明。
- `sample_faq.md` 端到端联调样例。

## 当前验证证据（2026-09-21）

| 检查 | 结果 | 证据边界 |
|---|---|---|
| `flutter analyze --no-pub` | PASS | 静态分析通过 |
| `flutter test --no-pub --concurrency=1` | PASS | 仅一个 Widget smoke test，不覆盖网络和文件选择 |
| `docker compose config --quiet` | PASS | Compose 配置可解析，不代表容器已启动 |
| `./mvnw test` | PASS | 2026-09-21 本地 JDK 25.0.3 + Docker Desktop 4.91.0；Testcontainers 1.21.4 执行 98 个测试，0 failures/errors/skipped；Structured Output、预算路由、AskService、keyword-RRF 实验、Agent Tool、PostgreSQL/pgvector ACL 集成回归通过 |
| GitHub Actions CI | PASS | Run `35319672140`（CI #26）；backend-tests 实际执行 61 tests，0 failures/errors/skipped；compose-config 通过 |
| PostgreSQL + pgvector 运行 | PASS | PostgreSQL 16.15、pgvector 0.8.6、4 张业务表、HNSW cosine 索引 |
| LM Studio 模型 | PASS | Gemma 4 26B + Nomic Embedding，OpenAI-compatible server `1234` |
| 文档入库 | PASS | `sample_faq.md` 进入 `ready`，生成 2 个 Chunk |
| 资料内问题 | PASS | `found=true`，回答保修期限与申请流程，返回有效来源 |
| 资料外问题 | PASS | `found=false`、`sources=[]`；保留实际模型 Token 用量 |
| 文档删除 | PASS | API 返回成功，document/chunk 行清零，原始上传文件删除，再次检索拒答 |
| 旧库迁移 | PASS | 非空旧 schema 自动 baseline 为 V1，再执行 V2；Hibernate validate 与应用启动通过 |
| 空库迁移 | PASS | GitHub Actions run `35295919726` 顺序执行 V1-V5；PostgreSQL 16.15、`vector(768)`、默认授权、`audit_event` 和 `index_task` 建表通过 |
| ACL 隔离 | PASS | 无授权用户列表为空；USER、DEPARTMENT、ROLE 授权范围均有确定性测试；READ 用户删除返回 404；双 tenant 文档列表/详情互不可见 |
| 文档替换 | PASS | GitHub Actions run `35169350194` 验证版本化新文件、`contentVersion` 递增、旧文件保留和旧 Chunk 在新索引就绪前不被删除；单元测试覆盖同 checksum 幂等、越权拒绝、成功切换和解析失败回滚 |
| PostgreSQL/Testcontainers 集成测试 | 本地 PASS；CI 待复跑 | Testcontainers 1.21.4 已修复本机 Docker Engine 29 / Docker Desktop 4.91.0 探测兼容问题；本地 `./mvnw test` 已真实执行 13 个 PostgreSQL/pgvector 集成测试，全部通过且 0 skipped；GitHub Actions 仍需下一次运行验证同一依赖升级 |
| 持久化索引任务真实联调 | PASS | 2026-09-18，PostgreSQL 16.15 + LM Studio；上传一次成功 `SUCCEEDED/attempt=1/23125ms`，Provider 中断后自动重试成功 `attempt=2/32542ms`，连续失败后 `FAILED/attempt=3/93526ms`，人工重试后第 4 次成功，遗留 RUNNING 经后端重启恢复后第 2 次成功 |
| 问答观测 V6/V7 迁移 | PASS（本地） | PostgreSQL 16.15 从 V5 顺序升至 V7；12 个新增观测字段存在，历史记录 tenant/request/status 必填字段空值为 0，Hibernate schema validate 与应用启动通过；真实 HTTP 验证保留合法 `X-Request-Id` |
| 用户反馈 V8 | PASS | PostgreSQL 16.15 从 V7 升至 V8；真实 API 验证创建、修改、单问答唯一反馈、原因清理、非法 rating 400 和不存在/无权 requestId 404；GitHub Actions run `35300087644` 完成 V1-V8 空库迁移及相关回归测试 |
| 健康检查与运营指标 | PASS | 公开 health/liveness/readiness 只返回状态；匿名和普通已登录用户均不能读取 metrics/prometheus，仅 SYSTEM_ADMIN/AUDITOR 可读取；GitHub Actions run `35321425249` 验证问答/索引/反馈/ACL/估算成本指标、Prometheus histogram 和低基数标签边界 |
| 20 题 Golden Dataset 与离线 Runner | PASS（数据契约） | `python3 evaluation/run_eval.py`；`golden-v1` 共 20 题，`dataset_valid=true`，行为分布为 ANSWER 16、ACL_FILTERED_REFUSAL 2、REFUSE 2；尚未代表真实模型质量结果 |
| 答案质量扩展集与确定性评分 | PASS（数据契约） | `answer-quality-v1` 新增 12 题，覆盖产品规格、安装、保修、退换货、密码恢复、临时密码、资料外拒答和 ACL 拒答；`quality_rules` 支持答案点覆盖、grounded、引用精度和 fail-closed 拒答门槛；真实 API 结果另见答案质量 A/B 报告 |
| 答案质量扩展集真实 A/B | PASS（实验完成，默认关闭） | VECTOR/KEYWORD_RRF 均 12/12 HTTP、ACL leakage=0、拒答 4/4；答案质量门槛均 9/12，KEYWORD_RRF Recall@5 从 87.5% 提升到 100%，但平均 Token 从 1522.58 增至 1805.50，并出现 2 次 Structured Output 失败；详见 `evaluation/reports/answer-quality-v1-backend-ab-local-2026-09-21.md` |
| 20 题真实 API 评测 | 有证据；默认保持 Top-K=5/2400 | PostgreSQL 16.15 + LM Studio Gemma 4 26B/Nomic Embedding；修复 actor role fixture 后 Top-K=5/2400 为 16/20，Top-K=8/2400 为 15/20，4000 全局候选为 15/20，3200 复杂路由为 13/20；预算路由和 Top-K=8 均未达全局质量门槛，默认不切换；详见 `evaluation/reports/golden-v1-structured-output-local-2026-09-20.md` |
| 检索压力集真实 API 评测 | 基线 PASS；路由候选否决 | 默认历史压力集为 8/8；4000 候选为 8/8；3200 复杂路由本轮为 7/8，出现 1 个模型拒答字段不一致但 ACL leakage=0；V10 Recall@1/3/5=75%/91.67%/100% |
| 离线 keyword candidate benchmark | PASS（候选阶段） | `keyword-candidates-v1` 8 题；raw/normalized 两方案 Recall@5 均 100%、ACL leakage=0；normalized 上下文干扰率 74.07%，2/2 拒答题仍产生候选；不改线上检索链路 |
| Vector + keyword 融合模拟 | PASS（决策证据） | 同一压力集/ACL fixture 的文档级对照；keyword-weight-2 RRF 将 Recall@1 从向量 75% 提到 91.67%，Recall@5 仍为 100%，ACL leakage=0；进入受控线上 A/B 前，不启用默认链路 |
| ACL-aware 线上候选 A/B | PASS（候选层） | 2026-09-21 真实 `/api/ask` + 受保护诊断 + 数据库 ACL 可见 Chunk；向量/keyword-weight-2 RRF Recall@1=75%/91.67%、Recall@5 均 100%、ACL leakage=0；API 8/8 HTTP 成功但有 1 个拒答行为失败；融合候选尚未注入 AskService |
| 后端自有 keyword-RRF A/B | PASS（实验完成，默认关闭） | 2026-09-21 同一 8 题、认证和 ACL 下完成 VECTOR/KEYWORD_RRF 双捕获；两组 HTTP 8/8、行为/引用契约失败 0、ACL leakage=0；Recall@1=75%/91.67%、Recall@5 均 100%；keyword-RRF API P95=13815ms、VECTOR=16103ms；详见 `evaluation/reports/retrieval-backend-ab-v1-local-2026-09-21.md` |

本轮真实验证发现并修复：模型判断资料不足时曾错误返回 `found=true` 和无关来源；删除文档时曾残留原始文件。两条路径均已增加回归测试。

2026-09-20 真实联调发现默认 LM Studio 模型 ID 已过期：`text-embedding-nomic-embed-text` 会导致全部请求在 Embedding 阶段失败；已根据 `/v1/models` 核实并同步为 `text-embedding-nomic-embed-text-v1.5`，Chat 模型同步为 `google/gemma-4-26b-a4b-qat`。随后发现 Gemma 在 `AI_MAX_TOKENS=1200` 时会把预算消耗在 reasoning，导致可见答案为空或截断；默认配置已同步为 2400。最新完整压力集恢复为 8/8，STRESS-003 的两个目标文档仍完整命中，说明问题属于生成预算而不是召回。2026-09-21 的完整 4000 候选回归显示 Stress 仍为 8/8，但 Golden 为 15/20 且有 4 次结构化输出失败，暂不切换默认值。

## 已知缺口

- 本地和 GitHub Actions 均固定 JDK 25；GitHub Actions 已完成远端验证。
- 后端当前本地回归为 85 个 H2/非 Docker 测试和 13 个 PostgreSQL/Testcontainers 集成测试通过，共 98 个测试、0 skipped；GitHub Actions run `35321425249` 是依赖升级之前的 62 tests / 0 skipped 远端证据，Testcontainers 1.21.4 仍需下一次 CI 验证。
- 企业身份与 ACL schema、查询边界和最小管理 API 已建立；尚无前端管理页、批量导入、用户停用、部门停用和更细的知识库管理员权限矩阵。
- 当前只有 allow 型 ACL；尚未定义显式 deny、组织继承冲突和权限缓存失效策略。
- 文档已实现 checksum、内容版本、权限版本、停用、软删除、reindex、可回滚替换和持久化任务治理；尚无任务取消、优先级、分布式 Broker 或前端任务管理页，这些不属于当前最小闭环。
- 权限拒绝已有基础审计事件、tenant 范围内只读查询 API 和低基数拒绝计数；尚未提供保留策略、脱敏策略和评测记录。
- 应用自身默认不记录文档正文和 Prompt，但本地真实联调确认 LM Studio Developer Logs 会显示 Embedding 输入、Prompt 和模型输出；客户敏感资料上线前必须单独配置或替换 Provider 日志策略，不能把应用日志边界误认为全链路日志边界。
- 问答已有 requestId、总/分段耗时、结构化失败原因、Provider 超时分类、参数校验和统一 401/403 契约、用户反馈、健康/就绪探针、反馈率/ACL 拒绝/Token/估算成本指标及首版告警 guardrail；尚未用真实 7 天基线调优阈值。
- 已有 `golden-v1`（20 题）、`answer-quality-v1`（12 题）、`retrieval-stress-v1`（8 题）、真实 API 采集和聚合报告；答案质量扩展集已完成 VECTOR/KEYWORD_RRF 后端 A/B，结果支持区分召回缺口与生成稳定性问题，但质量门槛没有提升；受保护诊断采集器和 Recall@1/3/5 评分器已实现，并已用真实 HTTP 诊断样本形成排序边界报告；评测 fixture 现在会幂等补齐 actor roles；云端成本对照和独立的模型评分仍未完成。
- 没有生产级 BM25/全文 Hybrid Search 或 Reranker；PostgreSQL 默认 simple FTS 的中文切词预实验不足，`pg_trgm` 仅完成 disposable 查询验证。离线 keyword candidate、文档级 RRF 模拟、ACL-aware 线上候选 A/B 和默认关闭的后端 keyword-RRF 端到端 A/B 已完成；当前结果支持继续评估，但不批准默认启用，也没有成本结论。
- 应用内只读 Agent Tool Registry 已实现并完成单元测试和真实 HTTP 复核；尚无 MCP adapter、模型驱动 Agent loop 或写工具。
- 已加入确定性的复杂问题识别与预算路由开关，并增加模型拒答字段一致性 fail-closed；3200 开启实测未通过质量门槛，默认继续关闭。Top-K=8 可找回 RAG-014 但整体质量低于 Top-K=5，默认继续保持 5。
- 没有公开 Demo、架构图、部署 Runbook、Case Study 和英文说明。

## 下一步

RAG 基线、ACL、Structured Output 和应用内只读 Agent Tool 边界已经具备实现与真实 HTTP 证据；本地评测仍暴露召回排序和 Provider 吞吐问题，下一阶段先收敛证据，再决定是否进入 MCP：

1. 在 Docker-backed CI 修复后执行新增 PostgreSQL ACL、知识库过滤和工具 HTTP 路由集成测试。
2. 优先修复 `QUALITY-002` 召回缺口及 `QUALITY-004`/`QUALITY-006` 生成稳定性问题，再扩充跨文档和相似术语案例；当前质量 A/B 结果见 `evaluation/reports/answer-quality-v1-backend-ab-local-2026-09-21.md`。继续保持实验开关关闭，不直接把 Top-K、Hybrid Search 或 Reranker 切到默认。
3. 保持 2400 默认，优先修复召回缺口和本地模型稳定性；未通过质量、P95 和成本门槛前不打开 `AI_COMPLEX_ROUTING_ENABLED`。
4. 评测与 Docker-backed 集成门槛稳定后，再增加只读 MCP adapter；写操作继续不开放。

## 文档维护规则

- “代码存在”不等于“测试通过”。
- “测试通过”不等于“真实 Provider/数据库联调通过”。
- “本地联调通过”不等于“已部署或可公开访问”。
- 规划项不能写进“已实现”；历史结果未复现时要明确标记。
- 每次里程碑更新都记录日期、环境、命令、结果、限制和下一步。
