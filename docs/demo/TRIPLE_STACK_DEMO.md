# 三端联动 2 分钟演示剧本（Flutter + Admin Web + MCP）

> 目标：在 120 秒内向面试官、FDE 客户或 Upwork 客户完整证明：
> 1. 权限在 SQL 检索层强制隔离（越权文档永不进模型上下文）；
> 2. 移动端（Flutter）支持真实 SSE 流式 Agent 工具调用与多轮记忆；
> 3. 管理后台（Vue 3）具备拒答/差评复核闭环（Human-in-the-Loop）与检索诊断；
> 4. 外部 AI 客户端（Claude Desktop / Claude Code）可通过标准 MCP 协议安全接入。

---

## 一、演示前启动顺序（4 条命令）

```bash
# 1. 启动 PostgreSQL + Java 后端 (8080) + rag-agent-python (8090, 开启 SQLite 会话持久化)
cd /Users/jackychou/StudioProjects/rag-agent-python
RAG_AGENT_SESSION_DB_PATH=/tmp/rag_agent_sessions.db \
  docker compose -f docker-compose.yml -f docker-compose.with-kb.yml up -d

# 2. 验证 MCP 标准 stdio 握手
cd /Users/jackychou/StudioProjects/rag-mcp-server
uv run python scripts/verify_mcp_stdio.py

# 3. 启动 B 端运营与复核台 (5173)
cd /Users/jackychou/StudioProjects/rag-knowledge-base/admin-web
npm run dev

# 4. 启动 Flutter 客户端 (真机先执行 adb reverse tcp:8080 tcp:8080 && adb reverse tcp:8090 tcp:8090)
cd /Users/jackychou/StudioProjects/rag-knowledge-base/app
flutter run --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=AGENT_BASE_URL=http://localhost:8090
```

---

## 二、120 秒录屏分镜脚本

| 时间 | 画面 | 演示动作 | 核心卖点（字幕/口播） |
|---|---|---|---|
| `0:00–0:25` | Flutter App（Agent 流式模式） | 用普通员工账号登录，提问一个有权限文档的问题 → 展示 `search_knowledge · ok` 工具芯片 → 实时流式出字 → 展示引用文档芯片；紧接着追问一轮“那默认值/例外情况呢？”验证多轮上下文 | **Flutter 三端原生质感 + SSE 流式 Agent + 多轮记忆** |
| `0:25–0:50` | Flutter App（切换直连模式 & 权限隔离） | 问一个无权限或知识库未覆盖的问题 → 系统明确拒答（`found=false`，不编造）并点击「没帮助」提交反馈 | **Fail-Closed 不幻觉 + 用户反馈信号采集** |
| `0:50–1:25` | `admin-web`（http://localhost:5173） | 切到「拒答与差评复核」Tab，刷新看到刚产生的 `NOT_FOUND` / `NOT_HELPFUL` 记录 → 点击「检索诊断」展示 Top-K 分数与阈值对比 → 点「去补充文档」上传缺失文档 | **Human-in-the-Loop 运营闭环 + 检索可解释性** |
| `1:25–1:50` | Claude Desktop / Claude Code（`rag-mcp-server`） | 用同一普通员工 Token 配置 `rag-mcp-server`，在外部 Agent 中提问 → 只能检索到被授权文档，无法越权读取管理文档 | **标准 MCP 协议互操作 + 调用方身份透传** |
| `1:50–2:00` | 终端 `audit-events` / 总结页 | 展示 `admin-web`「安全审计日志」Tab 中的 `ALLOW` / `DENY` 记录 | **企业级可审计、可追溯、可私有化部署** |

---

## 三、录屏脱敏检查清单

- 录屏前确认使用合成测试文档与演示账号，不出现真实客户名称、真实手机号/密钥、或本地模型敏感日志路径。
- 若使用本地模型（LM Studio），确保关闭 Thinking 或在字幕中注明测试模型与延迟口径。
