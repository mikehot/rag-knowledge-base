# RAG Knowledge Base

一个正在从 RAG MVP 升级为企业知识库 V0.1 的 Java / Spring Boot + Flutter 项目。

目标闭环：管理员上传企业文档，系统完成解析、切片、Embedding 和索引；员工登录后只能检索有权访问的知识；AI 返回有来源的回答；系统记录审计、延迟、Token、失败和反馈，并通过版本化评测集验证质量与权限边界。

## 当前状态

仓库已具备 JWT、PDF/DOCX/TXT/Markdown 入库、pgvector 向量检索、相似度拒答、引用、Token 记录、Flutter 问答与文档管理等 MVP 代码。Milestone 2 已进入后半程：Flyway migration、tenant/department/role/knowledge-base/document ACL schema、SQL 查询阶段权限过滤、最小管理 API、文档生命周期、拒绝审计和只读审计查询 API 已经落地。

持久化索引任务、批量重建、自动/人工失败重试、幂等入队和任务状态观测已经进入代码与测试验证阶段。前端管理页、完整回答观测、20 题评测、Agent Tool 和 MCP 尚未完成。真实 RAG 基线、旧库升级、空库迁移、可回滚替换上传，以及 USER/DEPARTMENT/ROLE/tenant 权限边界均已有验证证据，但这不代表企业知识库 V0.1 已完成。

当前验证状态与已知限制见 [PROGRESS.md](PROGRESS.md)。

## 文档导航

- [REQUIREMENTS.md](REQUIREMENTS.md)：企业知识库 V0.1 需求、边界与验收标准。
- [ROADMAP.md](ROADMAP.md)：从真实 RAG 基线到 ACL、评测、Agent/MCP 和 FDE 交付包的推进顺序。
- [PROGRESS.md](PROGRESS.md)：代码已实现范围、当前验证证据与下一步。
- [PORTFOLIO.md](PORTFOLIO.md)：对外展示口径；只能使用已经验证的证据。
- [backend/README.md](backend/README.md)：后端配置、Provider 和 API。
- [app/README.md](app/README.md)：Flutter 运行方式和客户端说明。

## Modules

- `backend/`：Spring Boot 3.5.x、PostgreSQL、pgvector、文档处理和 RAG API。
- `app/`：Flutter App，Riverpod MVVM + dio/retrofit。
- `sample_faq.md`：用于真实端到端基线验证的样例知识。

## Quick Start

前提：Docker daemon 已启动，并准备一个 OpenAI-compatible Chat + Embedding 服务。默认配置面向本机 LM Studio。

当前验证工具链为 JDK 25.0.3；`.java-version` 固定为 `25`，Maven 仍以 Java 17 release 编译源码。

```bash
docker compose up -d db
cd backend
./mvnw spring-boot:run
```

默认演示账号：

- username: `demo`
- password: `demo123456`

默认 AI 配置：

- Chat base URL: `http://localhost:1234/v1`
- Embedding model: `text-embedding-nomic-embed-text`
- Embedding dim: `768`

如果后端运行在 Docker 中而 LM Studio/Ollama 运行在宿主机，`AI_BASE_URL` 和 `AI_EMBEDDING_BASE_URL` 通常需要设置为 `http://host.docker.internal:1234/v1`。

上述命令是项目启动入口。每个新环境仍需按 [ROADMAP.md](ROADMAP.md) 的 Milestone 1 和 [PROGRESS.md](PROGRESS.md) 的证据要求重新验证。

## V0.1 推进顺序

```text
真实 RAG 基线
  → 用户 / 部门 / 角色 / 知识库 / 文档 ACL
  → 文档生命周期、结构化回答、审计、观测和反馈
  → 20 题评测基线
  → 评测驱动的 Hybrid Search / Reranker 决策
  → 只读 Agent Tool 和 MCP
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
