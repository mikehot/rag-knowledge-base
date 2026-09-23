# 5–10 分钟 Demo 讲稿与执行步骤

> Demo 使用 `sample_faq.md` 和本地默认账号。若要展示员工 ACL 隔离，使用 disposable tenant 和外部凭证，不要在共享数据库中删除或覆盖真实资料。

命令示例需要 `curl` 和 `jq`；也可以用 API Client 手动保存登录 token 与 `requestId`。

## Demo 目标

让评审在一次演示中看到：文档入库、异步索引、授权回答、引用、资料外拒答、反馈、权限边界、只读 Tool/MCP，以及失败可观测性。

## 0. 启动前检查（约 1 分钟）

```bash
docker compose up -d db
curl -s http://localhost:1234/v1/models
cd backend
./mvnw spring-boot:run
```

如果使用宿主机 LM Studio，确认 `/v1/models` 返回的 Chat 和 Embedding ID 与配置完全一致。GUI 能打开不等于 API Server 已启动；详细故障排查见 [DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md)。

## 1. 登录并上传（约 1 分钟）

以下命令只适合 disposable 本地环境。不要把返回的 JWT 写入 Git 或截图公开。

```bash
BASE_URL=http://localhost:8080
LOGIN_JSON=$(curl -s "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}')
TOKEN=$(printf '%s' "$LOGIN_JSON" | jq -r '.data.token')

curl -s "$BASE_URL/api/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@sample_faq.md'
```

记录返回的 `documentId`，随后通过 `GET /api/documents` 轮询，直到文档状态为 `ready`。索引任务失败时不要重复上传；先看任务状态和失败原因，再使用授权的 retry API。

## 2. 授权回答与引用（约 1 分钟）

```bash
ASK_JSON=$(curl -s "$BASE_URL/api/ask" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"question":"设备保修期是多久？"}')
REQUEST_ID=$(printf '%s' "$ASK_JSON" | jq -r '.data.requestId')
printf '%s\n' "$ASK_JSON"
```

展示重点：`found=true`、`grounded=true`、后端生成的 `sources`、`requestId`、`timings` 和 Token 使用量。引用中的文件名和定位来自后端检索结果，不是直接信任模型生成的文件名。

## 3. 资料外拒答（约 45 秒）

```bash
curl -s "$BASE_URL/api/ask" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"question":"公司明年的股权计划是什么？"}'
```

展示 `found=false`、空 `sources` 和稳定的 `failureReason`。这证明系统会在资料不足时拒答，不把模型的常识当作企业资料。

## 4. 反馈与运营观测（约 45 秒）

用上一步响应中的 `requestId` 提交反馈。上面的示例已经将它提取到 `REQUEST_ID`：

```bash
curl -s -X PUT "$BASE_URL/api/ask/$REQUEST_ID/feedback" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"rating":"HELPFUL","reason":"引用位置清晰"}'
```

管理员或审计员可在权限允许时查询审计和受保护诊断；普通用户不能读取 Prometheus、问题正文或 Chunk 正文。

## 5. 只读 Tool / MCP 边界（约 1 分钟）

```bash
curl -s "$BASE_URL/api/agent/tools" \
  -H "Authorization: Bearer $TOKEN"

python3 evaluation/run_mcp_smoke.py \
  --base-url "$BASE_URL" \
  --credentials /private/tmp/rag-eval-admin.json \
  --output /private/tmp/mcp-readonly-smoke-demo.json
```

展示点：只能发现 `search_knowledge`、`list_documents`、`get_document_status`；身份参数覆盖会被拒绝；未认证请求返回 401；没有删除、改权限或写 OA/CRM 的 Tool。

## 6. ACL 演示（约 1 分钟，可选）

使用外部凭证和 disposable tenant 运行 `evaluation/prepare_api_fixture.py`，再用员工凭证执行 `evaluation/run_api_eval.py`。展示员工只能看到已授权 FAQ，不能列出或引用受保护策略文档；评测报告只保留聚合结果，不公开凭证、问题正文或 Token。

## 7. 失败恢复（约 1 分钟，可选）

不要在共享环境中故意破坏 Provider。用已有失败任务或 disposable 环境演示：查看 index task 的状态、attempt、失败原因和耗时，然后调用人工 retry；恢复后确认文档回到 `ready`。若只展示文档替换，说明新版本在新索引成功前不会替换旧的可检索版本。

## 8. Flutter 设备演示（已完成本地验收）

这一步只使用 disposable 本地账号和 `sample_faq.md`，不连接真实客户数据。开始设备验收前先确认后端和 LM Studio API 已启动，再在另一个终端执行：

```bash
adb devices
adb reverse tcp:8080 tcp:8080
cd app
flutter pub get
dart run build_runner build
flutter run -d <device-id> \
  --dart-define=API_BASE_URL=http://localhost:8080 \
  --dart-define=APP_USERNAME=demo
```

设备级最小验收顺序：

1. 登录页显示，输入 disposable `demo` 账号后进入问答页。
2. 选择本轮新准备的唯一测试 Markdown 文件上传，在知识库页看到 `processing` 到 `ready`；不要用已经存在的同名/同内容文档推断新上传成功。
3. 提问“设备保修期是多久？”，看到引用来源、`grounded` 状态、耗时/Token 和反馈按钮。
4. 提问资料外问题，确认回答显示拒答状态且没有来源卡片。
5. 返回知识库页，确认就绪文档菜单包含停用和重建索引；失败文档只演示重建索引，不重复上传。
6. 点击退出登录，确认回到登录页；再次启动应用时只验证会话恢复，不截图 token。

### 管理运营 UI（本地实现，待设备端到端验收）

1. 用文档所有者或具备 `MANAGE` 的 disposable 管理账号打开文档菜单 →「管理文档权限」，核对主体/权限列表。
2. 授予测试员工 `READ`，确认员工能检索并引用目标文档；随后撤权，再次查询必须无来源且不得引用该文档。
3. 打开顶部「索引任务」，检查状态、attempt、失败原因和耗时；对一个可控的失败任务只点击一次「安全重试」，确认任务按后端状态机恢复并最终到达成功/ready。
4. 用无系统管理员角色但有文档 `MANAGE` 的账号确认 ACL 列表仍可查看，若主体目录 API 返回 403，界面应保留已加载 ACL 并明确提示不能授权新主体；不得绕过后端权限。

以上是本地 Flutter 接线后的验收用例，尚未作为设备演示结果。ACL 即时撤权、任务恢复与零泄漏必须有新鲜 API/设备证据后才能在作品集里标为通过。

2026-09-23 在 Android 16/API 36 真实设备独立验证了 Flutter 应用内上传：通过系统文件选择器选择本轮新建的唯一文件 `rag-device-upload-check-20260923.md`，应用回到知识库页先显示 `入库中`，随后显示 `1 段 · 就绪`；同一 demo 账号的只读文档 API 轮询也观察到 `processing` 后变为 `ready`、chunkCount=1。验收后已删除该临时后端文档及设备文件。此前记录的 `sample_faq.md` 上传归因不作为本次上传证据：它在本轮操作前已存在于文档清单且为 `ready`，所以仅凭重选后看到 ready 不能证明这次产生了新上传。

此前问答引用、耗时/Token、`HELPFUL` 反馈、资料外拒答、文档菜单和退出登录截图记录仍是本地临时证据，路径分别为 `/private/tmp/rag-ask-full-query-result.png`、`/private/tmp/rag-ask-outscope-result.png`、`/private/tmp/rag-sample-menu.png`、`/private/tmp/rag-logged-out.png`；不应直接当作公开 Demo 资产，也不替代本轮单独核实的上传证据。

截图或录屏不得包含 JWT、密码输入内容、LM Studio Developer Logs、真实客户文档正文或设备序列号；录屏和公开部署仍是独立门槛。

## Demo 结论

这不是“模型回答了几个问题”的展示，而是一个带有入库、ACL、引用、拒答、审计、评测和恢复边界的企业知识库闭环。当前仍应称为 V0.1 建设中的可验证项目，不应描述为生产级 Agent 平台。
