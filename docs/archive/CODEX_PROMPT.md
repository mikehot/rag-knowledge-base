# 发给 Codex 的执行提示词

> 用法：把「==== 提示词开始 ====」到「==== 提示词结束 ====」之间的内容，连同 `REQUIREMENTS.md` 一起发给 Codex。Codex 能访问本机 StudioProjects。

==== 提示词开始 ====

你是这个项目的全栈执行工程师。请严格按仓库根目录 `REQUIREMENTS.md` 实现「RAG 知识库问答」MVP。该文档是唯一权威依据。

## 一句话
管理员上传文档 → 解析切片 + 向量化入库（pgvector）→ 用户自然语言提问 → 检索 Top-K 相关片段喂大模型 → 返回基于资料的中文答案 + 出处；资料外的问题不编造，提示转人工。

## 技术栈（不得擅自更换）
- Flutter（MVVM，dio+retrofit，Riverpod）
- Spring Boot 3.x（Java 17+）+ PostgreSQL + **pgvector**
- 文档解析：PDFBox / POI / 直接读
- 生成 + embedding：OpenAI 兼容 provider，支持本地（LM Studio / Ollama）与云切换；默认本地
- 部署：Docker（pgvector/pgvector:pg16）

## 目录
- `backend/`、`app/`，仓库已有 `sample_faq.md` 供端到端联调

## 强制复用 ai-weekly-report（同在 StudioProjects 下，别重写）
- Flutter `core/`（网络/错误/主题/三态）、后端 JWT/文件上传/统一响应/全局异常、`AiProvider` 抽象（**含 HttpClient 强制 HTTP_1_1 的修复**）、异步用 `@TransactionalEventListener(AFTER_COMMIT)`、Android 工具链 pin（AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.1.0）、debug `usesCleartextTraffic`。

## 执行顺序
1. 接口契约对齐（REQUIREMENTS 4.1），前后端定义模型。
2. 纵切跑通：上传→假入库→列表；提问→假答案。Flutter 两 Tab 接通。
3. 填真实逻辑：解析→切片（4.3）→ embedding → 写 chunk；提问 embedding→pgvector Top-K→阈值判断→拼提示词→LLM→答案+出处（4.4）。
4. 加固：超时/重试/兜底/限流/token 记录；相似度阈值低于配置直接转人工不调 LLM。
5. 部署：Dockerfile + compose（pgvector 镜像），README 写本地/云配置样例、embedding 维度说明、真机连法。

## 硬性要求
- 统一响应 `{code,message,data}`，错误 message 中文。
- 向量维度、Top-K、chunk 大小/重叠、相似度阈值、provider/base-url/model 全做配置项，并在 docker-compose 透传。
- Java HttpClient 必须 `.version(HTTP_1_1)`（本地服务器 HTTP/2 会挂死）。
- 资料外问题必须 found=false + 转人工，**严禁编造**。
- 任何模型异常不得导致接口/ App 崩；机密走环境变量。
- Android 必须真机能装能跑（沿用工具链 pin + debug 明文）。

## 设计原型
问答页：聊天气泡（用户右蓝、AI 左灰卡），AI 答案卡 = 答案正文 + 分隔线 + 可点「来源」标签（文档名+定位，点开看原文片段）；找不到显示转人工。知识库页：上传按钮 + 文档列表（类型图标+文件名+「N段·状态」+状态色+删除）。两页底部 Tab 切换。详见 REQUIREMENTS 第 5 节。

## 验收
对照 REQUIREMENTS 第 6 节。最终用 LM Studio（生成 + embedding 本地模型）真机端到端：上传 `sample_faq.md` → 提问命中出答案带出处；问资料外问题 → 转人工。

## 协作约定
- 改接口/设计先回写 `REQUIREMENTS.md` 再改代码。
- 未覆盖的决策点选主流默认并在 README 记录，不要停下等待。
- 完成后写 `PROGRESS.md`（已实现、已知限制、为何某些问题单测/analyze 抓不到）。

现在开始：初始化 backend/ 和 app/，定义契约与数据模型（含 pgvector 建表），然后按顺序推进。

==== 提示词结束 ====
