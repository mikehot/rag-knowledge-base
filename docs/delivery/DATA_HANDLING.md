# 数据处理说明

> **English summary.** What this app sends to the model provider, what it logs, what it persists in
> Postgres, and what it writes to disk — each claim below is marked either "verified against the code on
> 2026-10-15" (with a file:line citation) or "not verifiable from this codebase" (third-party process or
> business process, out of scope for a code review). The one finding worth reading even if you skip the
> rest: **the raw question text is stored in Postgres indefinitely, with no purge job** (§3). Full detail
> is in Chinese below; ask if you need an English translation of a specific section.
>
> 本文件只写在 2026-10-15 对照代码核实过的内容，不是转述旧文档的说法。证据以 PORTFOLIO.md 和本文件标注的
> file:line 为准。

## 适用范围

本文件回答"客户的数据会去哪里、系统会记住什么"。不是安全审计，也不是合规认证——只是把代码里能验证的事实
说清楚。接入真实客户数据前，仍需要走 [DEPLOYMENT_RUNBOOK.md 的 Provider 验收清单](../DEPLOYMENT_RUNBOOK.md#provider-侧模型-io-与隐私边界)。

每条结论标注核实状态：

- ✅ **已核实**：直接读了对应代码，给出 file:line。
- ⚠️ **未核实 / 第三方行为**：涉及本仓库之外的进程（比如 LM Studio 本身）或业务流程，代码审查看不到，不能
  替你做这个判断。

## 1. 发给模型 Provider 的内容 ✅

每次提问，后端会把以下内容发给 `AI_BASE_URL` 指向的模型服务（本地 LM Studio 或任何 OpenAI 兼容云端服务）：

- 用户问题原文（`backend/src/main/java/com/example/ragknowledgebase/ask/AskService.java:538`）
- 命中的文档片段原文、文件名和定位（`AskService.java:527-532`）

**不发送**用户 id、租户 id、用户名或邮箱——通读了 `AskService.java` 构建 Prompt 的整段代码
（`buildPrompt`），没有任何地方引用这些字段。

Embedding 请求（`OpenAiCompatibleEmbeddingProvider.java:62-65`）只发问题原文或文档片段原文，同样不含身份
信息。

云端 Provider 的 API Key 只放在 `Authorization: Bearer` 请求头（`OpenAiCompatibleAiProvider.java:69-71`），
不写进请求体。

## 2. 应用自己的日志 ✅

读了整个后端代码库的日志调用：Spring Boot 应用**不会**把问题原文、文档片段或模型回答正文写进自己的日志。
唯一相关的一条日志语句（`AskService.java:421-431`，结构化输出失败时）只记录回答的**长度**，不记录内容。
没有请求/响应体记录的拦截器，也没有单独的 `logback-spring.xml` 覆盖这个行为
（`backend/src/main/resources/application.yml:38-40` 只是给日志行加了 `requestId` 前缀）。

**这条不包括 LM Studio 自己的日志** ⚠️。LM Studio 是本机另一个独立进程，不是这个代码库的一部分，我们验证
不了它的日志行为。已知的事实见
[DEPLOYMENT_RUNBOOK.md 的 Provider 侧模型 I/O 与隐私边界](../DEPLOYMENT_RUNBOOK.md#provider-侧模型-io-与隐私边界)：它会把部分 model I/O 写到本机
`~/.lmstudio/server-logs`，目前规则是"只用合成/公开数据"。能确认的只是：我们的程序不会把同样的内容再额外记
一份。

## 3. 数据库里持久化了什么

### 审计表（`audit_events`）✅ —— 只存权限元数据

字段：`tenant_id, user_id, username, action, resource_type, resource_id, outcome, reason（≤255字符）, created_at`
（迁移文件 `V4__audit_events.sql`）。`reason` 是系统生成的短字符串（比如拒绝原因），不是用户自由文本。只记
录"谁、何时、对什么、做了什么、结果如何"，不含问题或回答正文。权限变更和审计记录在同一事务提交
（`AuditService.java:48-57` 的 `@Transactional(propagation = Propagation.MANDATORY)`）。

### 问答历史表（`ask_log`）✅ —— **问题原文会被长期保存**

这是本文件最重要的一条，之前的文档没有专门说清楚：

- `ask_log` 表有一个 `question` 字段，**存问题原文**，截断到 2000 字符
  （`AskLog.java:28-29`），每次提问都会写入（`AskService.java:443-478` 的 `record()`方法）。
- **回答原文不存**——整个 `AskLog` 实体里没有 answer/response 字段，只有 `found`、`tokenUsage`、
  `resultStatus`、延迟分解、模型/Provider 配置这些结果和元数据。
- 关联表 `ask_retrieval_hit` 只存引用元数据（排名、chunk id、文件名、定位、相似度），**不存片段原文**。
- **没有清理或过期任务**：在整个后端代码里搜索定时任务，只有索引队列的轮询器，没有任何任务会删除或归档
  `ask_log`/`ask_retrieval_hit` 的旧记录。问题原文会一直留在数据库里，除非运维手动删除。

如果客户要求"问题不长期保留"，这是当前默认行为**做不到**的地方，需要额外加一个保留期限/清理任务——这不
在本次交付范围内，是一个待实现项，不是已经做了只是没写文档。

### 用户反馈表（`ask_feedback`）✅ —— 评分是枚举，但附带原因是自由文本

`rating` 是枚举，但 `reason` 字段（≤500字符）是用户自己写的文字，不是结构化元数据——如果客户要求"数据库
里只有元数据，没有用户撰写的内容"，这个字段是例外，需要单独说明或在交付时讨论是否要屏蔽/脱敏。

## 4. 上传的文档 ✅

文件按原样（未加密）存在 `UPLOAD_STORAGE_DIR` 指向的磁盘目录（`FileStorageService.java:22-45`），文件名是
`<documentId>.<ext>`。**代码里没有任何加密逻辑**——如果需要静态加密，要依赖底层磁盘/卷的加密能力，这个代
码库不提供。删除文档时是真删（`Files.deleteIfExists`，`FileStorageService.java:47-61`），不是进回收站，发
生在数据库事务提交之后。

## 5. 云端 Provider 的 API Key ✅

- 通过 `AI_API_KEY`（聊天）/ `AI_EMBEDDING_API_KEY`（Embedding）两个环境变量配置，只放 HTTP 请求头，不写
  进请求体、不写日志、不存数据库（`AppProperties` 只是配置对象，没有任何实体或日志引用这两个字段）。
- **一个操作上的坑**：如果没单独设置 `AI_EMBEDDING_API_KEY`，它会自动回退使用 `AI_API_KEY`
  （`application.yml:133`）。如果客户用"云端聊天模型 + 本地 Embedding"这种组合，必须显式把
  `AI_EMBEDDING_API_KEY` 设为空，否则云端的聊天 Key 会被发给本地 Embedding 服务（已在
  [DeepSeek 云端对照报告](../../evaluation/reports/cloud-provider-deepseek-local-2026-09-26.md)里按这个方式正确配置过）。

## 接入真实数据前该做什么

本文件只覆盖"代码会做什么"。是否可以用真实客户数据，仍然要走
[DEPLOYMENT_RUNBOOK.md 的 Provider 验收清单](../DEPLOYMENT_RUNBOOK.md#provider-侧模型-io-与隐私边界)（Provider
书面的数据保留承诺、日志可关闭或脱敏、数据驻留与加密、可审计的删除路径、用合成数据先跑一遍评测）。如果客
户对 `ask_log` 的长期保留或上传文件未加密有顾虑，这是交付前需要双方确认的点，见
[验收手册](ACCEPTANCE_HANDBOOK.md)。
