# RAG Knowledge Base

[![CI](https://github.com/mikehot/rag-knowledge-base/actions/workflows/ci.yml/badge.svg)](https://github.com/mikehot/rag-knowledge-base/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

中文 | [English](README.en.md)

企业知识库问答系统：员工只能检索被授权的文档，每个回答都带出处，资料里没有的问题转人工而不是编造。技术栈为 Spring Boot + PostgreSQL/pgvector + Flutter，附带评测、审计和部署手册。

*An enterprise knowledge-base Q&A system where employees only retrieve documents they are allowed to see, every answer cites its sources, and uncovered questions are handed off instead of invented.*

- **作品集说明（先看这个）**：[PORTFOLIO.md](PORTFOLIO.md)，包含设计决策、评测结果与已知局限，以及可交付范围。
- **5 分钟跑起来**：下方 [Quick Start](#quick-start)。
- **当前状态**：V0.2（评测与检索质量阶段）。核心链路已实现并验证：权限过滤检索、带引用回答（后端检查引用是否真的支撑答案）、拒答、提示注入防护、持久化索引与故障恢复、审计与指标、只读 Tool/MCP、可切换且可重建的 Embedding 模型。质量门对「bge-m3 + `AI_TEMPERATURE=0`」配置通过，对默认配置（nomic）**不通过**；尚无演示视频和公开 Demo。验证证据见 [PROGRESS.md](PROGRESS.md) 与 [evaluation/reports](evaluation/reports/README.md)。

## 评测一览（本地、合成与公开数据）

| 项 | 结果 | 说明 |
|---|---|---|
| 真实语料 corpus-v1（28 份文档，57 题） | 可答题 **44/45**（bge-m3），拒答 12/12，ACL 泄漏 0 | 换用 nomic 为 25/45；题目由单人基于文档编写 |
| 同一配置的波动 | 41–44/45 | 单次运行不能支撑对照，见 [波动报告](evaluation/reports/run-to-run-variation-local-2026-10-13.md) |
| answer-quality 质量门 | bge-m3 + `temperature=0` 通过（连续 8 轮 11/12）；默认配置不通过 | [Q006 与质量门](evaluation/reports/quality-006-and-gate-local-2026-10-13.md) |
| 改写后的题集 corpus-v2（同样 57 题，日常说法） | 可答题 43/45（跨重启 v1 为 43–45），检索证据仍 45/45 完整 | 失败来自问法歧义与跨文档答案不完整，不是检索；[报告](evaluation/reports/corpus-v2-paraphrase-local-2026-10-13.md) |
| 引用支撑检查 | 521 条回放中修复 3 条、0 条变差 | 主要保护「每个回答都带出处」，不是提分 |
| **复测（2026-10-14，新栈，同配置同代码）** | corpus-v2 可答题 **38/45**，拒答 12/12，ACL 泄漏 0；检索指标不变（top-1 相似度中位数 0.703，期望文档 45/45） | 比前一天低 5 题，**原因未隔离**：差异在生成侧，LM Studio 与模型文件未变；见 [补记](evaluation/reports/corpus-v2-paraphrase-local-2026-10-13.md) |
| Python 智能体经 MCP 调用本服务的工具调用评测（[rag-agent-python](https://github.com/mikehot/rag-agent-python)） | 安全类（越权不泄漏、边界、超范围拒答）在全部留出集的全部配置下通过；答案类随片段长度变化（留出集 v2）：360 字符默认 18/22，放宽到 800 为 21/22 | 每格一次运行，题目单人编写，子串评分；失败模式包括模型空回复和误判「知识库里没有」 |

局限见 [PORTFOLIO.md](PORTFOLIO.md)：单机、单模型、规则（子串）评分、题目由单人编写；没有生产部署或真实用户数据。

**这些数字是「某一天、某一台机器、某一个模型服务状态」下的测量，不是常数。** 同一配置在相隔一天的全新环境上差了 5 题就是例子；重复运行在同一会话内只能说明解码是确定的，不能说明结果稳定。引用任何一格数字时请连同日期和配置一起引用。

## 文档导航

| 文档 | 内容 |
|---|---|
| [PORTFOLIO.md](PORTFOLIO.md) | 对外说明：问题、架构、设计决策、评测与局限 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统上下文、数据流、权限信任边界 |
| [docs/DEMO.md](docs/DEMO.md) | 可执行的演示讲稿（反馈、MCP、失败恢复、Flutter） |
| [docs/DEPLOYMENT_RUNBOOK.md](docs/DEPLOYMENT_RUNBOOK.md) | 启动、迁移、备份恢复、故障排查、Provider 数据边界 |
| [docs/CASE_STUDY.md](docs/CASE_STUDY.md) / [EN](docs/CASE_STUDY_EN.md) | 项目复盘 |
| [evaluation/README.md](evaluation/README.md) | 评测数据集、质量门与评测工具 |
| [REQUIREMENTS.md](REQUIREMENTS.md) / [ROADMAP.md](ROADMAP.md) / [PROGRESS.md](PROGRESS.md) | 需求、计划、验证状态 |
| [backend/README.md](backend/README.md) / [app/README.md](app/README.md) | 后端与 Flutter 客户端说明 |

## Modules

- `backend/`：Spring Boot 3.5，PostgreSQL + pgvector，负责文档处理、权限、RAG API、审计与指标。
- `app/`：Flutter App（Riverpod MVVM + dio/retrofit），提供问答、知识库管理、ACL 管理和索引任务面板。
- `evaluation/`：Python 评测工具与版本化数据集。
- `sample_faq.md`：端到端演示用的合成样例知识。

## Quick Start

在一台已有依赖的机器上，从 clone 到拿到第一个带引用的回答，机器时间约 1 分钟（2026-09-24 全新 clone 演练，见 [报告](evaluation/reports/clean-clone-rehearsal-local-2026-09-24.md)）。首次运行还需要额外时间拉取 Docker 镜像、下载 Maven 依赖，并在 LM Studio 中下载模型。

### 前提

- Docker（daemon 已启动）、JDK 25（`.java-version`；源码以 Java 17 字节码编译）、`curl`、`jq`。
- 一个 OpenAI-compatible 模型服务，默认是本机 LM Studio `http://localhost:1234/v1`，需要：
  - Chat：`google/gemma-4-26b-a4b-qat`（通过 `AI_MODEL_ID` 可替换）。Gemma 需在 LM Studio 中关闭 **Enable Thinking**，否则可能耗尽输出预算，导致结构化回答失败，见 [Runbook](docs/DEPLOYMENT_RUNBOOK.md)。
  - Embedding：`text-embedding-nomic-embed-text-v1.5`，768 维。
  - 两个 ID 必须与 `curl -s http://localhost:1234/v1/models` 的返回完全一致。
- LM Studio 只处理合成或公开样例数据，不要用它处理真实客户资料。
- 也可以改用云端聊天模型。已验证的配置是 DeepSeek `deepseek-flash` 聊天加本地 Embedding，需要设置 `AI_RESPONSE_FORMAT=json_object`，见 [backend/README.md](backend/README.md#cloud-provider-examples)。
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

演示视频**尚未录制**。Python 智能体一侧已备好分镜和两个逐幕驱动脚本（用户视角 7 幕，管理员上传与授权/撤权一段，以及约 5 分钟的剪辑方案），见 [rag-agent-python/docs/DEMO_VIDEO.md](https://github.com/mikehot/rag-agent-python/blob/main/docs/DEMO_VIDEO.md)；Flutter 客户端和真机抽测不在其中，见 `docs/DEMO.md`。

## Technology Positioning

- Java / Spring Boot 负责企业业务、权限、API、审计与部署。
- PostgreSQL + pgvector 作为 V0.1 的业务与向量数据底座。
- Flutter 展示移动端问答、知识管理、引用和反馈闭环。
- Python 用于离线评测、检索实验和数据工具，不替换主后端。另有独立仓库 [rag-agent-python](https://github.com/mikehot/rag-agent-python) 作为编排层，通过本服务的 `/mcp` 以调用者自己的 JWT 调用只读工具；它不重新实现分块、向量化和检索。

## Security

- 不提交真实 API Key、JWT Secret、客户资料、生产 Token 或 Embedding dump。
- 示例数据必须脱敏。
- Prompt、文档正文、Tool 参数和模型原始响应默认不进入日志。
- 权限过滤发生在数据库检索阶段，以零越权泄漏作为验收条件。
- 本地 LM Studio 只处理合成或公开数据；真实数据须使用通过 Runbook Provider 验收清单的服务。

## License

[MIT](LICENSE)
