# PROGRESS

> Last evidence review: 2026-09-20
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
| 2. 身份、ACL、文档生命周期 | `in-progress` | Flyway V1-V10、ACL、最小管理 API、可回滚替换和持久化索引任务已落地；PostgreSQL CI、真实 Provider 重试和重启恢复已验证，管理 UI 等次要范围仍未完成 |
| 3. 结构化回答、审计、观测、反馈 | `verified` | requestId、总/分段耗时、稳定失败分类、超时/参数校验/401/403 契约、V6/V7 观测字段、V8 用户反馈、健康/就绪探针、反馈/ACL 拒绝/Token/估算成本指标、受保护检索诊断和 Structured Output Contract 已落地并通过单元/全量回归；真实 Provider 回归仍需用新契约重跑 |
| 4. 评测基线与检索压力集 | `verified` | `golden-v1` 两次 20/20、最新压力集 8/8 通过；答案点、引用、拒答均 100%，ACL leakage=0；V10 受保护诊断及 Recall@1/3/5 评分已落地 |
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
- Flyway V1-V10 管理 RAG、企业身份/ACL、幂等约束、审计、持久化索引任务、问答观测、用户反馈和检索诊断 schema；V9 修复旧库 `app_user.created_at` 默认值兼容性，V10 保存不含问题正文的 Top-K 检索快照；Hibernate 只做 schema validate。
- V2 已包含 tenant、department、role、user-role、knowledge base、membership 和 document ACL。
- 文档列表/详情和 Chunk 向量查询在 SQL 阶段执行 tenant + user/department/role + knowledge-base/document ACL 过滤。
- `/api/ask` 已执行 Structured Output Contract：模型只返回 `answer`、`found`、`grounded`、`sourceIndexes`；后端校验结构和引用编号，再生成 `sources`、`requestId`、`failureReason`、`timings`。
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

## 当前验证证据（2026-09-20）

| 检查 | 结果 | 证据边界 |
|---|---|---|
| `flutter analyze --no-pub` | PASS | 静态分析通过 |
| `flutter test --no-pub --concurrency=1` | PASS | 仅一个 Widget smoke test，不覆盖网络和文件选择 |
| `docker compose config --quiet` | PASS | Compose 配置可解析，不代表容器已启动 |
| `./mvnw test` | PASS | 2026-09-20 本地 JDK 25.0.3；76 个测试通过、13 个 PostgreSQL/Testcontainers 集成测试因本机 Docker socket 当前不可用而 skipped；Structured Output 与 Agent Tool 单元测试通过 |
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
| PostgreSQL/Testcontainers 集成测试 | 待下一次 CI | 既有 GitHub Actions run `35319672140` 验证 V1-V8 和 12 个集成测试；本轮新增 V9/V10 schema 断言尚未在 Docker-backed CI 复跑 |
| 持久化索引任务真实联调 | PASS | 2026-09-18，PostgreSQL 16.15 + LM Studio；上传一次成功 `SUCCEEDED/attempt=1/23125ms`，Provider 中断后自动重试成功 `attempt=2/32542ms`，连续失败后 `FAILED/attempt=3/93526ms`，人工重试后第 4 次成功，遗留 RUNNING 经后端重启恢复后第 2 次成功 |
| 问答观测 V6/V7 迁移 | PASS（本地） | PostgreSQL 16.15 从 V5 顺序升至 V7；12 个新增观测字段存在，历史记录 tenant/request/status 必填字段空值为 0，Hibernate schema validate 与应用启动通过；真实 HTTP 验证保留合法 `X-Request-Id` |
| 用户反馈 V8 | PASS | PostgreSQL 16.15 从 V7 升至 V8；真实 API 验证创建、修改、单问答唯一反馈、原因清理、非法 rating 400 和不存在/无权 requestId 404；GitHub Actions run `35300087644` 完成 V1-V8 空库迁移及相关回归测试 |
| 健康检查与运营指标 | PASS | 公开 health/liveness/readiness 只返回状态；匿名和普通已登录用户均不能读取 metrics/prometheus，仅 SYSTEM_ADMIN/AUDITOR 可读取；GitHub Actions run `35321425249` 验证问答/索引/反馈/ACL/估算成本指标、Prometheus histogram 和低基数标签边界 |
| 20 题 Golden Dataset 与离线 Runner | PASS（数据契约） | `python3 evaluation/run_eval.py`；`golden-v1` 共 20 题，`dataset_valid=true`，行为分布为 ANSWER 16、ACL_FILTERED_REFUSAL 2、REFUSE 2；尚未代表真实模型质量结果 |
| 20 题真实 API 评测 | PASS（本地两次回归） | PostgreSQL 16.15 + LM Studio Gemma 4 26B/Nomic Embedding；两次均 20/20 通过，答案点/引用/拒答/ACL 均 100%，ACL leakage=0；P50 6.34s/6.28s、P95 9.13s/9.17s；聚合报告见 `evaluation/reports/golden-v1-local-2026-09-18.md` |
| 检索压力集真实 API 评测 | PASS 8/8 | 2026-09-20 修正 LM Studio 模型 ID，并将 Gemma 本地默认 `AI_MAX_TOKENS` 从 1200 调整为 2400 后，最新完整运行 8/8；答案点/引用/拒答均 100%、ACL leakage=0；V10 Recall@1/3/5=75%/91.67%/100%；报告见 `evaluation/reports/retrieval-stress-v1-local-2026-09-20.md` |

本轮真实验证发现并修复：模型判断资料不足时曾错误返回 `found=true` 和无关来源；删除文档时曾残留原始文件。两条路径均已增加回归测试。

2026-09-20 真实联调发现默认 LM Studio 模型 ID 已过期：`text-embedding-nomic-embed-text` 会导致全部请求在 Embedding 阶段失败；已根据 `/v1/models` 核实并同步为 `text-embedding-nomic-embed-text-v1.5`，Chat 模型同步为 `google/gemma-4-26b-a4b-qat`。随后发现 Gemma 在 `AI_MAX_TOKENS=1200` 时会把预算消耗在 reasoning，导致可见答案为空或截断；默认配置已同步为 2400。最新完整压力集恢复为 8/8，STRESS-003 的两个目标文档仍完整命中，说明问题属于生成预算而不是召回。

## 已知缺口

- 本地和 GitHub Actions 均固定 JDK 25；GitHub Actions 已完成远端验证。
- 后端当前有 53 个稳定单元/上下文测试和 13 个 PostgreSQL/Testcontainers 集成测试；本轮本地为 53 passed + 13 skipped，GitHub Actions run `35321425249` 是文档 ACL API 之前的 62 tests / 0 skipped 远端证据；新增集成测试仍需下一次 CI 验证。
- 企业身份与 ACL schema、查询边界和最小管理 API 已建立；尚无前端管理页、批量导入、用户停用、部门停用和更细的知识库管理员权限矩阵。
- 当前只有 allow 型 ACL；尚未定义显式 deny、组织继承冲突和权限缓存失效策略。
- 文档已实现 checksum、内容版本、权限版本、停用、软删除、reindex、可回滚替换和持久化任务治理；尚无任务取消、优先级、分布式 Broker 或前端任务管理页，这些不属于当前最小闭环。
- 权限拒绝已有基础审计事件、tenant 范围内只读查询 API 和低基数拒绝计数；尚未提供保留策略、脱敏策略和评测记录。
- 应用自身默认不记录文档正文和 Prompt，但本地真实联调确认 LM Studio Developer Logs 会显示 Embedding 输入、Prompt 和模型输出；客户敏感资料上线前必须单独配置或替换 Provider 日志策略，不能把应用日志边界误认为全链路日志边界。
- 问答已有 requestId、总/分段耗时、结构化失败原因、Provider 超时分类、参数校验和统一 401/403 契约、用户反馈、健康/就绪探针、反馈率/ACL 拒绝/Token/估算成本指标及首版告警 guardrail；尚未用真实 7 天基线调优阈值。
- 已有 `golden-v1`（20 题）、`retrieval-stress-v1`（8 题）、真实 API 采集和聚合报告；受保护诊断采集器和 Recall@1/3/5 评分器已实现，并已用真实 HTTP 诊断样本形成排序边界报告；云端成本对照和独立的模型评分仍未完成。
- 没有 BM25/全文 Hybrid Search 或 Reranker；是否需要尚无评测依据。
- 没有 Agent、Tool Calling 或 MCP。
- 没有公开 Demo、架构图、部署 Runbook、Case Study 和英文说明。

## 下一步

RAG 基线、ACL、评测和本地 Provider 预算问题已经收口，Structured Output 和应用内只读 Agent Tool 边界已完成，下一阶段进入真实 HTTP/ACL 复核与 MCP 适配：

1. 在可用 Docker/CI 中复核新增 PostgreSQL ACL、知识库过滤和工具 HTTP 路由。
2. 用更新后的 `grounded` 契约重跑 Golden/Stress 真实 API 评测。
3. 在应用内 Tool Registry 稳定后，再增加只读 MCP adapter；写操作继续不开放。
4. 只有后续评测出现文档召回失败，才按 Chunk、Metadata、BM25、Hybrid Search、Reranker 的顺序推进。

## 文档维护规则

- “代码存在”不等于“测试通过”。
- “测试通过”不等于“真实 Provider/数据库联调通过”。
- “本地联调通过”不等于“已部署或可公开访问”。
- 规划项不能写进“已实现”；历史结果未复现时要明确标记。
- 每次里程碑更新都记录日期、环境、命令、结果、限制和下一步。
