# 作品集说明：RAG 知识库问答

## 一句话定位

企业资料问答 MVP：上传 FAQ、制度、产品文档等资料，系统切片入库并向量检索，用户提问时返回中文答案和来源。

## 适合展示的能力

- RAG 后端：Spring Boot 3、PostgreSQL、pgvector、文档解析、切片、embedding、Top-K 检索。
- AI 集成：OpenAI-compatible chat + embedding provider，支持本地 LM Studio / Ollama 或云端模型。
- Flutter App：问答 Tab、知识库 Tab、上传、状态轮询、来源标签弹窗。
- 工程化：JWT、统一响应、上传白名单、全局中文错误、Docker Compose。
- 低成本私有化雏形：可在客户本机/内网部署模型和向量库。

## 核心演示流程

1. 启动 pgvector 数据库和后端。
2. 上传 `sample_faq.md`。
3. 等待文档解析、切片、embedding 入库。
4. 提问资料内问题，答案返回 `found=true` 并显示来源。
5. 提问资料外问题，系统返回转人工提示。

## 验证证据

- `backend ./mvnw test` 通过。
- `app flutter analyze` 通过。
- `app flutter test` 通过。
- `app flutter build apk --debug` 通过。
- `docker compose config` 通过。

## 当前限制

- 尚未在当前环境完成 LM Studio/Ollama + pgvector 的真机端到端联调。
- MVP 是单轮问答，不做多轮指代消解、reranker、流式输出、多租户或权限分级。
- 后端测试 profile 不连接真实 pgvector，维度匹配和 HNSW 索引需运行时验证。

## 接单价值

这个项目适合作为“企业资料智能问答 / 内部知识库”样板，可给售后 FAQ、产品手册、培训资料、门店制度等场景复用。它也是客服工单系统的 RAG 基座。
