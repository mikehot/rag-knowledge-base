# PROGRESS

> Last evidence review: 2026-09-24
> Source of truth for implemented and verified status. Planned capabilities live in `REQUIREMENTS.md` and `ROADMAP.md`.
> 2026-09-24 之前的逐条证据、命令和日期记录已原样迁至 [`docs/PROGRESS_HISTORY.md`](docs/PROGRESS_HISTORY.md)；详细数据以 `evaluation/reports/` 为准。本文件只保留当前状态、缺口和下一步。

## 状态定义

- `planned`：已有范围和验收条件，尚未实现。
- `in-progress`：已开始实现或验证，尚未通过完整门槛。
- `verified`：代码、自动检查、运行证据和文档满足门槛。
- `blocked`：存在明确外部依赖，已记录阻塞条件。

## 当前总览

| 里程碑 | 状态 | 当前结论 |
|---|---|---|
| 1. 可复现 RAG 基线 | `verified` | JDK 25（字节码目标 17）、PostgreSQL 16 + pgvector、LM Studio 上传/命中/拒答/删除闭环通过 |
| 2. 身份、ACL、文档生命周期 | `in-progress` | Flyway V1–V13、SQL 阶段 ACL 过滤、生命周期/任务治理已落地；管理员与非系统文档管理员的 USER/ROLE 授权/撤权有 Android 真机证据。2026-09-24 新增 PostgreSQL 集成测试覆盖文档级 DEPARTMENT 授权→列表/详情/向量检索可见、问答带引用→撤权→三处不可见、问答拒答无引用且模型未被调用；非管理者撤权 404 并记审计。缺：部门授权的一次真机抽测 |
| 3. 结构化回答、审计、观测、反馈 | `verified` | 结构化输出合同、fail-closed、requestId/分段耗时、审计、指标、反馈已落地；后端 Docker-backed 全量 138 tests 通过。一次性 `STRUCTURED_OUTPUT_INVALID` 三次复测未复现，根因未知 |
| 4. 评测基线 | 工具 `verified`；本地模型未过质量门，DeepSeek flash 通过 | Golden 16/20（Top-K=5/2400）、stress 7/8（Thinking-off）、answer-quality rubric-v2 最近 10/12；Q002/Q006 持续失败。口径见下文 |
| 5. 检索优化决策 | 暂停（阶段 B 在真实语料上恢复） | Keyword-RRF、diversity、adjacent 均未胜出，默认保持 VECTOR/Top-K=5。2026-09-24 chunking A/B（700/400/300/200）：300/60 首次通过 answer-quality 门槛（10/11/11），但 Golden 14/20、Stress 7/8 低于对照，按预设规则不采用，默认仍为 700/100。按 Markdown 标题切块已离线复放否决（全证据 14–17/30 vs 默认 24/30），未改代码；chunking 在此 fixture 上已饱和，下一候选为多语言 Embedding |
| 6. Agent Tool / MCP | `in-progress`（按计划暂停扩展） | 三个只读 Tool + 最小无状态 MCP adapter，本地 smoke 16/16；完整 transport/auth、第三方互操作、Agent loop 延后到 V0.1 门槛之后 |
| 7. 交付包 / FDE Case Study | `in-progress` | 架构、Discovery、Demo、Runbook、中英文 Case Study、备份恢复演练、连续 disposable 演示已完成。缺：质量门通过后的干净环境全流程演练、脱敏录屏 |

## 评分口径（必须随分数一起报告）

- **rubric-v1**：2026-09-23 `QUALITY-001` 同义词修订之前的 answer-quality 评分规则。历史分数：2026-09-21 A/B 10/12、11/12；2026-09-23 Thinking-off 9/12。
- **rubric-v2**：修订之后。VECTOR、Top-K=5、Thinking-off 下依次为 7/12、10/12、10/12、10/12（后两次为 adjacent A/B 的 VECTOR 组）。
- 2026-09-23 Top-K 5/8 A/B 报告未记录 rubric 版本，不作为基线。
- 不同 rubric、数据集或模型设置的分数不比较、不平均。Gemma 的 `Enable Thinking` 目前靠 LM Studio UI 手动切换，是评测可复现性的已知缺口。

## Provider 日志（已按范围决策收口，2026-09-24）

LM Studio 定位为本地开发/评测 Provider，只处理合成数据；已确认它会把部分 model I/O 持久化到本机日志，根目录已收紧为 `0700`。其轮转、保留、目录重建后权限不再调查。真实数据须改用通过 `docs/DEPLOYMENT_RUNBOOK.md` Provider 验收清单的 Provider。证据见 `evaluation/reports/provider-log-boundary-review-local-2026-09-24.md`。

## 已实现（代码静态核对）

### 后端

- Spring Boot 3.5.7、Maven wrapper、Dockerfile；本地与 CI 运行 JDK 25（`.java-version`），`pom.xml` 字节码目标 Java 17。
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
- Flyway V1-V12 管理 RAG、企业身份/ACL、幂等约束、审计、持久化索引任务、问答观测、用户反馈、检索诊断和检索模式 schema；V9 修复旧库 `app_user.created_at` 默认值兼容性，V10 保存不含问题正文的 Top-K 检索快照，V11 保存 VECTOR/KEYWORD_RRF 模式，V12 扩展 `ask_log` 约束以接受 gated VECTOR_DIVERSITY；Hibernate 只做 schema validate。
- V2 已包含 tenant、department、role、user-role、knowledge base、membership 和 document ACL。
- 文档列表/详情和 Chunk 向量查询在 SQL 阶段执行 tenant + user/department/role + knowledge-base/document ACL 过滤。
- `/api/ask` 已执行 Structured Output Contract：模型只返回 `answer`、`found`、`grounded`、`sourceIndexes`；OpenAI-compatible Provider 同时发送 `response_format=json_schema`；后端继续校验结构和引用编号，再生成 `sources`、`requestId`、`failureReason`、`timings`。
- `/api/agent/tools` 已建立应用自有只读 Tool Registry，只注册 `search_knowledge`、`list_documents`、`get_document_status`；单次最多 3 个调用，拒绝未知字段、越权知识库、越权文档和所有写操作。
- `POST /mcp` 已建立最小无状态 MCP `2026-07-28` 适配层，只暴露 `server/discover`、`tools/list`、`tools/call`；要求 JWT 和协议/路由 headers，复用 Agent Tool Registry，不开放 sessions、Tasks、Resources、Prompts、模型循环或写操作。
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
- 文档上传、列表、处理状态轮询、删除和停用/重建索引操作。
- 文档 ACL 列表/授权/撤权对话框与持久化索引任务状态/失败原因/安全重试面板；已通过 Android 16/API 36 合成设备验收（部门授权/撤权除外，见下文缺口）。
- Android Debug 明文 HTTP 配置及固定的 AGP/Gradle/Kotlin 工具链。

### 本地编排

- Docker Compose 定义 pgvector 与 backend，并透传 RAG/AI 配置。
- 根目录、后端和 Flutter 启动说明。
- `sample_faq.md` 端到端联调样例。

## 已知缺口

- **答案质量**：默认 700/100 下 FAQ 的 chunk#1 混合规格/安装/保修/退换货四节，导致 Q002/Q006 召回失败（2026-09-24 对照组 6 轮全部失败）。300/60 修复了这两题的召回，但切碎了其他答案和引用（Golden RAG-005/012/013/014、STRESS-003、Q004 引用），净效果持平。按固定大小打包无法同时兼顾；详见 `evaluation/reports/chunking-ab-local-2026-09-24.md`。
- **评测可复现性**：质量门已冻结于 `evaluation/README.md`（rubric-v2，连续 3 轮 ≥ 10/12）；每组前用探针确认 Thinking-off，但开关本身仍需在 LM Studio UI 手动设置。同配置两次会话间 Golden 波动 17→15，单轮 Golden/Stress 证据偏弱。
- **权限变更审计**：2026-09-26 起，文档 ACL、知识库成员、用户角色的授予与撤销（以及带角色创建用户）都写入 `ALLOW` 审计，内容为主体与权限，并与变更同事务提交；重复授权等失败操作不留记录，没有实际变化的角色分配也不记录。由集成测试 `permissionChangesWriteAllowAuditEventsInTheSameTransaction` 覆盖。
- **ACL**：部门授权/撤权与撤权后问答已由集成测试 `departmentDocumentAclGrantAndRevokeGateRetrievalAndCitedAnswers` 覆盖（模型被 mock，验证的是权限与检索链路，不是模型行为）；部门授权尚无真机抽测；只有 allow 型 ACL，无显式 deny、继承冲突和权限缓存失效策略；用户/部门停用、批量导入未做。
- **观测**：告警阈值尚无真实 7 天基线；审计事件无保留/脱敏策略。
- **Embedding 迁移**：Chunk 未记录生成它的 Embedding 模型；更换模型时，重建完成前旧向量仍参与检索，需要全量重建并暂停问答。
- **检索**：无生产级全文检索或 Reranker；现有实验均默认关闭，且无成本结论。
- **代码审查（2026-09-28）**：对后端做了聚焦安全、事务和并发的审查，10 项发现全部修复，后端全量 138 tests 通过。
  1. 索引改为在事务外计算 Embedding，提交时锁住文档行并重新检查；实体加 `@DynamicUpdate`；旧版本任务标记为“已被取代”。
  2. 删除文件改到事务提交后执行。
  3. 检索和文档列表要求所属知识库为 ACTIVE。
  4. 登录和 JWT 校验用户状态。
  5. 不再内置 JWT 密钥和管理员密码，本地默认值移到 `application-local.yml`；接口文档默认关闭。
  6. 问答不再全程持有事务。
  7. 每日限额改为原子计数（Flyway V14 `ask_daily_usage`）。
  8. 不存在的地址返回 404 而不是 500。
- **云端 Provider**：2026-09-26 实测 DeepSeek flash 和 v4-pro（Embedding 仍在本地）。增加 `AI_RESPONSE_FORMAT`（`json_schema` 或 `json_object`）以兼容不支持 `json_schema` 的服务。flash 通过 answer-quality 质量门（10/10/10），golden 17/20，stress 8/8。见 `evaluation/reports/cloud-provider-deepseek-local-2026-09-26.md`。尚未测试云端 Embedding。
- **MCP / Agent**：无完整 transport/auth conformance、第三方客户端互操作、模型驱动 loop 或写工具（按计划延后）。
- **交付**：无干净环境全流程演练记录、脱敏录屏、公开 Demo；备份恢复仅为一次本地人工演练，不代表生产 PITR/DR。
- **CI**：最近几次提交的 GitHub Actions 已通过；长期运行基线仍需积累。

## 下一步

按 `ROADMAP.md` v3.0（2026-09-24）执行。项目定位：AI 转型作品，同时面向求职（AI 应用工程师 / FDE）和接单客户。

**阶段 A：V0.1 可展示（约 1 周）**

1. A1 部门 ACL 授权/撤权，以及撤权后员工问答/引用的完整路径：自动化测试已完成（2026-09-24，124 tests 通过）；剩一次真机抽测，可并入 A5 录屏时一起完成。
2. A2 全新 clone 演练（已完成 2026-09-24）：README/DEMO 共 8 处缺口已修复，README 命令已在第二个全新 clone 中逐字执行通过；机器已有缓存时，从 clone 到首个带引用回答不到 1 分钟。见 `evaluation/reports/clean-clone-rehearsal-local-2026-09-24.md`。
   - 演练发现的缺陷已修复：reindex/replace 进行中或失败时，文档继续用上一版已提交的 Chunk 回答（检索不再按文档状态过滤）；有集成测试和实机复测，后端全量 125 tests 通过。
3. A3 V0.1 质量声明（已完成 2026-09-24）：唯一口径在 `PORTFOLIO.md`「评测结果与已知局限」。
4. A4 作品集刷新（已完成 2026-09-24）：重写 `PORTFOLIO.md`、README 开头、中英文 Case Study；`CODEX_PROMPT.md` 归档至 `docs/archive/`。
5. A5 3–5 分钟脱敏演示视频和截图。

**阶段 B：V0.2 有意义的评测**：真实规模公开语料和约 50 题数据集 → 重新建立基线 → 再按顺序做检索优化（含多语言 Embedding）→ 云端模型质量/延迟/成本对照 → 公开 Demo 决策。

**阶段 C（可选）**：真实 MCP 客户端演示；英文技术文章。

已完成的检索实验（质量门冻结、chunking A/B、按标题切块离线否决）见 `evaluation/reports/`。在现有 7 份合成文档上的检索调优已停止：效果已饱和，而且低于噪声。

继续不做：Fine-tuning、GraphRAG/Neo4j、复杂 Multi-Agent、Kubernetes、本地 GPU；不默认开启 Hybrid Search 或 Reranker。

## 文档维护规则

- “代码存在”不等于“测试通过”。
- “测试通过”不等于“真实 Provider/数据库联调通过”。
- “本地联调通过”不等于“已部署或可公开访问”。
- 规划项不能写进“已实现”；历史结果未复现时要明确标记。
- 每次里程碑更新都记录日期、环境、命令、结果、限制和下一步。
