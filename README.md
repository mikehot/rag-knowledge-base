# RAG Knowledge Base

一个正在从 RAG MVP 升级为企业知识库 V0.1 的 Java / Spring Boot + Flutter 项目。

目标闭环：管理员上传企业文档，系统完成解析、切片、Embedding 和索引；员工登录后只能检索有权访问的知识；AI 返回有来源的回答；系统记录审计、延迟、Token、失败和反馈，并通过版本化评测集验证质量与权限边界。

## 当前状态

仓库已具备 JWT、PDF/DOCX/TXT/Markdown 入库、pgvector 向量检索、相似度拒答、引用、Token 记录、Flutter 问答与文档管理等 MVP 代码。Milestone 2 已进入后半程：Flyway migration、tenant/department/role/knowledge-base/document ACL schema、SQL 查询阶段权限过滤、最小管理 API、文档生命周期、拒绝审计和只读审计查询 API 已经落地。

持久化索引任务、批量重建、自动/人工失败重试、幂等入队、任务状态观测和进程重启恢复已经通过 PostgreSQL CI 与本地真实 Provider 验证。问答现已具备请求关联 ID、总/分段耗时、稳定失败分类、数据库观测字段，以及绑定原提问用户和 tenant 的反馈 API；Actuator 存活/就绪探针、问答反馈、ACL 拒绝、Token/估算成本和第一组低基数 RAG/索引指标已通过本地测试与 GitHub CI。20 题 `golden-v1` 数据集、8 题 `retrieval-stress-v1`、离线 Runner、真实 API 采集入口、文档 ACL 管理 API 和仅管理员/审计员可读的检索诊断 API 已建立；历史本地 Golden 两次 20/20、压力集最新 8/8 通过；STRESS-003 的 Gemma 生成预算问题已通过默认 `AI_MAX_TOKENS=2400` 修复并由完整套件验证。另有不依赖人工答案词的 `keyword-candidates-v1` 离线候选 benchmark，当前仅用于评估是否值得引入 Hybrid Search。现在 `/api/ask` 已执行 Structured Output Contract，应用内只读 Agent Tool Registry 已提供 `search_knowledge`、`list_documents`、`get_document_status`，并已增加需要 JWT、复用同一 ACL 边界的最小无状态 MCP 适配层 `POST /mcp`；写操作、模型循环、Tasks、Resources 和 Prompts 尚未开放。聚合报告见 [evaluation/reports](evaluation/reports/)。指标与首版告警边界见 [OBSERVABILITY.md](OBSERVABILITY.md)。真实 RAG 基线、旧库升级、空库迁移、可回滚替换上传，以及 USER/DEPARTMENT/ROLE/tenant 权限边界均已有验证证据，但这不代表企业知识库 V0.1 已完成。

当前验证状态与已知限制见 [PROGRESS.md](PROGRESS.md)。

## 文档导航

- [REQUIREMENTS.md](REQUIREMENTS.md)：企业知识库 V0.1 需求、边界与验收标准。
- [ROADMAP.md](ROADMAP.md)：从真实 RAG 基线到 ACL、评测、Agent/MCP 和 FDE 交付包的推进顺序。
- [PROGRESS.md](PROGRESS.md)：代码已实现范围、当前验证证据与下一步。
- [evaluation/README.md](evaluation/README.md)：Golden Dataset、检索压力集、keyword candidate benchmark、结构化输出 Provider 探针、线上候选 A/B、后端端到端 A/B、离线 Runner 和真实响应评测边界。
- [PORTFOLIO.md](PORTFOLIO.md)：对外展示口径；只能使用已经验证的证据。
- [backend/README.md](backend/README.md)：后端配置、Provider 和 API。
- [app/README.md](app/README.md)：Flutter 运行方式和客户端说明。
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：系统上下文、数据流、权限信任边界和技术决策。
- [docs/DISCOVERY_BRIEF.md](docs/DISCOVERY_BRIEF.md)：用户、工作流、约束、指标和非目标。
- [docs/DEMO.md](docs/DEMO.md)：5–10 分钟演示讲稿和可执行步骤。
- [docs/DEPLOYMENT_RUNBOOK.md](docs/DEPLOYMENT_RUNBOOK.md)：启动、迁移、备份、恢复、回滚和故障排查。
- [docs/CASE_STUDY.md](docs/CASE_STUDY.md)：中文 Case Study 与证据边界。
- [docs/CASE_STUDY_EN.md](docs/CASE_STUDY_EN.md)：英文项目说明和 Demo talk track。
- [evaluation/reports/demo-v0.1-disposable-local-2026-09-22.md](evaluation/reports/demo-v0.1-disposable-local-2026-09-22.md)：隔离环境 API Demo 聚合记录。

## Modules

- `backend/`：Spring Boot 3.5.x、PostgreSQL、pgvector、文档处理和 RAG API。
- `app/`：Flutter App，Riverpod MVVM + dio/retrofit。
- `sample_faq.md`：用于真实端到端基线验证的样例知识。

## Quick Start

在一台已有依赖的机器上，从 clone 到拿到第一个带引用的回答，机器时间约 1 分钟（2026-09-24 全新 clone 演练，见 [报告](evaluation/reports/clean-clone-rehearsal-local-2026-09-24.md)）。首次运行还需要额外时间拉取 Docker 镜像、下载 Maven 依赖，并在 LM Studio 中下载模型。

### 前提

- Docker（daemon 已启动）、JDK 25（`.java-version`；源码以 Java 17 字节码编译）、`curl`、`jq`。
- 一个 OpenAI-compatible 模型服务，默认是本机 LM Studio `http://localhost:1234/v1`，需要：
  - Chat：`google/gemma-4-26b-a4b-qat`（通过 `AI_MODEL_ID` 可替换）。Gemma 需在 LM Studio 中关闭 **Enable Thinking**，否则可能耗尽输出预算，导致结构化回答失败，见 [Runbook](docs/DEPLOYMENT_RUNBOOK.md)。
  - Embedding：`text-embedding-nomic-embed-text-v1.5`，768 维。
  - 两个 ID 必须与 `curl -s http://localhost:1234/v1/models` 的返回完全一致。
- LM Studio 只处理合成或公开样例数据，不要用它处理真实客户资料。
- `docker-compose.yml` 固定了容器名 `rag-knowledge-base-db` 并使用 5432 端口；同一台机器已有另一份 checkout 的容器时，会发生冲突。

### 1. 启动

所有命令都在仓库根目录执行。

```bash
docker compose up -d db
(cd backend && ./mvnw spring-boot:run)
```

另开一个终端，等待 `curl -s http://localhost:8080/readyz` 返回 `{"status":"UP"}`。默认管理员是 `demo` / `demo123456`，仅限本地演示。

### 2. 上传并提问

```bash
BASE_URL=http://localhost:8080
TOKEN=$(curl -s "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}' | jq -r .data.token)

DOC_ID=$(curl -s "$BASE_URL/api/documents/upload" -H "Authorization: Bearer $TOKEN" \
  -F 'file=@sample_faq.md' | jq -r .data.documentId)

# 等待索引完成（通常几秒）
until curl -s "$BASE_URL/api/documents/$DOC_ID" -H "Authorization: Bearer $TOKEN" \
  | jq -e '.data.status == "ready"' >/dev/null; do sleep 2; done

# 资料内：found=true，sources 来自后端检索
curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"设备保修期是多久？"}' | jq '.data | {found, answer, sources: [.sources[].filename], latencyMs}'

# 资料外：found=false，不编造
curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"公司明年的股权计划是什么？"}' | jq '.data | {found, sources, failureReason}'
```

### 3. 权限：员工只能检索被授权的文档

```bash
EMP_ID=$(curl -s "$BASE_URL/api/admin/users" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"demo-employee","password":"employee123456","roleCodes":["EMPLOYEE"]}' | jq -r .data.id)
EMP_TOKEN=$(curl -s "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo-employee","password":"employee123456"}' | jq -r .data.token)
ask_as_employee() {
  curl -s "$BASE_URL/api/ask" -H "Authorization: Bearer $EMP_TOKEN" -H 'Content-Type: application/json' \
    -d '{"question":"设备保修期是多久？"}' | jq -c '.data | {found, sources: [.sources[].filename]}'
}

ask_as_employee    # 未授权：found=false，无引用；GET /api/documents 为空

ACL_ID=$(curl -s "$BASE_URL/api/documents/$DOC_ID/acl" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"principalType\":\"USER\",\"principalId\":\"$EMP_ID\",\"permission\":\"READ\"}" | jq -r .data.id)
ask_as_employee    # 已授权：found=true，引用 sample_faq.md

curl -s -X DELETE "$BASE_URL/api/documents/$DOC_ID/acl/$ACL_ID" -H "Authorization: Bearer $TOKEN" >/dev/null
ask_as_employee    # 撤权后：再次拒答
```

授权对象也可以是 `DEPARTMENT` 或 `ROLE`。权限在 SQL 检索阶段过滤，未授权的内容不会进入模型上下文。

更完整的演示（反馈、只读 Tool/MCP、失败恢复、Flutter 客户端）见 [docs/DEMO.md](docs/DEMO.md)。

## V0.1 推进顺序

```text
真实 RAG 基线
  → 用户 / 部门 / 角色 / 知识库 / 文档 ACL
  → 文档生命周期、结构化回答、审计、观测和反馈
  → 20 题评测基线
  → 评测驱动的 Hybrid Search / Reranker 决策
  → 只读 Agent Tool 和最小 MCP 适配层
  → 审批式写操作
```

Fine-tuning、GraphRAG、Neo4j、复杂 Multi-Agent、Kubernetes、本地 GPU 和全量 Python 重写不属于当前优先级。

## Technology Positioning

- Java / Spring Boot 负责企业业务、权限、API、审计与部署。
- PostgreSQL + pgvector 作为 V0.1 的业务与向量数据底座。
- Flutter 展示移动端问答、知识管理、引用和反馈闭环。
- Python 后续用于离线评测、OCR、Reranker 实验、数据清洗和 Benchmark，不替换主后端。

## Security

- 不提交真实 API Key、JWT Secret、客户资料、生产 Token 或 Embedding dump。
- 示例数据必须脱敏。
- Prompt、文档正文、Tool 参数和模型原始响应默认不进入日志。
- ACL 实现后，权限过滤必须发生在数据库检索阶段，并以零越权泄漏作为验收条件。
