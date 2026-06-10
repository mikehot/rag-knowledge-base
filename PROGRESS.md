# PROGRESS

## 已实现

- 初始化 `backend/`：Spring Boot 3.5、Java 17 target、Maven wrapper、Dockerfile。
- 后端接口契约：`/api/auth/login`、文档上传/列表/详情/删除、`/api/ask`，统一响应 `{code,message,data}`。
- JWT 单用户鉴权、全局中文错误、上传白名单和大小限制。
- 文档链路：PDFBox / POI / TXT/MD 解析，按配置切片，`@TransactionalEventListener(AFTER_COMMIT)` 异步入库。
- pgvector：启动时创建 `vector` 扩展、`chunk` 表、HNSW cosine 索引；向量维度通过 `AI_EMBEDDING_DIM` 配置。
- AI：OpenAI 兼容 chat + embedding provider，支持本地/云 base URL/model/key 配置，Java `HttpClient` 强制 `HTTP_1_1`。
- 问答链路：问题 embedding、Top-K 检索、相似度阈值短路、资料外 `found=false` 转人工、不调 LLM、来源返回、token usage、每日限流记录。
- 初始化 `app/`：Flutter MVVM、Riverpod、dio+retrofit、json_serializable、file_picker。
- Flutter UI：底部「问答 / 知识库」两 Tab，聊天气泡、AI 答案卡、来源标签弹窗、上传/列表/状态轮询/删除。
- Android debug 明文 HTTP，AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.1.0。
- Docker Compose：`pgvector/pgvector:pg16` + backend，透传 RAG/AI 配置。
- README：本地/云配置样例、embedding 维度说明、真机连法。

## 已验证

- `backend`: `./mvnw test` 通过。
- `app`: `flutter analyze` 通过。
- `app`: `flutter test` 通过。
- `app`: `flutter build apk --debug` 通过；保留需求指定 AGP/Gradle/Kotlin 版本，Flutter 仅提示这些版本未来会被弃用。
- root: `docker compose config` 通过。

## 已知限制

- 尚未在当前环境实际启动 LM Studio/Ollama 与 pgvector 做真机端到端联调；需要用户本机模型服务可用后跑验收：上传 `sample_faq.md`，提问命中答案带出处，再问资料外问题。
- MVP 使用单轮问答，不做多轮指代消解、reranker、流式输出、多租户或权限分级。
- Flutter widget test 只做页面 smoke，不覆盖真实文件选择器和网络；这些依赖平台 channel 与后端运行，`flutter analyze`/单测无法保证。
- 后端测试 profile 关闭 pgvector DDL；真实 `vector(n)`、HNSW 索引、模型维度匹配只能在 PostgreSQL + pgvector 运行时验证。
