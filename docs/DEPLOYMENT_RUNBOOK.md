# Deployment and Operations Runbook

> 当前 Runbook 面向单机本地/演示环境和 Docker Compose。它不是 Kubernetes、生产高可用或多地域部署方案。

## 1. 适用范围与前置条件

- macOS/Linux、Docker Desktop、JDK 25、Maven Wrapper。
- PostgreSQL + pgvector 通过 Compose 提供。
- Chat/Embedding Provider 使用 LM Studio 或其他 OpenAI-compatible 服务。
- 生产环境必须替换默认账号、JWT Secret、数据库密码、HTTP 明文和本地文件卷。

使用 LM Studio Gemma 4 作为本地 Chat Provider 时，加载模型后在 **Developer → 模型 → Inference → Custom Fields → Enable Thinking** 关闭 thinking，再通过 Structured Output 探针验收。当前后端 OpenAI-compatible 请求不携带 Gemma 专属自定义字段，不能依赖请求体临时覆盖该开关；模型参数应作为 Provider 的本地运行配置管理。2026-09-23 的本地 6 次重复探针在 thinking 关闭时均通过 JSON 合同，开启时重复探针曾 6 次耗尽 token 并失败；这只是结构化输出能力证据，不代替答案质量、ACL 和延迟评测。

启动前检查：

```bash
java -version
./backend/mvnw -version
docker version
curl -fsS http://localhost:1234/v1/models
```

## 2. 本地启动

### 仅数据库 + 源码后端（推荐开发方式）

```bash
docker compose up -d db
cd backend
./mvnw spring-boot:run
```

默认数据库是 `localhost:5432/rag_knowledge_base`，后端是 `localhost:8080`。Flyway 启动时执行迁移，Hibernate 使用 `validate` 检查实体与 schema 一致性。

### Compose 后端

```bash
docker compose up -d --build
docker compose ps
curl -fsS http://localhost:8080/readyz
```

只有在确认 LM Studio 可从容器访问时才使用 Compose 后端；宿主机 Provider 地址通常是 `http://host.docker.internal:1234/v1`。

## 3. 必须配置的运行参数

| 配置 | 本地默认 | 部署要求 |
|---|---|---|
| `JWT_SECRET` | 开发字符串 | 使用 Secret 管理器生成并轮换 |
| `APP_DEFAULT_PASSWORD` | `demo123456` | 禁止在部署环境保留 |
| `DB_PASSWORD` | `rag` | 使用 Secret，不写入镜像或 Git |
| `AI_API_KEY` | 空 | 云 Provider 用 Secret；本地 Provider 也要限制网络 |
| `UPLOAD_STORAGE_DIR` | `./uploads` | 使用持久化卷或对象存储，并限制访问 |
| `AI_*_MODEL_ID` | 本地模型 ID | 以 Provider `/v1/models` 实际返回值为准 |
| `AI_EMBEDDING_DIM` | `768` | 必须与现有 pgvector 列和模型维度一致 |

不要提交真实 API Key、JWT、密码、客户资料、Prompt、模型原始响应或 Embedding dump。

## 4. 健康检查与验收

```bash
curl -fsS http://localhost:8080/livez
curl -fsS http://localhost:8080/readyz
curl -fsS http://localhost:8080/actuator/health
```

`livez` 不访问数据库或 Provider；`readyz` 检查应用、数据库和磁盘。`/actuator/metrics` 与 `/actuator/prometheus` 需要 JWT + `SYSTEM_ADMIN`/`AUDITOR`，部署时还应放在内部网络。

应用验收顺序：

1. 登录成功。
2. 上传 `sample_faq.md`，文档状态进入 `ready` 且 Chunk 数大于 0。
3. 有权限问题命中并有引用。
4. 资料外问题 `found=false` 且 `sources=[]`。
5. 运行 `python3 evaluation/run_eval.py` 验证数据集契约。
6. 有条件时运行真实 API 评测、MCP smoke 和 Flutter smoke。

## 5. 数据库备份、恢复与迁移

### 备份

生产环境使用受控备份系统。单机演示可使用：

```bash
PGPASSFILE=/secure/secrets/postgres.pass pg_dump --format=custom \
  --file=/secure/backup/rag-knowledge-base-$(date +%Y%m%d-%H%M).dump \
  "postgresql://rag@localhost:5432/rag_knowledge_base"
```

`PGPASSFILE` 由部署系统安全提供，不要写入 shell history。备份数据库时还要备份 `UPLOAD_STORAGE_DIR` 中仍被文档引用的原始文件。

### 恢复演练

1. 停止写流量并确认备份时间点。
2. 在隔离数据库恢复 custom dump。
3. 启动同版本应用，检查 Flyway 当前版本和 `readyz`。
4. 验证文档列表、ACL、问答引用和索引任务。
5. 通过评测集或演示脚本执行最小回归后再切换流量。

### 迁移与回滚

Flyway 迁移是前向变更；不要在生产库执行 `flyway clean`，也不要把“回滚应用镜像”误认为“回滚数据库 schema”。发生迁移问题时，保留日志和备份，在隔离库恢复并按兼容版本处理。当前仓库没有自动生产回滚脚本。

## 6. 常见故障

| 症状 | 检查 | 处理 |
|---|---|---|
| Docker 无法连接 | `docker version`、Docker Desktop 状态 | 启动 Docker，再检查 `docker compose ps` |
| LM Studio GUI 能打开但请求失败 | `curl http://127.0.0.1:1234/v1/models` | 确认 Developer → Local Server 显示 Running；若从隔离执行环境检查，需允许访问本机回环网络；再核对 Chat/Embedding ID |
| Gemma Structured Output 反复截断/生成无效 JSON | Developer → 模型 → Inference → Custom Fields → Enable Thinking；再运行 `evaluation/probe_structured_output.py` | 对本地 RAG Chat 模型关闭 thinking 并重复探针；不要放宽 JSON 校验或将 `reasoning_content` 当作答案 |
| 全部上传在 Embedding 失败 | 模型 ID、维度、Provider 日志 | 确认 `text-embedding-nomic-embed-text-v1.5` 和 768 维；不要直接清库 |
| `/readyz` 失败 | 容器日志、数据库连接、磁盘空间 | 先恢复依赖，再重新检查；不要重复上传 |
| 文档一直 processing | index task 状态、attempt、失败原因 | 检查 Provider/解析器，修复原因后调用授权 retry |
| 问答 401/403 | JWT、角色、知识库 membership、文档 ACL | 重新登录并核对服务端权限；不要在客户端绕过 |
| 问答 found=false | similarity、ACL、文档状态、Provider 失败原因 | 先判断是资料不足、未授权还是索引失败 |
| Structured Output 失败 | `failureReason`、Provider finish reason | 保持 fail-closed；不要把截断 JSON 或 `reasoning_content` 当答案 |
| 容器访问宿主机 Provider 失败 | Compose 中的 `AI_BASE_URL` | 使用 `host.docker.internal`，确认 Provider 监听地址和网络策略 |

### Provider 侧模型 I/O 与隐私边界

**范围声明（2026-09-24 收口）**：LM Studio 是本项目的**本地开发/评测 Provider**，不属于 V0.1 交付系统的一部分。它的日志治理不作为本仓库的验收门槛，本仓库也不再继续调查其日志轮转、保留或目录重建后的权限行为。

已确认的事实（证据见 `evaluation/reports/provider-log-boundary-review-local-2026-09-24.md`）：

- LM Studio 0.4.25（Build 1）会把至少部分 model I/O（格式化输入与输出）持久化到本机 `~/.lmstudio/server-logs`；RAG 输入包含检索到的文档片段，因此这些日志按敏感数据处理。[LM Studio `lms log stream` 文档](https://lmstudio.ai/docs/cli/serve/log-stream)
- 公开文档与本机设置中未发现可依赖的脱敏、保留期限或关闭开关。
- 本机缓解：`server-logs` 根目录已收紧为 `0700`，正常退出/重开后保持；这只是开发机缓解，不是控制措施。

由此得出的使用规则：

- **LM Studio 只处理合成/公开样例数据**。不向其发送真实客户文档、真实员工问题或任何受限内容。
- 排查服务启动/HTTP 状态时用 `lms log stream --source server`；不要分享 Developer Logs、model source 日志、终端记录或原始 Provider 响应，也不要把它们放进截图、录屏、工单或公开报告。
- 应用自身不记录 Prompt、问题或文档正文；这只覆盖应用边界，不代表全链路无内容日志。

**接入真实数据前的 Provider 验收清单**（任一项不满足即不得接入真实数据）：

1. Provider 提供书面的请求/响应数据保留期限，以及是否用于训练的承诺；
2. 可关闭或脱敏 Prompt/输出日志，或者日志访问受控并有审计；
3. 数据驻留地区、传输加密和访问控制满足客户要求；
4. 有可审计的删除路径和事件响应联系人；
5. 以合成数据重跑 Golden / answer-quality / stress 评测，并在 `PROGRESS.md` 记录模型 ID、参数和结果。

## 7. 事件处理与证据保留

- 保留时间、部署版本、配置摘要、requestId、failure 分类和任务状态。
- 不保留问题正文、Prompt、文档正文、Token、密码或原始 Provider 响应到公开报告。
- 先判断是否是权限/数据泄露，再判断质量和性能；ACL 拒绝事件需要保留审计链。
- 变更后重新执行 `./mvnw test`、`docker compose config --quiet` 和适用的离线/真实评测。
- 公开 Case Study 只使用版本化报告中的聚合数字和明确日期。
