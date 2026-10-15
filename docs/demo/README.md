# 演示视频 / Demo video

[`rag-agent-demo.mp4`](rag-agent-demo.mp4)：**3 分 47 秒，无声，仅终端画面**，屏幕上的中英文说明就是讲解。2026-10-15 录制，
1714×922，H.264，2.5 MB。

A silent, terminal-only screen recording (3 min 47 s). The on-screen captions are the narration.

## 它展示什么

| 时间（约） | 内容 |
|---|---|
| 0:00–1:56 | **用户视角**：四个角色看到的文档数量不同（24 / 2 / 2 / 0）；有权限的人得到带出处的回答；没有权限的人检索到 0 个片段；有权限的人问了别的主题被拒答；Java 单次检索回答不了的跨文档问题，Python 智能体拆成两次检索答全；一个冒充管理员的提示词 |
| 1:56–2:18 | **证据与局限**：工具调用评测的结果，以及它**不能**说明的部分（同一配置 10-13 是 43/45，10-14 是 38/45，重启后 39/45，落差主要是措辞撞上字面评分，措辞为何变化未查明） |
| 2:18–3:47 | **管理员视角**：上传一份新的合成文档；授权前新员工什么也看不到；授予 READ 后带出处回答；撤权后立刻又被拒答；清理 |

## 它不展示什么

- **没有 Flutter App 画面**，也不是真机；A1 的真机抽测仍然开放（见 [PROGRESS.md](../../PROGRESS.md)）。
- **没有讲解语音**。
- **不展示会话记忆中途撤权**：管理员那一段是不带会话提问的；记忆会话里撤权是否生效没有强制、没有测试。
- 画面里的数字是 **2026-10-14/15 在一台机器、一个本地模型、一人编写的评测集上的测量**，不是产品指标，也没有任何
  生产部署或真实用户数据。

## 数据

- 28 份公开文档：24 份 Kubernetes 中文文档（CC BY 4.0，署名见
  [evaluation/corpus/k8s-zh/ATTRIBUTION.md](../../evaluation/corpus/k8s-zh/ATTRIBUTION.md)）和 4 部中国法律
  （见 [evaluation/corpus/laws-zh/ATTRIBUTION.md](../../evaluation/corpus/laws-zh/ATTRIBUTION.md)）。
- 管理员那一段上传的是一份**合成**的差旅报销制度，演示结束即删除。
- 全部在本机运行（Gemma 4 26B + bge-m3，经 LM Studio），**没有托管环境，没有云端模型**。

## 怎么复现

驱动脚本和分镜在 [rag-agent-python](https://github.com/mikehot/rag-agent-python) 的 `scripts/demo.py`、
`scripts/demo_admin.py` 和 `docs/DEMO_VIDEO.md`。需要本地模型服务和 `scripts/eval_stack.sh up` 拉起的评测栈；
脚本在 Thinking 开着时会拒绝运行。

## 这段视频是怎么做出来的（不作修饰）

由三段原始录屏拼成：A 部分（去掉最后一幕）、重录的最后一幕（原片段只有约 10 秒，最后一帧定格延长了 12 秒让证据表读得完，
内容本来就静止）、B 部分。没有改动任何画面内容。成片我按**每 8–10 秒一帧**抽帧检查过（不是逐帧）：没有发现 token、密码、
用户名、凭证文件路径或标题栏；画面里偶尔有鼠标指针。
