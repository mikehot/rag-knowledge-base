# PROGRESS

> Last evidence review: 2026-09-23
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
| 2. 身份、ACL、文档生命周期 | `in-progress` | 已提交基线包含 Flyway V1-V12；V13 为默认关闭的检索实验审计值。管理员 Flutter ACL/索引任务主路径已有 Android 16/API 36 合成设备证据。2026-09-24 新文档 MANAGE 主体目录路径也完成隔离 `.verify` 真机验收：非系统管理员 EMPLOYEE 可见同租户 USER/DEPARTMENT/ROLE 候选；Flutter USER 与 Employee ROLE 的 READ 授权/撤权均改变合成员工文档列表可见性，部门候选已显示但未做 grant/revoke；无 MANAGE 员工仍 HTTP 404。撤权后员工问答未在本轮真机验证；先前一次 `found=false` 但生成失败为 `STRUCTURED_OUTPUT_INVALID`，不能算完整拒答门禁 |
| 3. 结构化回答、审计、观测、反馈 | `verified` | requestId、总/分段耗时、稳定失败分类、超时/参数校验/401/403 契约、V6/V7 观测字段、V8 用户反馈、健康/就绪探针、反馈/ACL 拒绝/Token/估算成本指标、受保护检索诊断和 Structured Output Contract 已落地；Provider 现区分缺失与显式未知 finish_reason，对显式非正常/未知终止 fail-closed，结构化失败日志不含 Prompt/问题/正文；2026-09-24 Docker-backed 全量回归 123 passed/0 skipped。此前 `STRUCTURED_OUTPUT_INVALID` 的三次定向复测均未复现，failure-only 元数据未触发，原根因仍未知 |
| 4. 评测基线与检索压力集 | `verified` | **评测工具/数据集门槛通过，不等于质量门通过**。golden-v1 有历史 20/20，当前记录的默认 Top-K=5 为 16/20；修订 rubric 的 answer-quality VECTOR 在最近配对两轮均 10/12；Thinking-off 的 stress 记录为 7/8。跨数据集/模型设置分开报告；ACL leakage 和 Schema 失败在最近相邻策略 API A/B 中均为 0 |
| 5. 检索优化决策 | `in-progress` | Keyword-RRF、diversity、adjacent 均未胜出整体重复质量门槛；默认继续 VECTOR/Top-K=5。新的 source-preserving 离线邻块候选无可用替换、答案点覆盖不变，暂停继续加组件 |
| 6. Agent Tool / MCP | `in-progress` | 三个只读 Agent Tool、闭合参数 schema、ACL/租户继承、空结果/超时/未知工具/预算/审计测试已落地；最小无状态 MCP 适配层和可重复的本地 HTTP smoke 已通过，完整 MCP transport/auth、第三方 SDK/client conformance 和 Agent loop 尚未完成 |
| 7. 交付包 / FDE Case Study | `in-progress` | 架构图、Discovery Brief、Demo、部署 Runbook 和中英文 Case Study 已完成；2026-09-24 disposable API 连续演示覆盖授权引用、拒答、权限拒绝、反馈、失败恢复，MCP 16/16；Android 16/API 36 真机通过管理员 ACL/索引任务 UI 验收，后续隔离 `.verify` 补测非系统 EMPLOYEE 文档管理员的 USER/DEPARTMENT/ROLE 候选加载及 USER/ROLE grant/revoke 列表可见性。LM Studio 合成探针确认部分模型输入/输出写入 server-log；68 个日志文件为 0644，日志根目录已收紧为 0700，并在用户正常退出/重新打开应用后复核仍为 0700；目录重建后的权限持久性、日志内容分类、脱敏及保留/轮换策略仍未闭环。沙箱 loopback 曾误报 Server stopped；宿主机权限复核确认 Desktop/API Server 运行。脱敏录屏、公开部署和真实客户运营基线仍未完成 |

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
- 新增文档 ACL 列表/授权/撤权对话框与持久化索引任务状态/失败原因/安全重试面板；目前已接入代码并通过 analyze/model tests，尚未做设备/API 端到端验收。
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
| `./mvnw test` | PASS | 2026-09-22 本地 JDK 25.0.3 + Docker Desktop 4.91.0；Testcontainers 1.21.4 执行 109 个测试，0 failures/errors/skipped；包含 V12 检索模式迁移、VECTOR_DIVERSITY 约束集成断言、Structured Output 重试、found=true 资料不足 fail-closed、预算路由、AskService、keyword-RRF/vector-diversity 实验、Agent Tool、MCP adapter、PostgreSQL/pgvector ACL 集成回归 |
| GitHub Actions CI | PASS | Run `35319672140`（CI #26）；backend-tests 实际执行 61 tests，0 failures/errors/skipped；compose-config 通过 |
| PostgreSQL + pgvector 运行 | PASS | PostgreSQL 16.15、pgvector 0.8.6、4 张业务表、HNSW cosine 索引 |
| LM Studio 模型 | PASS | Gemma 4 26B + Nomic Embedding，OpenAI-compatible server `1234` |
| Structured Output Provider 探针 | 未通过能力门禁 | 2026-09-22 无文档合成探针：Gemma 在 2400/3200 tokens 下均 `finish_reason=length` 且 content JSON 不可解析；Qwen 3.8 返回空 `message.content`，仅有 `reasoning_content`；详见 `evaluation/reports/structured-output-probe-v1-local-2026-09-22.md` |
| Q002 向量候选扩展诊断 | PASS（定位证据） | employee ACL 可见候选共 7 个；`sample_faq.md` 排名第 6、similarity=0.533425，Top-5 第 5 名为 0.536526，ACL leakage=0；确认是 Top-K 边界排序缺口，不改默认 Top-K |
| 向量候选扩展/多样性重排对照 | PASS（候选阶段，默认关闭） | Q002 通过跳过重复文档 Chunk 找回 `sample_faq.md`；但 8 题 stress 集 Recall 仍 100%、候选行为 6/8，干扰率从 60.00% 升至 76.67%，拒答候选仍 2/2，ACL leakage=0；不进入生产链路 |
| 文档入库 | PASS | `sample_faq.md` 进入 `ready`，生成 2 个 Chunk |
| 资料内问题 | PASS | `found=true`，回答保修期限与申请流程，返回有效来源 |
| 资料外问题 | PASS | `found=false`、`sources=[]`；保留实际模型 Token 用量 |
| 文档删除 | PASS | API 返回成功，document/chunk 行清零，原始上传文件删除，再次检索拒答 |
| 旧库迁移 | PASS | 非空旧 schema 自动 baseline 为 V1，再执行 V2；Hibernate validate 与应用启动通过 |
| 空库迁移 | PASS | GitHub Actions run `35295919726` 顺序执行 V1-V5；PostgreSQL 16.15、`vector(768)`、默认授权、`audit_event` 和 `index_task` 建表通过 |
| ACL 隔离 | PASS | 无授权用户列表为空；USER、DEPARTMENT、ROLE 授权范围均有确定性测试；READ 用户删除返回 404；双 tenant 文档列表/详情互不可见 |
| 文档替换 | PASS | GitHub Actions run `35169350194` 验证版本化新文件、`contentVersion` 递增、旧文件保留和旧 Chunk 在新索引就绪前不被删除；单元测试覆盖同 checksum 幂等、越权拒绝、成功切换和解析失败回滚 |
| PostgreSQL/Testcontainers 集成测试 | 本地 PASS；CI 待复跑 | Testcontainers 1.21.4 已修复本机 Docker Engine 29 / Docker Desktop 4.91.0 探测兼容问题；本地 `./mvnw test` 已真实执行 14 个 PostgreSQL/pgvector 集成测试，全部通过且 0 skipped；GitHub Actions 仍需下一次运行验证同一依赖升级 |
| 持久化索引任务真实联调 | PASS | 2026-09-18，PostgreSQL 16.15 + LM Studio；上传一次成功 `SUCCEEDED/attempt=1/23125ms`，Provider 中断后自动重试成功 `attempt=2/32542ms`，连续失败后 `FAILED/attempt=3/93526ms`，人工重试后第 4 次成功，遗留 RUNNING 经后端重启恢复后第 2 次成功 |
| 问答观测 V6/V7 迁移 | PASS（本地） | PostgreSQL 16.15 从 V5 顺序升至 V7；12 个新增观测字段存在，历史记录 tenant/request/status 必填字段空值为 0，Hibernate schema validate 与应用启动通过；真实 HTTP 验证保留合法 `X-Request-Id` |
| 用户反馈 V8 | PASS | PostgreSQL 16.15 从 V7 升至 V8；真实 API 验证创建、修改、单问答唯一反馈、原因清理、非法 rating 400 和不存在/无权 requestId 404；GitHub Actions run `35300087644` 完成 V1-V8 空库迁移及相关回归测试 |
| 健康检查与运营指标 | PASS | 公开 health/liveness/readiness 只返回状态；匿名和普通已登录用户均不能读取 metrics/prometheus，仅 SYSTEM_ADMIN/AUDITOR 可读取；GitHub Actions run `35321425249` 验证问答/索引/反馈/ACL/估算成本指标、Prometheus histogram 和低基数标签边界 |
| 20 题 Golden Dataset 与离线 Runner | PASS（数据契约） | `python3 evaluation/run_eval.py`；`golden-v1` 共 20 题，`dataset_valid=true`，行为分布为 ANSWER 16、ACL_FILTERED_REFUSAL 2、REFUSE 2；尚未代表真实模型质量结果 |
| 答案质量扩展集与确定性评分 | PASS（数据契约） | `answer-quality-v1` 新增 12 题，覆盖产品规格、安装、保修、退换货、密码恢复、临时密码、资料外拒答和 ACL 拒答；`quality_rules` 支持答案点覆盖、grounded、引用精度和 fail-closed 拒答门槛；真实 API 结果另见答案质量 A/B 报告 |
| 答案质量扩展集真实 A/B | PASS（实验完成，默认关闭） | 修正 `QUALITY-001` 误报并补充 Q002/Q005 合法自然表达后，初始 VECTOR/KEYWORD_RRF 均 10/12、12/12 HTTP、ACL leakage=0、拒答 4/4；Keyword-RRF Recall@5 从 87.5% 提升到 100%，但平均 Token 从 1522.58 增至 1805.50，并出现 2 次 Structured Output 失败；Prompt hardening 后补充捕获为 VECTOR 10/12、KEYWORD_RRF 11/12，但仍需重复稳定性证据；详见 `evaluation/reports/answer-quality-v1-backend-ab-local-2026-09-21.md` |
| 答案质量重复稳定性复测 | PASS（定向证据）；全量质量结论仍 `in-progress` | 已准备与 fixture 对齐的 `SUPPORT` 部门 `EMPLOYEE` disposable 凭据并完成 Q002/Q006 各 3 次 VECTOR/KEYWORD_RRF 捕获；两组均 6/6 HTTP 200、ACL leakage=0，Q002/Q006 在 VECTOR 下均稳定 `INSUFFICIENT_CONTEXT`，在 KEYWORD_RRF 下均稳定 `STRUCTURED_OUTPUT_INVALID`；详见答案质量 A/B 报告。该结果不支持打开默认 Hybrid Search |
| Structured Output 有界重试 | PASS（代码与回归） | 新增 `AI_STRUCTURED_OUTPUT_RETRIES`，默认 1；仅非法 JSON 触发一次修复提示重试，合并 Token/生成耗时，耗尽后仍 fail-closed；新增 `found=true` 但正文明确承认资料不足时的 fail-closed 保护；完整后端回归 100 tests、0 failures/errors/skipped；隔离真实捕获修复过一次 `QUALITY-004`，但未证明整体答案质量提升 |
| 多要点回答 Prompt hardening | `in-progress` | 针对 `QUALITY-006` 增加强制编号逐项回答提示，并保留 AskService Prompt 回归断言；新鲜 12 题捕获中 VECTOR 仍为 10/12、Q002/Q006 为召回缺口，KEYWORD_RRF 为 11/12 但 Q006 仍漏非质量问题运费；尚未证明答案完整性稳定提升 |
| 20 题真实 API 评测 | 有证据；默认保持 Top-K=5/2400 | PostgreSQL 16.15 + LM Studio Gemma 4 26B/Nomic Embedding；修复 actor role fixture 后 Top-K=5/2400 为 16/20，Top-K=8/2400 为 15/20，4000 全局候选为 15/20，3200 复杂路由为 13/20；预算路由和 Top-K=8 均未达全局质量门槛，默认不切换；详见 `evaluation/reports/golden-v1-structured-output-local-2026-09-20.md` |
| 检索压力集真实 API 评测 | 基线 PASS；路由候选否决 | 默认历史压力集为 8/8；4000 候选为 8/8；3200 复杂路由本轮为 7/8，出现 1 个模型拒答字段不一致但 ACL leakage=0；V10 Recall@1/3/5=75%/91.67%/100% |
| 离线 keyword candidate benchmark | PASS（候选阶段） | `keyword-candidates-v1` 8 题；raw/normalized 两方案 Recall@5 均 100%、ACL leakage=0；normalized 上下文干扰率 74.07%，2/2 拒答题仍产生候选；不改线上检索链路 |
| Vector + keyword 融合模拟 | PASS（决策证据） | 同一压力集/ACL fixture 的文档级对照；keyword-weight-2 RRF 将 Recall@1 从向量 75% 提到 91.67%，Recall@5 仍为 100%，ACL leakage=0；进入受控线上 A/B 前，不启用默认链路 |
| ACL-aware 线上候选 A/B | PASS（候选层） | 2026-09-21 真实 `/api/ask` + 受保护诊断 + 数据库 ACL 可见 Chunk；向量/keyword-weight-2 RRF Recall@1=75%/91.67%、Recall@5 均 100%、ACL leakage=0；API 8/8 HTTP 成功但有 1 个拒答行为失败；融合候选尚未注入 AskService |
| 后端自有 keyword-RRF A/B | PASS（实验完成，默认关闭） | 2026-09-21 同一 8 题、认证和 ACL 下完成 VECTOR/KEYWORD_RRF 双捕获；两组 HTTP 8/8、行为/引用契约失败 0、ACL leakage=0；Recall@1=75%/91.67%、Recall@5 均 100%；keyword-RRF API P95=13815ms、VECTOR=16103ms；详见 `evaluation/reports/retrieval-backend-ab-v1-local-2026-09-21.md` |
| Vector diversity 端到端答案质量 A/B | PASS（实验完成，默认关闭） | 2026-09-22 同一 12 题、认证、ACL 和本地服务完成 VECTOR/VECTOR_DIVERSITY 双捕获；迁移 V12 后两组 HTTP/API 12/12，质量门均 8/12、ACL leakage=0；VECTOR_DIVERSITY 引用覆盖 62.5% vs 75%、答案点覆盖 50% vs 64.58%、中位延迟约高 4.2%，不进入默认链路；详见 `evaluation/reports/vector-diversity-answer-quality-ab-local-2026-09-22.md` |
| 最小 MCP 适配层 | PASS（代码、单元回归和脚本化本地 HTTP smoke；第三方 SDK/client conformance 待完成） | 2026-09-22 新增无状态 `POST /mcp`，支持 `server/discover`、`tools/list`、`tools/call`；要求 `MCP-Protocol-Version=2026-07-28`、`Mcp-Method` 和工具调用 `Mcp-Name`，复用三只读 Tool、ACL、参数边界和审计；4 个 MCP adapter tests 通过，`evaluation/run_mcp_smoke.py` 的 16/16 本地检查通过，覆盖发现/列表/调用、header mismatch、身份参数覆盖拒绝和未认证 401；未声称完整 MCP conformance，详见 `evaluation/reports/mcp-readonly-smoke-local-2026-09-22.md` |
| FDE 交付材料 | PASS（文档边界） | 2026-09-22 新增 `docs/ARCHITECTURE.md`、`DISCOVERY_BRIEF.md`、`DEMO.md`、`DEPLOYMENT_RUNBOOK.md`、中文/英文 Case Study；内容与当前代码、评测和 MCP smoke 证据对齐，不把本地样例包装成生产或 ROI 结论 |
| 隔离 API Demo | PASS（一次性本地证据） | 2026-09-22 在独立 PostgreSQL/pgvector + 当前源码服务上完成上传、V12 迁移、索引 `READY/SUCCEEDED`、2 Chunk、授权回答/引用、反馈、fail-closed 拒答、无权限员工拒绝和 MCP 只读边界；详见 `evaluation/reports/demo-v0.1-disposable-local-2026-09-22.md`；尚不是 Flutter 设备或公开部署证据 |
| Flutter 客户端会话、回答与文档生命周期 | PASS（客户端解析与本地测试） | 2026-09-22 Flutter 已消费 `requestId`、`grounded`、`latencyMs`、`tokenUsage`、`failureReason`、分段耗时和来源；回答卡展示状态/耗时/Token/失败分类，并接入 `HELPFUL`/`NOT_HELPFUL` 反馈；新增显式登录、平台安全 token 存储、启动恢复和退出登录；知识库页已对接已有文档停用/重建索引接口，并保留服务端权限边界；`flutter analyze` 与 6 个 Flutter tests 通过 |
| Flutter Android 设备级演示 | PASS（本地 disposable 证据；范围分项记录） | 2026-09-23 Android 16/API 36 真机验证唯一临时 Markdown 上传到 `ready`；2026-09-24 以隔离 `.verify` 包验证管理员在 Flutter UI 授权/撤权，员工文档列表随之 5→6→5、受限文档不再出现；任务面板显示合成 reindex 失败 3/3 和安全重试，恢复 LM Studio 后 UI 重试到 `SUCCEEDED` 4/6，文档回到 `ready`。本轮员工列表可见性以只读 API 复核；同轮未重做员工提问/引用验收。完整范围见 `evaluation/reports/flutter-operations-acl-index-task-device-local-2026-09-24.md`；不是公开部署或生产证明 |

### 2026-09-23 本轮回归刷新

- 后端在 JDK 25.0.3、Docker Desktop/Testcontainers 环境执行 `mvn test`：116 tests，0 failures/errors/skipped。该结果来自当前本地工作区，不是本轮 GitHub Actions 运行。
- Flutter `dart run build_runner build`、`flutter analyze`、`flutter test --no-pub --concurrency=1` 均通过；新增 ACL/索引任务模型解析测试后共 8 项测试通过。`flutter build apk --debug` 成功产出本地 Debug APK；新增管理界面尚未安装到设备做端到端验收。构建输出提示现有 Gradle/AGP/Kotlin 版本未来会失去 Flutter 支持，本轮不扩展升级范围。
- Python：`python3 -m unittest discover -s evaluation -p 'test_*.py'`，9 tests 通过。直接以 root 模块名调用导致导入失败，按仓库测试目录发现方式重跑通过；没有代码故障。
- `docker compose config --quiet` 和 `git diff --check` 通过。Flutter Android Debug APK 构建结果待本轮完成后补记。
- 评测工具和回归门槛通过只证明可以执行和验证代码；当前质量数据仍不足以宣称 answer-quality release gate 通过。

### 2026-09-23 Flutter 运营界面后端 API 验收

- 使用新建 PostgreSQL 16.15/pgvector disposable 容器、隔离后端和 synthetic 管理员/员工：授权 employee 后，其只读 `search_knowledge` 返回 sample FAQ 来源；撤销 ACL 后，后续搜索及文档列表都不再包含该文档，观测 ACL leakage=0。
- 在同一 disposable 环境将 Embedding URL 指向不可用端点，任务到 `FAILED`、attempt 3 且有失败原因；改回正确 LM Studio Embedding 服务后，SYSTEM_ADMIN retry API 将任务推进至 `SUCCEEDED`、attempt 4。
- 后端/API acceptance PASS；Flutter Debug 构建通过，但独立 `.verify` 包安装被 Android 返回 `INSTALL_FAILED_USER_RESTRICTED`。未尝试绕过设备确认，也未覆盖原安装。故新 Flutter ACL/任务界面尚无真机验收证据。详见 `evaluation/reports/flutter-operations-acl-index-task-local-2026-09-23.md`。
- 测试使用独立端口 55485/8088；5432 开发数据库未连接或修改。服务、容器、凭据、manifest、测试脚本、上传目录和 ADB reverse 已清理。

### 2026-09-23 独立备份/恢复演练

- 从新建的无持久卷 PostgreSQL 16.15/pgvector 源容器导出 custom-format 数据库备份，并单独归档上传文件目录；恢复到另一全新 PostgreSQL 容器和独立上传目录，应用连接恢复目标后正常启动，Flyway 识别 V13 schema 已存在且无重新迁移。
- 恢复 API 核验：管理员可见 7 份合成文档；员工可见 5 份被授权文档，finance/HR 限制文档不可见，ACL leakage=0；7 条持久化索引任务均恢复为 `SUCCEEDED`。现有只读 MCP smoke 为 16/16。
- PASS 仅指一次本地数据库 dump + 文件归档的人工恢复路径；不证明生产备份调度、加密、异地副本、PITR、保留轮换、恢复时间目标或灾难恢复 SLA。源/目标容器、临时备份、上传副本和合成凭据均已清理，5432 开发数据库未连接或更改。详见 `evaluation/reports/backup-restore-rehearsal-local-2026-09-23.md`。

### 2026-09-24 Provider 日志隐私边界复核

- LM Studio 官方 `lms log stream` 文档明确可展示模型实际收到的格式化输入和返回输出；RAG 输入可能包含检索文档片段，因此必须按敏感内容处理。Runbook 已记录客户数据期间不得暴露/分享 model I/O 日志、仅以合成数据进行获准诊断，以及先核实本机版本日志存储/访问/保留/脱敏/关闭控制的要求。
- 2026-09-24 服务恢复后，在获准的 disposable 环境用 unique synthetic sentinel 对 `lms log stream --source model --filter input,output --json` 做内存过滤；输入和输出标记均可见，只打印匹配布尔值，原始流与回答未落盘。后续持久化探针只扫描新增字节，在一个 server-log 的 4,055 个新增字节内找到输入和输出标记，确认至少部分 model I/O 已持久化；未读旧日志或保留正文。只读检查 LM Studio 0.4.25（Build 1）General/Developer 设置及本地文件元数据：68 个 dated server-log 文件的 mtime 覆盖 2026-03-19 至 2026-09-24；所有枚举文件权限位 0644，server-logs/月目录 0755、用户主目录 0750。日志内容完整分类、实际其他账号可达性及保留/轮换策略仍 OPEN。详见 `evaluation/reports/provider-log-boundary-review-local-2026-09-24.md`。

### 2026-09-24 连续 disposable 交付演示

- 新建无持久卷 pgvector 环境，从空库迁移到 V13；7 个合成文档全部完成索引。管理员/员工/外部员工列表分别为 7/5/1，restricted finance/HR 文档对非管理员不可见，ACL leakage=0。
- 员工授权问答获得后端引用，资料外问题 `found=false` 且无来源；直接读取受限文档返回 403/404，Agent Tool 返回 `PERMISSION_DENIED`；反馈提交成功，MCP HTTP smoke 16/16。
- 诚实记录检索措辞敏感：初始“整机保修期是多久？”因 FAQ 未进入 Top-5 而 `INSUFFICIENT_CONTEXT`；更精确措辞使 FAQ 位列 rank 3 后 happy path 通过。这是演示成功，不是对 Top-5 质量缺口的修复或质量门通过。
- 单条重建索引任务在隔离无效 Embedding endpoint 下到 `FAILED`/attempt 3；恢复有效 endpoint 后由 SYSTEM_ADMIN retry 到 `SUCCEEDED`/attempt 4，文档回到 `ready`。未改 SQL 状态或 LM Studio 持久设置。
- Chat model load API 请求 8192 context 但回报 effective context 226304，Thinking/推理设置也未被作为受控实验变量；此轮只作功能演示，不纳入质量基线。数据库、服务、账号、文件、模型都已清理/卸载，5432 未触及。详见 `evaluation/reports/continuous-disposable-demo-local-2026-09-24.md`。

### 2026-09-24 Flutter 运营 UI 真机验收

- Android 16/API 36 上安装独立 `.verify` Debug 包，未覆盖日常 App。使用 loopback-only 8089 后端、55490 无持久卷 pgvector 数据库和 7 份 synthetic 文档。
- 管理员通过 Flutter ACL 弹窗给 `finance-policy.md` 授予员工 `READ`；员工只读文档列表由 5 项变为 6 项且出现该文档。随后从同一 UI 二次确认撤权；员工列表回到 5 项且不再出现该文档。此处验证 UI 操作与列表即时变化；本轮没有重新发起员工问答/引用。
- 管理员从设备文档菜单重建一份合成文档；临时不可用 Embedding endpoint 使任务进入 `FAILED`（3/3，记录耗时 90,385 ms），任务面板显示失败原因/尝试/耗时及“安全重试”。恢复 LM Studio endpoint 后，在 UI 点击安全重试，任务经 API 与 UI 双重读取为 `SUCCEEDED`（4/6，记录耗时 247,181 ms），文档 `ready`，7 份文档均 ready。
- 验收中发现 `PENDING` 且含上次错误的退避任务被标成“排队中”；已修正为“等待重试”，新建无错 `PENDING` 仍显示“排队中”。新增 4 个状态映射用例；`flutter analyze` 通过，`flutter test --no-pub --concurrency=1` 共 12 项通过，Debug `.verify` APK 构建并安装成功。此临时状态映射有单测覆盖，本轮设备最终状态是成功，未在更新后的 App 中再次观察瞬态等待重试标签。
- 未覆盖非系统管理员文档 MANAGE 主体目录路径。临时凭据、manifest、上传内容、后端、容器及 ADB reverse 已清理；没有截图、日志正文或真实文档写入仓库。详情见 `evaluation/reports/flutter-operations-acl-index-task-device-local-2026-09-24.md`。

本轮真实验证发现并修复：模型判断资料不足时曾错误返回 `found=true` 和无关来源；删除文档时曾残留原始文件。两条路径均已增加回归测试。

2026-09-20 真实联调发现默认 LM Studio 模型 ID 已过期：`text-embedding-nomic-embed-text` 会导致全部请求在 Embedding 阶段失败；已根据 `/v1/models` 核实并同步为 `text-embedding-nomic-embed-text-v1.5`，Chat 模型同步为 `google/gemma-4-26b-a4b-qat`。随后发现 Gemma 在 `AI_MAX_TOKENS=1200` 时会把预算消耗在 reasoning，导致可见答案为空或截断；默认配置已同步为 2400。最新完整压力集恢复为 8/8，STRESS-003 的两个目标文档仍完整命中，说明问题属于生成预算而不是召回。2026-09-21 的完整 4000 候选回归显示 Stress 仍为 8/8，但 Golden 为 15/20 且有 4 次结构化输出失败，暂不切换默认值。

2026-09-22 新增无文档 Structured Output Provider 探针，复核当前 Gemma 与已加载 Qwen 模型。Gemma 在简单和多要点问题上均以 `finish_reason=length` 结束且 content JSON 无法解析；Qwen 以 `stop` 结束但 `message.content` 为空、`reasoning_content` 存在，当前 Java Provider 不将后者当作答案。该证据确认 Q006 的剩余问题属于模型输出契约稳定性，不批准直接切换模型；必须先重复探针，再用同一 answer-quality/stress 门禁复核。

2026-09-23 加载此前未加载的默认 Gemma 后复测：thinking 开启时首次两条合成探针通过、随后重复三轮六条请求均耗尽 2400/3200 completion-token 并返回无效 JSON（2/8）。检查 LM Studio UI 确认 `Enable Thinking=on`；关闭后通过本机 API 重跑三轮，6/6 均 HTTP 200、`finish_reason=stop` 且通过严格 JSON 合同（25–140 completion tokens）。此前 shell 失败由本机回环网络沙箱限制导致，不是 LM Studio 服务故障。后续在 thinking 关闭下完成隔离端到端评测：Golden 16/20、answer-quality 9/12、stress 7/8，ACL 泄漏和 Schema 失败均为 0；再完成失败题 chunk 证据诊断与完整质量集 Top-K 5/8 双轮对照，Top-K 8 的 rubric 通过平均与 Top-K 5 相同、Token 平均约高 9.4%，默认保持 5。LM Studio 原开关已恢复 on；详见 `evaluation/reports/structured-output-gemma-recheck-local-2026-09-23.md`、`gemma-thinking-off-end-to-end-local-2026-09-23.md` 和 `chunk-evidence-topk-ab-local-2026-09-23.md`。

2026-09-23 对 `QUALITY-001` 做本机定向生成/评分器复核：Thinking-off、同一完整 FAQ 证据下，当前 Prompt 与“问号分隔子问题也需逐项回答”的变体交错各 3 次，共 6/6 严格 JSON 有效，均覆盖电池类型和续航，但现有低电量提醒 `match_any` 均未命中。另 3 次简化同提示调用用宽松词面代理检查，3/3 同时提及低电量与 App/应用通知或提醒；额外一次受控输出命中“通过 App 推送”。因此当前材料支持“答案表达自然但评分词不匹配”的解释，不支持调整正式 Prompt；在 `QUALITY-001` 加入经实测的同义短语并补离线评分回归。该结果仅是单题固定 FAQ 上下文直调，不是 Top-5 `/api/ask` 复测；answer-quality 9/12 仍是未修订 rubric 的历史端到端记录，完整集重跑后才能更新质量门结论。详见 `evaluation/reports/quality-001-rubric-diagnostic-local-2026-09-23.md`。LM Studio Thinking 已恢复为原来的 on。

随后在同一 Thinking-off 条件下用修订后的评分集，对新建 disposable PostgreSQL fixture 完成一次完整 12 题认证 `/api/ask` 运行（vector、Top-K=5）：HTTP 200 为 12/12，Schema 失败 0、ACL 泄漏 0；修订 rubric 总通过 7/12、可回答题 3/8、答案点覆盖 61.46%、引用覆盖/正确率均 75%、拒答正确率 100%。失败为 QUALITY-001/002/003/005/006；Q001 有来源但仍漏低电量提醒点，Q002/Q006 未检索到目标来源并输出 grounded=false。平均 API 延迟 1521ms、中位数 1251ms、平均 Token 1582。此单轮不同于历史未修订 rubric 的 9/12，不能据此改写历史基线；同义词修订未解决实际 Q001。对照已有重复 chunk/API 证据，Q002/Q006 属于 Top-K 边界召回缺口；Q001 属于回答完整性问题。Q003/Q005 分别覆盖 3/4、1/2 答案点且引用目标来源，但原始回答已按隐私最小化策略删除，当前只能判为答案点/精确词项未覆盖，不能确认是语义漏答还是自然改写。下一步只窄采集 Q003/Q005 并在内存中逐项核对，未经实测不扩充 rubric；随后再重复完整 12 题门禁，暂不改 Prompt、Top-K 或检索默认值。LM Studio Thinking 已恢复 on。汇总见 `evaluation/reports/quality-001-rubric-diagnostic-local-2026-09-23.md`。

随后针对 Q003/Q005 在新建无持久卷 PostgreSQL fixture 中各做三轮认证 `/api/ask` 定向复测（VECTOR、Top-K=5、Thinking-off）：6/6 HTTP/API 成功、Schema 失败 0、ACL 泄漏 0、目标来源引用 6/6、答案点全覆盖 6/6。六条回答均使用现有评分词覆盖保修排除项（含私自拆解）及七天无理由退货与两项条件；没有证据支持增加别名。上一轮完整集的两题失败在定向复测中未复现，可能与上下文组合或模型生成波动有关，但旧回答及精确 Top-5 上下文已清理，无法做逐条对照。LM Studio Thinking 已恢复 on，临时服务、凭据、数据和上传文件已清理。下一步保持 rubric 与检索配置不变，再跑一次完整修订 12 题门禁判断总体和 Q001/Q002/Q006 是否复现；报告更新于 `evaluation/reports/quality-001-rubric-diagnostic-local-2026-09-23.md`。

2026-09-23 第二次完整修订 rubric 的 12 题认证 `/api/ask` 复测在全新 disposable PostgreSQL/pgvector fixture 完成（VECTOR、Top-K=5、Thinking-off）：HTTP 200 12/12，质量门 10/12（83.33%），可回答题 6/8，答案点覆盖 75%，引用覆盖/正确率 75%，拒答正确率 100%，Schema 失败 0、ACL 泄漏 0。只有 Q002/Q006 失败，均为 `found=false`、`grounded=false`、无来源、`INSUFFICIENT_CONTEXT`。Q001/Q003/Q005 本轮通过；对照前一轮完整集 7/12（Q001/003/005 失败），后面三题存在跨轮波动，而 Q002/Q006 连续两轮失败；与先前受 ACL 限制候选 rank 7/6 的证据一致，但仍需在当前 fixture 再取受保护诊断确认。平均 API latency 1479ms、中位数 1216ms、平均 Token 1581。旧 9/12 采用较早 rubric，不与修订后的 7/12、10/12 直接比较，也不因单轮 10/12 宣布质量门稳定。Thinking 已恢复 on，临时服务、凭据、捕获和上传已清理；汇总见 `evaluation/reports/quality-001-rubric-diagnostic-local-2026-09-23.md`。

2026-09-22 对 `QUALITY-002` 做了 ACL-aware 向量候选扩展诊断：在 50 候选上实际只有 7 个可见 Chunk，`sample_faq.md` 位于 rank 6，正好落在生产 Top-K=5 之后；rank 5/6 相似度差约 0.0031。该证据确认召回缺口是窄边界排序问题，但不支持全局提升 Top-K；下一步只做离线候选扩展/重排对照。

随后完成 8 题 stress 集的离线文档多样性对照：多样性策略未提高目标候选 Recall 或候选行为通过数，反而增加 answerable 上下文干扰，且拒答题仍产生候选。随后完成 12 题 answer-quality 端到端 A/B；迁移 V12 修复实验模式审计约束后，VECTOR_DIVERSITY 仍未提高质量门或答案点覆盖，继续保持默认关闭。Q002 的局部召回改善不足以支持运行时重排；下一步回到模型输出稳定性、诊断和 CI 集成门槛。

## 已知缺口

- 本地和 GitHub Actions 均固定 JDK 25；GitHub Actions 已完成远端验证。
- 2026-09-24 较早一轮后端完整回归 119 项通过、0 skipped，其中新增 PostgreSQL 测试覆盖非系统管理员的文档 MANAGE 主体目录查询；本轮增加 Provider 终止元数据与 fail-closed 边界后重跑为 123 项通过、0 skipped。CI 新增读取 Surefire XML 的硬门禁，本轮尚未由 GitHub Actions 远端执行。此前 GitHub Actions run `35698781377` 对旧提交通过 `backend-tests`、`compose-config` 和 `flutter-tests`，长期运行基线仍需持续积累。
- 企业身份与 ACL schema、查询边界、最小管理 API 和首版 Flutter 管理界面已建立；管理员 READ 授权/撤权有设备证据，文档 MANAGE 候选目录有后端/API 证据但未做设备点验。批量导入、用户停用、部门停用和更细的知识库管理员权限矩阵仍未完成。
- 当前只有 allow 型 ACL；尚未定义显式 deny、组织继承冲突和权限缓存失效策略。
- 文档已实现 checksum、内容版本、权限版本、停用、软删除、reindex、可回滚替换和持久化任务治理；首版 Flutter 索引任务界面已通过合成设备的失败与安全重试验收。尚无任务取消、优先级或分布式 Broker；批量任务管理不属于当前最小闭环。
- 权限拒绝已有基础审计事件、tenant 范围内只读查询 API 和低基数拒绝计数；尚未提供保留策略、脱敏策略和评测记录。
- 应用自身默认不记录文档正文和 Prompt；本机 LM Studio 合成持久化探针确认输入、输出标记写入一个新增 server-log 文件字节。当前配置至少会持久化部分 model I/O；日志根目录为 owner-only `0700`，递归元数据复核仍见 68 个 `0644` 文件、月目录 `0755`，合计约 41.8 MB；根目录权限可阻断普通路径下其他账号穿透读取。经用户批准正常退出后，用户重新打开 LM Studio；截图、桌面状态及宿主机权限下 `lms server status`/`GET /v1/models` 均确认应用和 API Server 正常，后者当时有 5 个已加载模型。沙箱内的 loopback 检查曾误报服务未运行。成功重新打开后日志根目录仍为 `0700`，故跨正常退出/重开权限持久性已验证；目录重建后的持久性仍未知。宿主机深度代码签名校验报告 `.webpack` 中存在新增 sealed resources，但这与当前 UI/API 正常运行并存，尚未归因，不据此认定 App 故障或重装。`/Users` 下现有普通账号家目录中只发现当前账号属于 `staff`，但 Directory Services 全量枚举失败；完整内容分类、脱敏、保留/轮换和关闭策略仍未知。系统 `newsyslog` 未发现 LM Studio 专属规则，但不排除应用内轮转。敏感客户资料上线前仍必须评估并接受 Provider 日志控制，不能把应用日志边界误认为全链路日志边界。
- 问答已有 requestId、总/分段耗时、结构化失败原因、Provider 超时分类、参数校验和统一 401/403 契约、用户反馈、健康/就绪探针、反馈率/ACL 拒绝/Token/估算成本指标及首版告警 guardrail；尚未用真实 7 天基线调优阈值。
- 已有 `golden-v1`（20 题）、`answer-quality-v1`（12 题）、`retrieval-stress-v1`（8 题）、真实 API 采集和聚合报告；答案质量扩展集已完成 VECTOR/KEYWORD_RRF 后端 A/B；Gemma thinking 关闭时的历史 disposable 端到端复测为 Golden 16/20、answer-quality 9/12、stress 7/8，结构化失败和 ACL 泄漏均为 0；stress 检索诊断 8/8、Recall@5=100%。新增 chunk 级指定来源词项证据诊断（报告不落文档正文），7 个失败题 Top-5 evidence-point coverage 28.57%、Top-8/10 为 100%，但 Top-8/10 每题平均多带 6 个无明确答案点的 chunk。完整质量集 Top-K=5/8 各运行两轮，rubric 通过均值均为 10/12；Top-8 词项答案点覆盖/引用较高但单轮波动且平均 Token +9.4%，默认仍为 5。修订 `QUALITY-001` 的同义词并补离线回归后，修订 rubric 的单次 Top-K=5 API 捕获为 7/12（answerable 3/8），失败 Q001/002/003/005/006；与旧版 9/12 口径不同且尚未重复，不作为新稳定基线。详见 `evaluation/reports/chunk-evidence-topk-ab-local-2026-09-23.md` 和 `evaluation/reports/quality-001-rubric-diagnostic-local-2026-09-23.md`。云端成本对照和独立模型评分仍未完成。
- 没有生产级 BM25/全文 Hybrid Search 或 Reranker；PostgreSQL 默认 simple FTS 的中文切词预实验不足，`pg_trgm` 仅完成 disposable 查询验证。离线 keyword candidate、文档级 RRF 模拟、ACL-aware 线上候选 A/B、默认关闭的后端 keyword-RRF 端到端 A/B 和默认关闭的 vector-diversity 端到端 A/B 已完成；当前结果不批准默认启用，也没有成本结论。
- 应用内只读 Agent Tool Registry 和最小无状态 MCP adapter 已实现并完成单元回归；`evaluation/run_mcp_smoke.py` 已提供可重复的本地 HTTP 边界检查；尚无完整 MCP transport/auth conformance、第三方 SDK/client 互操作证据、模型驱动 Agent loop 或写工具。
- Flutter 问答客户端解析并展示后端回答契约、来源、失败分类、耗时/Token，并可提交反馈；已有独立登录、安全 token 存储和会话恢复。管理员 ACL 与最小索引任务 UI 已通过 Android 16/API 36 合成设备验收；非系统文档 MANAGE 用户的 USER/DEPARTMENT/ROLE 主体候选已设备点验；USER 与 ROLE 的授权/撤权及员工列表可见性回归通过。部门 grant/revoke、撤权后员工问答/引用未覆盖；停用文档恢复和批量任务管理未完成。既有 Flutter CI run `35698781377` 远端通过；本轮 `flutter analyze`、12 项 Flutter 测试和 Debug `.verify` APK 构建通过。尚无脱敏录屏或公开 Demo。
- 已加入确定性的复杂问题识别与预算路由开关，并增加模型拒答字段一致性 fail-closed；3200 开启实测未通过质量门槛，默认继续关闭。Top-K=8 在失败题离线词项覆盖上优于 5，但完整 answer-quality 两轮未稳定提高 rubric 通过均值且 Token 更高，默认继续保持 5。
- Gemma 在 LM Studio `Enable Thinking=off` 时通过了 6/6 独立 Structured Output 合同探针；端到端三集合复测中结构化失败=0、ACL 泄漏=0、stress Recall@5=100%，但 Golden 16/20、answer-quality 9/12、stress 7/8，答案质量门未通过。本地设置尚未持久化/自动化，继续保持 fail-closed，不要放宽 JSON 解析，也不要把 `reasoning_content` 当作 `message.content` 替代。
- 第一版架构图、Discovery Brief、Demo、部署 Runbook 和中英文 Case Study 已完成；已有一次隔离 API Demo 记录和 Android 设备截图；尚无录屏、公开可访问 Demo、真实客户生产部署和长期运营基线。

## 下一步

截至 2026-09-24，V0.1 的实现主干、管理员运营 UI 和非系统文档 MANAGE 主体目录路径已有隔离真机证据；答案质量门、撤权后问答结构化失败的真实根因、Provider 日志治理及对外交付证据仍未闭环。优先顺序：

1. **本轮状态收敛（已完成）**：计划开始时盘点的 24 个既有本地变更已核对用途；保留 answer-quality rubric/测试/报告；V13 与 `VECTOR_ADJACENT` 只作为默认关闭的实验，不推广默认检索策略。核心 37 文件由 `a7d7568` 提交，API/备份恢复验收文档由 `e6919a7` 提交，本轮 ACL 主体目录、演示/日志证据与路线更新由 `fadcd29` 提交，均已推送。
2. **检索候选窄诊断**：已新增“只替换同文档冗余 Chunk”的离线候选比较。当前 12 题里它没有做出任何替换，答案点覆盖仍为 75%（6/8），不值得做在线 API A/B；Q002/Q006 仍是已知缺口。除非有新的 source-preserving 候选假设，不继续堆 Hybrid/Reranker/重排。
3. **Flutter 最小运营 UI（管理员及非系统管理员主体目录主路径通过）**：管理员 Android 真机 `.verify` 已验证 ACL 授权/撤权及索引安全重试。非系统文档 MANAGE 用户的 Flutter 主体目录路径已在 Android 16/API 36 `.verify` 上点验：USER/DEPARTMENT/ROLE 候选显示；USER 与 ROLE READ 授权、撤权后合成员工列表可见性按预期变化；无 MANAGE 员工的主体目录请求仍 404。部门 grant/revoke 尚未点验。撤权后员工问答的合成 API 回放已另行 3 次验证安全拒答、无引用/ACL 泄漏；先前一次性 `STRUCTURED_OUTPUT_INVALID` 未在本轮复现，根因仍未知。详见 `evaluation/reports/document-acl-principal-directory-local-2026-09-24.md` 与 `evaluation/reports/structured-output-termination-diagnostics-local-2026-09-24.md`。
4. **撤权后结构化失败定向复现（已执行，本轮未复现）**：对一次性合成 FAQ 撤销员工唯一 `READ` 授权后，以固定 Gemma/Thinking-off、VECTOR Top-K=5 条件重复提问 3 次；均得到 `found=false`、`INSUFFICIENT_CONTEXT`、无来源，检索命中和引用均无撤权 FAQ。没有触发 `STRUCTURED_OUTPUT_INVALID`，因此 failure-only `finish_reason` 元数据本轮不可得；先前一次性失败的原因仍未知，不据此猜测。将来若失败再次出现，再使用已增加的脱敏元数据分流；当前不继续扩大检索或放宽合同。详见 `evaluation/reports/structured-output-termination-diagnostics-local-2026-09-24.md`。
5. **当前下一步：完成 Provider 日志治理门槛，再做干净交付演练**：合成探针已确认本机 LM Studio 将输入、输出标记追加到本地 server-log；只扫描新增字节且未保留原文。日志根目录已收紧为 `0700`，文件仍 `0644`；需在 LM Studio 重启/目录重建后复核权限，并继续确认应用内轮转/保留、日志内容类别、脱敏与关闭控制。剩余控制未核实前只用合成资料。随后从 disposable 环境重跑部署、恢复与演示闭环，并保留问法 Top-5 召回敏感性的限制说明。
6. **作品集收尾（文档本轮同步）**：中英文 Case Study 和 Demo 讲稿已纳入 2026-09-24 连续演示、Provider 日志与 Flutter 运营 UI 真机证据；脱敏录屏仍待日志边界确认后再制作。是否公开部署仍单独决策，不宣称未测的 ROI 或生产 SLA。

近期完整证据和离线 source-preserving 结果见 `evaluation/reports/adjacent-chunk-selection-answer-quality-local-2026-09-23.md`。截至 `fadcd29`，本轮 Provider 日志 sentinel、连续演示报告，以及 Runbook、Demo、Case Study、路线/进度更新均已提交并推送。

### 2026-09-24 非系统文档管理员 Flutter ACL 真机补充验收

- Android 16/API 36 隔离 `.verify` 包中，确认 EMPLOYEE 角色的文档 MANAGE 用户可加载同租户 USER、DEPARTMENT、ROLE 候选；该账号不是 SYSTEM_ADMIN。部门候选在 UI 显示，但只完成候选加载验证。
- 通过 UI 授予合成员工 USER READ 后，员工 `GET /api/documents` 可见目标文档；撤权后不再可见，且其文档级 principal 查询返回 404。通过 UI 授予 Employee ROLE READ 后员工同样可见，撤销该角色授权后再次不可见。
- 临时数据库、服务、ADB reverse、UI hierarchy 文件和内存凭据均已清理；员工问答/引用未测试，本证据不改变 `STRUCTURED_OUTPUT_INVALID` 与答案质量门状态。详情见 `evaluation/reports/document-acl-principal-directory-local-2026-09-24.md`。

### 2026-09-24 Structured Output 终止原因诊断补强

- 现有撤权后 `/api/ask` 记录只能证明请求在一次受限重试后以 `STRUCTURED_OUTPUT_INVALID` 结束，不能区分 token 上限中断、正常停止但 JSON/字段无效或其他失败；原始答案未持久化，因此不推测根因。
- OpenAI-compatible Provider 现在提取归一化 `finish_reason` 与 completion tokens。Structured Output 校验失败仅产生请求 ID、attempt、finish reason、completion token 数、输出字符数的 warning，不记录问题、Prompt、模型输出、文档正文或异常文本。
- `length`、`content_filter`、`tool_calls` 及显式未识别结束值即使文本碰巧是完整 JSON，也 fail-closed 为既有 `STRUCTURED_OUTPUT_INVALID`；缺失/空结束值保留旧 Provider 兼容。新增回归覆盖截断但语法完整 JSON和显式未知值。
- Docker-backed `./mvnw -B test`：123 passed，0 failures/errors/skipped。Flutter `analyze` 无问题，13 项 widget/unit tests 通过；ACL 对话框格式检查无改动。随后在 disposable API fixture、Gemma Thinking-off 条件下将员工撤权问答重放 3 次：均为 `INSUFFICIENT_CONTEXT`，结构化失败 0、ACL 泄漏 0；failure-only 日志未触发，原先一次性失败的根因仍未知。临时服务、数据库、模型与凭据已清理/恢复；生产/质量门状态不变。报告：`evaluation/reports/structured-output-termination-diagnostics-local-2026-09-24.md`。

### 2026-09-23：QUALITY-002 / QUALITY-006 新鲜隔离复核

- 新建无持久卷 PostgreSQL/pgvector fixture，仅重跑两题认证 `/api/ask`（VECTOR、Top-K=5、Thinking-off）：HTTP/API 200 为 2/2、Schema 失败 0、ACL 泄漏 0；但两题本轮 `failureReason=STRUCTURED_OUTPUT_INVALID`，与之前两次完整集运行的 `INSUFFICIENT_CONTEXT` 不同，分类具有跨轮波动。
- 当前受保护 Top-5 均未包含 `sample_faq.md`。ACL 过滤后的可见候选各 7 个：Q002 答案点 chunk rank 7（similarity 0.526589；Top-5 第 5 名 0.536526）；Q006 答案点 chunk rank 6（similarity 0.515066）。Q006 的 FAQ chunk#2 虽在 rank 3，却不含本题声明的答案点。
- 元数据/词项证据显示 Top-5 答案点覆盖分别为 0/2、0/3；将已饱和的 7 个可见候选纳入离线 context 后为 2/2、3/3。但每题 7 个 chunk 中仍有 6 个没有标注答案点、覆盖 4 个非证据文档；这是词项上下文证据，不等同语义质量提升。
- 未改生产代码和检索参数。临时数据库、服务、账号、上传资料与原始回答均已清理。Mac 解锁后复核 LM Studio UI，`Enable Thinking=on`，与原状态一致，无需切换。后续结论由下方完整 12 题相邻 Chunk 离线候选对照更新；运行时采用仍需独立的重复 API A/B。

### 2026-09-23：完整 answer-quality 相邻 Chunk 离线候选对照

- 在新建无持久卷 PostgreSQL/pgvector fixture 中为完整 12 题建立 ACL fixture，采集每题最多 50 个 ACL-filtered vector 候选。对比基线 Top-K=5 与相同 5 槽位的相邻 Chunk 替换：先取 Top-5；只看排名、文件名与 `chunk#N` locator，若 Top-5 命中文档的相邻 Chunk 落在 K+2 内，则允许以该 Chunk 替换另一文档的最低排名项，每题最多替换一次。gold answer labels 仅用于选择结束后的评分，不参与 selector。
- 8 个可回答题中，平均精确答案点词项覆盖 75.00%→87.50%，全答案点覆盖 6/8→7/8；非证据 Chunk 均值 4.25→4.00，非证据文档均值 2.875→2.25；off-source 词项命中 0，ACL leakage 0。Q006 的相邻 Chunk 将覆盖从 0/3 提至 3/3；Q002 目标文档没进 Top-5，邻块策略无效。K=8 下两种策略本就都是 8/8，平均仍有 5.875 个无证据点 Chunk。
- 该结果是单次确定性候选/词项诊断；未调用 chat model，不能证明答案质量、引用/拒答、结构化稳定性、Token 或延迟收益。线上 Top-K=5、VECTOR 和默认配置均未改变；不据此启用 Hybrid/Reranker。隔离数据库、后端、凭据、manifest、候选文件和上传内容均已清理。后续只有先做 feature-flagged 端到端重复配对 A/B 并通过全量质量与 ACL/运营门禁，才考虑运行时集成。详见 `evaluation/reports/adjacent-chunk-selection-answer-quality-local-2026-09-23.md`。

### 2026-09-23：同文档冗余 Chunk 的 source-preserving 离线比较

- 基于新建无持久卷 fixture 的 12 题 employee ACL Top-7 受保护诊断，对比 Top-5、原跨文档相邻替换和保留 Top-K 文档集合的同文档冗余替换。保守策略只使用 documentId、rank 和 `chunk#N` locator；答案点词项仅在选择后评分，Chunk 正文只在内存读取。
- 原策略重现离线词项提升 75%→87.5%（6/8→7/8），但可能移除独有跨文档证据。保守策略在 12/12 案例中保留文档集合，但无一例满足替换条件，答案点覆盖仍 75%（6/8），Q002/Q006 均未恢复；ACL leakage=0。因候选未改变，不进入线上 A/B。
- 临时 API、无持久卷数据库、凭据、回答采集、诊断数据和上传资料已清理。LM Chat 回答未用于这次离线候选评分；V0.1 默认检索行为不变。

## 文档维护规则

- “代码存在”不等于“测试通过”。
- “测试通过”不等于“真实 Provider/数据库联调通过”。
- “本地联调通过”不等于“已部署或可公开访问”。
- 规划项不能写进“已实现”；历史结果未复现时要明确标记。
- 每次里程碑更新都记录日期、环境、命令、结果、限制和下一步。
