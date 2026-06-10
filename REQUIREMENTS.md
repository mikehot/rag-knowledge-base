# RAG 知识库问答 — 需求文档（MVP）

> 交付对象：执行方（Codex）。本文档自包含。
> 版本：v1.0 · 日期：2026-06-10
> 姊妹项目：`ai-weekly-report`（AI 经营周报）——本项目大量复用其后端骨架与工程经验，见第 9 节。

---

## 1. 项目目标

把企业资料（产品手册 / FAQ / 制度文档）变成一个**能问答的知识库机器人**：

> 管理员上传文档 → 系统切片 + 向量化入库 → 用户用自然语言提问 → 系统检索相关片段喂给大模型 → 返回**基于资料的中文答案 + 出处引用**；资料里没有的，明确说"未找到，建议转人工"，**不编造**。

核心价值：减少重复客服咨询、新人快速查资料、答案有据可查。

### MVP 边界（必须做）
- 文档上传：PDF / Markdown / TXT / DOCX（至少 PDF + TXT/MD 必须，DOCX 尽量）
- 解析 → 切片（chunk）→ embedding → 存入向量库（pgvector）
- 文档列表 / 删除 / 入库状态展示
- 问答：检索 Top-K 相关片段 → 拼提示词 → 调大模型 → 答案 + 出处
- 找不到相关内容时不编造，提示转人工
- 基础鉴权（JWT；MVP 单用户即可）
- AI（生成 + embedding）provider 可切换：本地（LM Studio / Ollama）/ 云（Claude / OpenAI 兼容）
- 失败兜底、超时、token 记录、限流

### 明确不做（Out of Scope，v2 候选）
- 多知识库 / 多租户隔离
- 流式打字机输出（MVP 一次性返回即可，但接口预留）
- 对话多轮上下文记忆（MVP 单轮问答；可记录历史但不做指代消解）
- 网页嵌入 widget、公众号/企业微信接入
- 文档版本管理、权限分级
- 重排序（reranker）模型——MVP 用向量相似度 Top-K 即可，预留扩展

---

## 2. 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 移动端 | Flutter (stable) | MVVM；dio + retrofit；Riverpod |
| 后端 | Spring Boot 3.x (Java 17+) | REST API |
| 数据库 | PostgreSQL + **pgvector** 扩展 | 文档、切片、向量存储与检索 |
| 文档解析 | Apache PDFBox（PDF）/ Apache POI（DOCX）/ 直接读（TXT/MD） | |
| 向量检索 | pgvector 余弦相似度（`<=>`） | Top-K 检索 |
| 大模型（生成） | AiProvider 抽象：Claude / OpenAI 兼容（本地 LM Studio/Ollama 或云 DeepSeek） | 复用周报项目实现 |
| Embedding | EmbeddingProvider 抽象：OpenAI 兼容 `/v1/embeddings`（本地 `text-embedding-nomic-embed-text` 或云） | 见 4.4 |
| 部署 | Docker + Nginx + HTTPS | pgvector 用 `pgvector/pgvector:pg16` 镜像 |

> **本地优先**：默认推荐本地——生成走 LM Studio（如 `google/gemma-4-26b-a4b`），embedding 走 LM Studio 的 `text-embedding-nomic-embed-text`（向量维度 **768**，建表时按此设定，且做成配置项）。客户要云就改配置。

---

## 3. 系统架构

```
                    ┌── 上传：解析→切片→embedding→存向量 ──┐
Flutter App ──HTTPS──┤                                      ├── PostgreSQL + pgvector
                    └── 提问：embedding→向量检索→拼提示词→LLM ┘            │
                                         │                                 │
                                         └──────── AI Provider（生成 + embedding，本地/云）
```

### 两条核心链路

**A. 文档入库（异步）**
1. App 上传文件 → 后端存原始文件、建 `document(status=processing)`，**立即返回 documentId**。
2. 后台异步：解析正文 → 按规则切片 → 对每片调 embedding → 批量写入 `chunk`（含向量）。
3. 全部完成 → `document(status=ready, chunk_count=N)`；失败 → `status=failed` + `error_msg`。
4. App 轮询 `GET /documents/{id}` 或列表刷新看状态。

**B. 提问（同步）**
1. App `POST /ask {question}`。
2. 后端：对 question 调 embedding → 在 `chunk` 里按向量相似度取 Top-K（如 K=5，可配）。
3. 若 Top-K 最高相似度低于阈值（可配）→ 直接返回"未找到相关信息，建议转人工"，不调 LLM。
4. 否则：把"问题 + Top-K 片段（含来源）"拼成提示词 → 调 LLM → 得到答案。
5. 返回答案 + 引用到的来源（文档名 + 位置/片段）。

> 提问链路可能耗时数秒（embedding + 检索 + LLM）。MVP 用同步请求 + App 端 loading 即可；超时上限要大于本地模型响应时间（参考周报项目教训，本地模型可能 15s+）。

---

## 4. 后端详细设计

### 4.1 接口契约

统一响应：`{ "code": 0, "message": "ok", "data": {...} }`，非 0 为业务错误，message 为可展示中文。除登录外均需 `Authorization: Bearer <token>`。

```
POST /api/auth/login                 → { token, expiresIn }
POST /api/documents/upload           multipart: file（必填）→ { documentId, status:"processing" }
GET  /api/documents                  → { items:[{ documentId, filename, fileType, status, chunkCount, errorMsg, createdAt }] }
GET  /api/documents/{id}             → 同上单条
DELETE /api/documents/{id}           → { deleted:true }（同时删除其所有 chunk）
POST /api/ask                        { question }
                                     → {
                                         answer: "……",            // 找不到时为转人工提示
                                         found: true|false,         // 是否检索到足够相关内容
                                         sources: [                 // found=false 时为空数组
                                           { documentId, filename, locator:"p8"|"chunk#12", snippet:"被引用的原文片段" }
                                         ],
                                         tokenUsage: 1234
                                       }
```

校验：上传文件大小上限（如 20MB）、扩展名白名单（pdf/txt/md/docx）、question 非空。

### 4.2 数据模型（PostgreSQL + pgvector）

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
  id UUID PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE document (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES app_user(id),
  filename TEXT NOT NULL,
  file_type TEXT,                 -- pdf/txt/md/docx
  file_path TEXT,
  status TEXT NOT NULL DEFAULT 'processing',  -- processing/ready/failed
  chunk_count INTEGER DEFAULT 0,
  error_msg TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE chunk (
  id UUID PRIMARY KEY,
  document_id UUID REFERENCES document(id) ON DELETE CASCADE,
  seq INTEGER,                    -- 片段顺序
  locator TEXT,                   -- 定位信息，如 "p8" 或 "chunk#12"
  content TEXT NOT NULL,          -- 片段正文
  embedding vector(768),          -- 维度做成配置项，默认 768（nomic-embed-text）
  created_at TIMESTAMPTZ DEFAULT now()
);

-- 向量近邻索引（cosine）
CREATE INDEX idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_document ON chunk(document_id);
```

> 向量维度必须与所选 embedding 模型匹配，做成配置项 `AI_EMBEDDING_DIM`。换模型要重建库——README 写明。

### 4.3 切片（chunking）策略

- 按字符/标记长度切，建议 chunk 大小 ~500-800 字、重叠 ~80-100 字（可配 `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP`）。
- 优先按段落/标题边界切，避免把一句话切断。
- 每个 chunk 记录 `locator`（PDF 用页码 `pN`，纯文本用 `chunk#N`）。
- 中英文都要正确切（注意中文无空格）。

### 4.4 AI / Embedding 集成（复用 + 扩展周报项目）

- **生成 provider**：直接复用周报项目的 `AiProvider` 抽象（Claude / OpenAI 兼容），含 HTTP/1.1 修复、超时、重试、token 记录、失败兜底。
- **新增 EmbeddingProvider 抽象**：OpenAI 兼容 `POST {base-url}/embeddings`，body `{ model, input:[...] }`，解析 `data[].embedding`。同样支持本地（LM Studio/Ollama）与云。批量 embedding 要分批（如每批 ≤32 条）并发或顺序，带重试。
- **关键复用**：Java `HttpClient` 必须 `.version(HTTP_1_1)`（见周报项目 `OpenAiCompatibleReportService` 与 AI-Vault 笔记，本地服务器 HTTP/2 会挂死）。

**提问提示词模板（示意）**：
```
你是知识库问答助手。只能根据下面提供的资料片段回答用户问题，不得编造。
若资料不足以回答，请直接回复："未找到相关信息，建议转人工。"

【资料片段】
[1] (来源：产品手册.pdf p8) ……片段正文……
[2] (来源：售后FAQ.md) ……片段正文……

【用户问题】
{question}

请用中文简洁回答，并在末尾不要重复来源（来源由系统单独展示）。
```

**健壮性（必须）**：
- embedding / LLM 调用超时（默认本地 120s，可配）、失败重试、缺 key 友好报错、token 记录、每用户每日提问限流。
- 检索相似度阈值 `RAG_SIMILARITY_THRESHOLD` 可配；低于阈值不调 LLM，直接转人工提示（省钱 + 防幻觉）。
- 所有机密走环境变量，不硬编码。

---

## 5. Flutter App 设计规格

> 一个 App，底部两个 Tab：「问答」「知识库」+ 登录。MVVM，三态（加载/失败/重试）。设计风格：扁平、白底卡片、克制留白；语义色：用户气泡/来源标签用蓝色强调，成功绿色、处理中橙色、错误红色。原型见随附说明，按以下文字规格实现。

```
lib/
  core/            // 网络、错误处理、主题、基础 widget（可从 ai-weekly-report 搬）
  data/
    api/           // 5 个接口
    models/        // Document, ChunkSource, AskAnswer
  features/
    ask/           // 问答页 + ViewModel
    documents/     // 知识库页 + ViewModel（上传 + 列表 + 删除 + 轮询状态）
```

### 页面 ① 问答页（核心）
- AppBar：标题「智能问答」。
- 主体：聊天气泡流。用户消息靠右（蓝底），AI 答案靠左（浅灰卡）。
- AI 答案卡结构：答案正文 + 分隔线 + 「来源」区（每个来源是可点的小标签：文档名 + 定位，如 `产品手册.pdf · p8`）。点来源可弹出该片段原文（snippet）。
- 找不到时：答案卡显示「未找到相关信息，建议转人工。」，无来源标签。
- 底部输入框 + 发送按钮；发送后显示"思考中"loading（本地模型可能十几秒，不能让用户以为卡死）。
- 状态：空态（引导"上传文档后即可提问"）、思考中、答案、失败可重试。

### 页面 ② 知识库页
- AppBar：标题「知识库」，右上「上传文档」。
- 文档列表：每条显示 文件类型图标 + 文件名 + 「N 段 · 状态」+ 状态标识（就绪绿/入库中橙带进度/失败红）+ 删除。
- 上传：选文件 → 调上传接口 → 列表出现该文档（processing）→ 轮询/刷新直到 ready 或 failed。
- 状态：加载、空态（"还没有文档，点右上角上传"）、失败可重试。

---

## 6. 验收标准

后端：
- [ ] pgvector 建库成功，5 个接口按契约实现。
- [ ] 上传 PDF + TXT/MD 能完整入库：解析 → 切片 → embedding → 写 chunk，状态流转正确。
- [ ] 提问能检索 Top-K 并基于片段生成答案，返回 sources。
- [ ] 资料外的问题（如"今天天气"）返回 found=false + 转人工提示，**不编造**。
- [ ] 删除文档级联删除其 chunk。
- [ ] embedding + 生成均支持本地（LM Studio）/云切换；HttpClient 强制 HTTP/1.1。
- [ ] 机密走环境变量；docker-compose 透传所有 AI/RAG 配置项。

Flutter：
- [ ] 两个 Tab 页按规格实现，MVVM + 三态。
- [ ] 问答页正确渲染答案 + 可点来源 + 转人工态；知识库页上传/列表/删除/状态轮询正常。
- [ ] Android 构建：沿用 ai-weekly-report 的工具链约束（AGP 8.7.x / Gradle 8.11.x / Kotlin 2.1.x）与 debug 明文 HTTP 配置，确保**真机能装能跑**。

联调：
- [ ] LM Studio（生成 + embedding 本地模型）真机端到端：上传一份样例文档 → 提问命中 → 答案带出处；问资料外问题 → 转人工。

---

## 7. 交付物
1. `backend/` Spring Boot + Dockerfile + README（pgvector 镜像、配置项、embedding 维度说明、本地/云配置样例）。
2. `app/` Flutter + README（运行、后端地址、真机连法 adb reverse）。
3. 一份样例文档（如一个产品 FAQ 的 .md）+ 几条示例问题。
4. 接口文档（springdoc-openapi）。

---

## 8. 给执行方的注意事项
- 先纵切跑通（上传→假入库→列表；提问→假答案），再填真实 RAG 逻辑。
- 向量维度、Top-K、chunk 大小、相似度阈值、provider/base-url/model 全做成配置项。
- AI/embedding 输出永远要兜底，任何模型异常不得导致接口或 App 崩。
- 不用客户敏感数据做训练用途。
- 遇到文档未覆盖的决策点，选主流默认实现并在 README 记录，不要停下等待。
- 完成后写 `PROGRESS.md`。

---

## 9. 可复用 ai-weekly-report 的部分（务必复用，别重写）
- `core/`：Flutter 网络层、错误处理、主题、三态 widget。
- 后端：JWT 鉴权、文件上传/存储、统一响应包裹 `ApiResponse`、全局异常处理、`AiProvider` 抽象与 OpenAI 兼容实现（**含 HTTP/1.1 修复**）、异步处理用 `@TransactionalEventListener(AFTER_COMMIT)` 的模式。
- Android：工具链版本 pin（AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.1.0）、debug `usesCleartextTraffic`。
- 这些在 `/Users/jackychou/StudioProjects/ai-weekly-report/` 下，可直接参考其实现。
