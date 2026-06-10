# RAG Knowledge Base MVP

企业资料问答 MVP：上传文档后异步解析、切片、embedding 入 pgvector；用户提问时检索 Top-K 片段，再调用 OpenAI 兼容 LLM 生成中文答案并返回来源。

作品集展示见 [PORTFOLIO.md](PORTFOLIO.md)。

## Modules

- `backend/`: Spring Boot 3.x + PostgreSQL + pgvector
- `app/`: Flutter App, Riverpod MVVM + dio/retrofit
- `sample_faq.md`: 端到端联调用样例知识库

## Quick Start

```bash
docker compose up -d db
cd backend
./mvnw spring-boot:run
```

默认账号：

- username: `demo`
- password: `demo123456`

默认 AI 配置面向本机 LM Studio：

- Chat base URL: `http://localhost:1234/v1`
- Embedding model: `text-embedding-nomic-embed-text`
- Embedding dim: `768`

如果后端也在 Docker 中运行，LM Studio/Ollama 在宿主机上，`AI_BASE_URL` 和 `AI_EMBEDDING_BASE_URL` 通常应改成 `http://host.docker.internal:1234/v1`。

更多配置见 [backend/README.md](backend/README.md) 和 [app/README.md](app/README.md)。
