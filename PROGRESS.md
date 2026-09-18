# PROGRESS

> Last evidence review: 2026-09-18
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
| 2. 身份、ACL、文档生命周期 | `in-progress` | Flyway V1-V5、ACL、最小管理 API、可回滚替换和持久化索引任务已落地；V5 PostgreSQL CI 与真实 Provider 异步恢复仍待验证 |
| 3. 结构化回答、审计、观测、反馈 | `planned` | 已有基础回答结构、Token 和 ask log；缺 requestId、分段耗时、失败分类和反馈 |
| 4. 20 题评测基线 | `planned` | 尚无版本化评测集和 Runner |
| 5. Hybrid Search / Reranker | `planned` | 只在评测证明需要后启动 |
| 6. Agent Tool / MCP | `planned` | 尚未实现；等待 ACL、审计和评测门槛 |
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
- Flyway V1-V5 管理 RAG、企业身份/ACL、幂等约束、审计和持久化索引任务 schema；Hibernate 只做 schema validate。
- V2 已包含 tenant、department、role、user-role、knowledge base、membership 和 document ACL。
- 文档列表/详情和 Chunk 向量查询在 SQL 阶段执行 tenant + user/department/role + knowledge-base/document ACL 过滤。
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

## 当前验证证据（2026-09-17）

| 检查 | 结果 | 证据边界 |
|---|---|---|
| `flutter analyze --no-pub` | PASS | 静态分析通过 |
| `flutter test --no-pub --concurrency=1` | PASS | 仅一个 Widget smoke test，不覆盖网络和文件选择 |
| `docker compose config --quiet` | PASS | Compose 配置可解析，不代表容器已启动 |
| `./mvnw test` | PASS | 2026-09-18 本地 JDK 25.0.3；30 non-PostgreSQL tests passed；本机无 Docker socket，11 个 PostgreSQL integration tests skipped |
| GitHub Actions CI | PASS | Run `35169350194`；backend-tests 在 Ubuntu + Temurin 25.0.4.1 上实际执行 34 tests，0 failures/errors/skipped；compose-config 通过 |
| PostgreSQL + pgvector 运行 | PASS | PostgreSQL 16.15、pgvector 0.8.6、4 张业务表、HNSW cosine 索引 |
| LM Studio 模型 | PASS | Gemma 4 26B + Nomic Embedding，OpenAI-compatible server `1234` |
| 文档入库 | PASS | `sample_faq.md` 进入 `ready`，生成 2 个 Chunk |
| 资料内问题 | PASS | `found=true`，回答保修期限与申请流程，返回有效来源 |
| 资料外问题 | PASS | `found=false`、`sources=[]`；保留实际模型 Token 用量 |
| 文档删除 | PASS | API 返回成功，document/chunk 行清零，原始上传文件删除，再次检索拒答 |
| 旧库迁移 | PASS | 非空旧 schema 自动 baseline 为 V1，再执行 V2；Hibernate validate 与应用启动通过 |
| 空库迁移 | PASS | GitHub Actions run `35169350194` 顺序执行 V1/V2/V3/V4；PostgreSQL 16.15、`vector(768)`、默认用户/角色/知识库授权和 `audit_event` 建表通过 |
| ACL 隔离 | PASS | 无授权用户列表为空；USER、DEPARTMENT、ROLE 授权范围均有确定性测试；READ 用户删除返回 404；双 tenant 文档列表/详情互不可见 |
| 文档替换 | PASS | GitHub Actions run `35169350194` 验证版本化新文件、`contentVersion` 递增、旧文件保留和旧 Chunk 在新索引就绪前不被删除；单元测试覆盖同 checksum 幂等、越权拒绝、成功切换和解析失败回滚 |
| PostgreSQL/Testcontainers 集成测试 | PASS | GitHub Actions run `35169350194` 实际拉起 PostgreSQL 16.15 + pgvector，Flyway V1/V2/V3/V4 成功，`PostgresEnterpriseIntegrationTests` 10 tests / 0 skipped，并覆盖替换暂存、权限拒绝审计、审计查询、部门/角色授权和跨 tenant 隔离 |

本轮真实验证发现并修复：模型判断资料不足时曾错误返回 `found=true` 和无关来源；删除文档时曾残留原始文件。两条路径均已增加回归测试。

## 已知缺口

- 本地和 GitHub Actions 均固定 JDK 25；GitHub Actions 已完成远端验证。
- 后端当前有 30 个稳定单元/上下文测试和 11 个 PostgreSQL/Testcontainers 集成测试；新增任务测试的 PostgreSQL 部分尚需 GitHub Actions 形成远端证据。
- 企业身份与 ACL schema、查询边界和最小管理 API 已建立；尚无前端管理页、批量导入、用户停用、部门停用和更细的知识库管理员权限矩阵。
- 当前只有 allow 型 ACL；尚未定义显式 deny、组织继承冲突和权限缓存失效策略。
- 文档已实现 checksum、内容版本、权限版本、停用、软删除、reindex、可回滚替换和持久化任务治理；尚无任务取消、优先级、分布式 Broker 或前端任务管理页，这些不属于当前最小闭环。
- 权限拒绝已有基础审计事件和 tenant 范围内只读查询 API；尚未提供保留策略、脱敏策略和评测记录。
- 问答没有 requestId、分段耗时、结构化失败原因和用户反馈。
- 没有 20 题 Golden Dataset、离线 Runner 或回归报告。
- 没有 BM25/全文 Hybrid Search 或 Reranker；是否需要尚无评测依据。
- 没有 Agent、Tool Calling 或 MCP。
- 没有公开 Demo、架构图、部署 Runbook、Case Study 和英文说明。

## 下一步

1. 通过 GitHub Actions 验证 Flyway V5、PostgreSQL 幂等入队、安全领取、失败记录和人工重试。
2. 用真实 Provider 验证一次上传成功、自动重试、最终失败和进程重启恢复。
3. 进入 Milestone 3：补 requestId、分段耗时、结构化失败原因和用户反馈。

## 文档维护规则

- “代码存在”不等于“测试通过”。
- “测试通过”不等于“真实 Provider/数据库联调通过”。
- “本地联调通过”不等于“已部署或可公开访问”。
- 规划项不能写进“已实现”；历史结果未复现时要明确标记。
- 每次里程碑更新都记录日期、环境、命令、结果、限制和下一步。
