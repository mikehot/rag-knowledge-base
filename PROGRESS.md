# PROGRESS

> Last evidence review: 2026-09-22
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
| 2. 身份、ACL、文档生命周期 | `in-progress` | Flyway V1-V12、ACL、最小管理 API、可回滚替换和持久化索引任务已落地；PostgreSQL CI、真实 Provider 重试和重启恢复已验证，管理 UI 等次要范围仍未完成 |
| 3. 结构化回答、审计、观测、反馈 | `verified` | requestId、总/分段耗时、稳定失败分类、超时/参数校验/401/403 契约、V6/V7 观测字段、V8 用户反馈、健康/就绪探针、反馈/ACL 拒绝/Token/估算成本指标、受保护检索诊断和 Structured Output Contract 已落地；本地 Docker-backed 全量回归 109 passed/0 skipped，OpenAI-compatible Provider 已发送原生 JSON Schema，并完成 LM Studio 真实 A/B 复核 |
| 4. 评测基线与检索压力集 | `verified` | `golden-v1` 历史两次 20/20、修复评测 fixture 后默认 Top-K=5 为 16/20、压力集 8/8；ACL leakage=0；V10 受保护诊断及 Recall@1/3/5 评分已落地，Top-K=8 对照未通过全局质量门槛 |
| 5. Hybrid Search / Reranker | `planned` | 只在评测证明需要后启动 |
| 6. Agent Tool / MCP | `in-progress` | 三个只读 Agent Tool、闭合参数 schema、ACL/租户继承、空结果/超时/未知工具/预算/审计测试已落地；最小无状态 MCP 适配层和可重复的本地 HTTP smoke 已通过，完整 MCP transport/auth、第三方 SDK/client conformance 和 Agent loop 尚未完成 |
| 7. 交付包 / FDE Case Study | `in-progress` | 第一版架构图、Discovery Brief、Demo、部署 Runbook 和中英文 Case Study 已完成；干净 disposable 演示证据、公开部署和真实客户运营基线仍未完成 |

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
| Flutter Android 设备级演示 | PASS（本地 disposable 证据；范围分项记录） | 2026-09-23 Android 16/API 36 真实设备：Flutter 应用内选择唯一临时 Markdown 文件后，知识库 UI 从“入库中”变为“1 段 · 就绪”，只读文档 API 同步观察到 `processing`→`ready`、chunkCount=1；临时后端文档及设备文件随后删除。更早 `sample_faq.md` 已在操作前存在且为 `ready`，因此此前“选择/上传后 ready”的因果归属未核实，不再作为上传证据；其他问答/反馈/拒答/菜单/退出登录截图仍为本地临时记录，详见 `docs/DEMO.md`；不代表公开部署或生产设备证据 |

本轮真实验证发现并修复：模型判断资料不足时曾错误返回 `found=true` 和无关来源；删除文档时曾残留原始文件。两条路径均已增加回归测试。

2026-09-20 真实联调发现默认 LM Studio 模型 ID 已过期：`text-embedding-nomic-embed-text` 会导致全部请求在 Embedding 阶段失败；已根据 `/v1/models` 核实并同步为 `text-embedding-nomic-embed-text-v1.5`，Chat 模型同步为 `google/gemma-4-26b-a4b-qat`。随后发现 Gemma 在 `AI_MAX_TOKENS=1200` 时会把预算消耗在 reasoning，导致可见答案为空或截断；默认配置已同步为 2400。最新完整压力集恢复为 8/8，STRESS-003 的两个目标文档仍完整命中，说明问题属于生成预算而不是召回。2026-09-21 的完整 4000 候选回归显示 Stress 仍为 8/8，但 Golden 为 15/20 且有 4 次结构化输出失败，暂不切换默认值。

2026-09-22 新增无文档 Structured Output Provider 探针，复核当前 Gemma 与已加载 Qwen 模型。Gemma 在简单和多要点问题上均以 `finish_reason=length` 结束且 content JSON 无法解析；Qwen 以 `stop` 结束但 `message.content` 为空、`reasoning_content` 存在，当前 Java Provider 不将后者当作答案。该证据确认 Q006 的剩余问题属于模型输出契约稳定性，不批准直接切换模型；必须先重复探针，再用同一 answer-quality/stress 门禁复核。

2026-09-23 加载此前未加载的默认 Gemma 后复测：thinking 开启时首次两条合成探针通过、随后重复三轮六条请求均耗尽 2400/3200 completion-token 并返回无效 JSON（2/8）。检查 LM Studio UI 确认 `Enable Thinking=on`；关闭后通过本机 API 重跑三轮，6/6 均 HTTP 200、`finish_reason=stop` 且通过严格 JSON 合同（25–140 completion tokens）。此前 shell 失败由本机回环网络沙箱限制导致，不是 LM Studio 服务故障。后续在 thinking 关闭下完成隔离端到端评测：Golden 16/20、answer-quality 9/12、stress 7/8，ACL 泄漏和 Schema 失败均为 0；再完成失败题 chunk 证据诊断与完整质量集 Top-K 5/8 双轮对照，Top-K 8 的 rubric 通过平均与 Top-K 5 相同、Token 平均约高 9.4%，默认保持 5。LM Studio 原开关已恢复 on；详见 `evaluation/reports/structured-output-gemma-recheck-local-2026-09-23.md`、`gemma-thinking-off-end-to-end-local-2026-09-23.md` 和 `chunk-evidence-topk-ab-local-2026-09-23.md`。

2026-09-22 对 `QUALITY-002` 做了 ACL-aware 向量候选扩展诊断：在 50 候选上实际只有 7 个可见 Chunk，`sample_faq.md` 位于 rank 6，正好落在生产 Top-K=5 之后；rank 5/6 相似度差约 0.0031。该证据确认召回缺口是窄边界排序问题，但不支持全局提升 Top-K；下一步只做离线候选扩展/重排对照。

随后完成 8 题 stress 集的离线文档多样性对照：多样性策略未提高目标候选 Recall 或候选行为通过数，反而增加 answerable 上下文干扰，且拒答题仍产生候选。随后完成 12 题 answer-quality 端到端 A/B；迁移 V12 修复实验模式审计约束后，VECTOR_DIVERSITY 仍未提高质量门或答案点覆盖，继续保持默认关闭。Q002 的局部召回改善不足以支持运行时重排；下一步回到模型输出稳定性、诊断和 CI 集成门槛。

## 已知缺口

- 本地和 GitHub Actions 均固定 JDK 25；GitHub Actions 已完成远端验证。
- 后端当前本地回归为 95 个 H2/非 Docker 测试和 15 个 PostgreSQL/Testcontainers 集成测试通过，共 110 个测试、0 skipped。CI 新增读取 Surefire XML 的硬门禁：PostgreSQL 集成报告缺失、全部跳过或存在失败都会让 backend job 失败；本次门禁尚未由 GitHub Actions 远端执行。此前 GitHub Actions run `35698781377` 对旧提交通过 `backend-tests`、`compose-config` 和 `flutter-tests`，长期运行基线仍需持续积累。
- 企业身份与 ACL schema、查询边界和最小管理 API 已建立；尚无前端管理页、批量导入、用户停用、部门停用和更细的知识库管理员权限矩阵。
- 当前只有 allow 型 ACL；尚未定义显式 deny、组织继承冲突和权限缓存失效策略。
- 文档已实现 checksum、内容版本、权限版本、停用、软删除、reindex、可回滚替换和持久化任务治理；尚无任务取消、优先级、分布式 Broker 或前端任务管理页，这些不属于当前最小闭环。
- 权限拒绝已有基础审计事件、tenant 范围内只读查询 API 和低基数拒绝计数；尚未提供保留策略、脱敏策略和评测记录。
- 应用自身默认不记录文档正文和 Prompt，但本地真实联调确认 LM Studio Developer Logs 会显示 Embedding 输入、Prompt 和模型输出；客户敏感资料上线前必须单独配置或替换 Provider 日志策略，不能把应用日志边界误认为全链路日志边界。
- 问答已有 requestId、总/分段耗时、结构化失败原因、Provider 超时分类、参数校验和统一 401/403 契约、用户反馈、健康/就绪探针、反馈率/ACL 拒绝/Token/估算成本指标及首版告警 guardrail；尚未用真实 7 天基线调优阈值。
- 已有 `golden-v1`（20 题）、`answer-quality-v1`（12 题）、`retrieval-stress-v1`（8 题）、真实 API 采集和聚合报告；答案质量扩展集已完成 VECTOR/KEYWORD_RRF 后端 A/B；Gemma thinking 关闭时的 disposable 端到端复测为 Golden 16/20、answer-quality 9/12、stress 7/8，结构化失败和 ACL 泄漏均为 0；stress 检索诊断 8/8、Recall@5=100%。新增 chunk 级指定来源词项证据诊断（报告不落文档正文），7 个失败题 Top-5 evidence-point coverage 28.57%、Top-8/10 为 100%，但 Top-8/10 每题平均多带 6 个无明确答案点的 chunk。完整质量集 Top-K=5/8 各运行两轮，rubric 通过均值均为 10/12；Top-8 词项答案点覆盖/引用较高但单轮波动且平均 Token +9.4%，默认仍为 5；详见 `evaluation/reports/chunk-evidence-topk-ab-local-2026-09-23.md`。云端成本对照和独立模型评分仍未完成。
- 没有生产级 BM25/全文 Hybrid Search 或 Reranker；PostgreSQL 默认 simple FTS 的中文切词预实验不足，`pg_trgm` 仅完成 disposable 查询验证。离线 keyword candidate、文档级 RRF 模拟、ACL-aware 线上候选 A/B、默认关闭的后端 keyword-RRF 端到端 A/B 和默认关闭的 vector-diversity 端到端 A/B 已完成；当前结果不批准默认启用，也没有成本结论。
- 应用内只读 Agent Tool Registry 和最小无状态 MCP adapter 已实现并完成单元回归；`evaluation/run_mcp_smoke.py` 已提供可重复的本地 HTTP 边界检查；尚无完整 MCP transport/auth conformance、第三方 SDK/client 互操作证据、模型驱动 Agent loop 或写工具。
- Flutter 问答客户端现在解析并展示后端回答契约、来源、失败分类、耗时/Token，并可提交单次反馈；当前已有独立登录页、平台安全 token 存储、启动恢复和退出登录；知识库页已覆盖可见文档的停用、失败/就绪重建索引和删除入口，但尚无 ACL 管理、已停用文档恢复列表和批量任务管理页面。Flutter CI 已由 run `35698781377` 远端验证通过；Android 设备级截图已完成，尚无录屏、公开可访问 Demo 或生产设备证据。
- 已加入确定性的复杂问题识别与预算路由开关，并增加模型拒答字段一致性 fail-closed；3200 开启实测未通过质量门槛，默认继续关闭。Top-K=8 在失败题离线词项覆盖上优于 5，但完整 answer-quality 两轮未稳定提高 rubric 通过均值且 Token 更高，默认继续保持 5。
- Gemma 在 LM Studio `Enable Thinking=off` 时通过了 6/6 独立 Structured Output 合同探针；端到端三集合复测中结构化失败=0、ACL 泄漏=0、stress Recall@5=100%，但 Golden 16/20、answer-quality 9/12、stress 7/8，答案质量门未通过。本地设置尚未持久化/自动化，继续保持 fail-closed，不要放宽 JSON 解析，也不要把 `reasoning_content` 当作 `message.content` 替代。
- 第一版架构图、Discovery Brief、Demo、部署 Runbook 和中英文 Case Study 已完成；已有一次隔离 API Demo 记录和 Android 设备截图；尚无录屏、公开可访问 Demo、真实客户生产部署和长期运营基线。

## 下一步

RAG 基线、ACL、Structured Output 和应用内只读 Agent Tool 边界已经具备实现与真实 HTTP 证据；本地评测仍暴露召回排序和 Provider 吞吐问题，下一阶段先收敛证据，再决定是否进入 MCP：

1. 在 Docker-backed CI 修复后执行新增 PostgreSQL ACL、知识库过滤和工具 HTTP 路由集成测试。
2. 已完成 `QUALITY-002`/`QUALITY-006` 的 chunk 证据、服务端拒答门控、重复 API 对照和固定上下文生成重放：两题答案点 chunk 分别位于 rank 7 和 rank 6，Top-K=5 两轮均失败；Top-K=8 首轮通过、第二轮失败。API failureReason 是生成后的 `INSUFFICIENT_CONTEXT`，不是服务端空候选/阈值短路 `RETRIEVAL_MISS`。只提供各自相关 FAQ 片段时，Gemma Thinking-off 固定上下文调用两题均 5/5 通过 JSON、引用和全部答案点覆盖，说明模型能依据干净充分的证据作答；K=8 失败更可能与上下文组合/干扰或采样交互有关，当前无法再拆分。默认 Top-K=5 不变，不改拒答契约；详见 `evaluation/reports/quality-002-006-failure-classification-local-2026-09-23.md`。
3. `QUALITY-002` 曾有向量 Top-5 边界排序缺口，但离线和端到端多样性重排未产生净质量收益；继续保持 Top-K、Hybrid Search、Reranker 和 vector-diversity 默认关闭，并完成 V12 CI/评测稳定性复核。
4. answer-quality 门槛稳定通过后，再进入最小 Flutter ACL 管理 UI 验收；之后补完整设备 Demo 录屏并评估公开部署。第三方 SDK/client conformance 单独排期，Agent 写操作继续不开放。

## 文档维护规则

- “代码存在”不等于“测试通过”。
- “测试通过”不等于“真实 Provider/数据库联调通过”。
- “本地联调通过”不等于“已部署或可公开访问”。
- 规划项不能写进“已实现”；历史结果未复现时要明确标记。
- 每次里程碑更新都记录日期、环境、命令、结果、限制和下一步。
