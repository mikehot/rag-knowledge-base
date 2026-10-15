# 验收评测手册

> **English summary.** A procedure for the client's own reviewer to verify this system against their own
> documents and questions before accepting delivery — not a repeat of our past evaluation numbers on
> synthetic data. It covers permission isolation, refusal-on-missing-evidence, citation checking,
> revocation, running your own evaluation set with our tooling, and how to read our historical reports
> (and their limits). Deployment/health acceptance (is it running) is covered separately in
> [DEPLOYMENT_RUNBOOK.md §4](../DEPLOYMENT_RUNBOOK.md#4-健康检查与验收) — this handbook only covers answer
> quality and permission behavior.
>
> 本文件只写验收步骤和结论，具体数字一律链接到 [PORTFOLIO.md](../../PORTFOLIO.md)，不在这里重复一份——数字
> 以 PORTFOLIO.md 为准，避免两处各改一半。最后更新：2026-10-15。

## 1. 这份手册覆盖什么，不覆盖什么

**覆盖**：答得对不对、权限守不守得住、资料外会不会乱答、撤权后会不会还在用旧答案、怎么用你自己的文档验
证——这些都应该用**客户自己的数据**测，不是只看我们在合成语料上的历史报告。

**不覆盖**：系统能不能跑起来（部署、数据库连接、健康检查）。那部分见
[DEPLOYMENT_RUNBOOK.md §4 健康检查与验收](../DEPLOYMENT_RUNBOOK.md#4-健康检查与验收)，按那个清单先确认服
务正常，再开始本手册的验收。

## 2. 开始之前

- 系统已部署并通过 DEPLOYMENT_RUNBOOK §4 的健康检查。
- 至少准备两个测试账号：一个能看到待测文档的普通用户，一个**看不到**的用户（用来测权限隔离），加一个有
  文档管理权限的账号（用来测撤权）。
- 准备一小批**贴近真实场景**的文档和问题——不需要是生产数据，但要是客户自己业务领域的文档，不是我们仓库
  自带的 `sample_faq.md`。这是本手册和 DEPLOYMENT_RUNBOOK §4 最大的区别：那边验证"系统工作"，这边验证
  "系统在**你的**数据上工作"。

## 3. 验收项清单

每一项都写明怎么测、预期结果是什么、不通过说明什么。

### 3.1 权限隔离

用看不到该文档的账号去问一个该文档才能回答的问题。

- **预期**：`found=false`，不编造答案；如果直接访问该文档，返回 404（而不是"无权限"这种会暴露文档存在
  的提示）。
- **不通过说明**：权限检查失效，必须在验收通过前修复，这是设计上最核心的承诺——权限是检索条件，不是界面
  过滤。

### 3.2 资料外拒答

问一个文档库里确实没有答案的问题（比如问一个完全不相关的话题）。

- **预期**：`found=false`，`sources=[]`，回答里不出现编造的内容。
- **不通过说明**：模型凭常识编了答案，属于设计失效，需要检查 Provider 配置或 Prompt 边界。

### 3.3 引用可核

问一个有答案的问题，拿到回答后，打开 `sources` 里列出的文件名和定位（locator/chunk 编号），确认那段原文
真的支撑这个回答。

- **预期**：引用的分块内容确实能推出回答里的结论；后端已经做过"引用的分块是否真的支撑答案"的校验，不支
  撑时会改引真正支撑的分块。
- **不通过说明**：引用对不上原文，需要排查是检索问题还是引用校验逻辑问题。

### 3.4 撤权生效

用管理员账号先授权某个测试用户访问某份文档，让该用户问一个只有这份文档能回答的问题（确认答对），然后管
理员撤销这个授权，**立即**让同一用户重新问同一个问题。

- **预期**：撤权后的提问应该拒答（`found=false`），不会用旧答案或缓存结果。
- **不通过说明**：权限变更没有实时生效，是需要立即修复的安全问题。
- 这条验收方法和我们内部评测撤权场景的方法一致，见
  [enforce-retrieval 报告](../../evaluation/reports/enforce-retrieval-local-2026-10-15.md)（注意：那份报
  告测的是 Python Agent 层的撤权场景，不是本应用自身；本应用自身的撤权有独立的 PostgreSQL 集成测试和全新
  clone 上的 curl 实测，见 [PORTFOLIO.md 第一个关键设计决策](../../PORTFOLIO.md#三个关键设计决策)）。

### 3.5 用自己的文档和问题跑一遍完整评测（最重要的一条）

**你不必只信我们报告里的数字**——评测工具是开源在这个仓库里的，可以直接用来测你自己的文档。步骤：

1. 上传你的文档，拿到真实的文档 ID 和分块情况。
2. 按 `evaluation/datasets/corpus_v2.jsonl` 的格式写一份你自己的问题集（JSON Lines，每行一个用例：
   `id`、`question`、`answerable`、`expected_answer_points`（每个点给出可接受的 `match_any` 关键词）、
   `expected_source_documents`、`acting_user`、`forbidden_documents`、`expected_behavior`）——不用很多，
   十几到几十道能覆盖你最关心的场景就够。
3. 准备一份凭证文件（JSON，按 `acting_user.id` 映射到真实账号密码），**不要提交进仓库**。
4. 运行：
   ```bash
   python3 evaluation/run_api_eval.py --base-url http://your-host:8080 \
     --dataset your_questions.jsonl --credentials your_credentials.json \
     --output your_results.jsonl
   python3 evaluation/run_eval.py --dataset your_questions.jsonl --responses your_results.jsonl
   ```
5. 看结果：可答题命中率、拒答是否正确、ACL 是否泄漏。这是**你的数据上的真实数字**，不是我们合成语料的
   历史结果。

### 3.6 故障恢复

临时让模型服务不可用（比如停掉 LM Studio 或指向一个错误端口），再提问。

- **预期**：返回稳定的失败状态，不是 500 崩溃，也不是卡死不回应。
- **不通过说明**：故障处理不完整，需要检查超时和降级逻辑。

## 4. 怎么读我们的历史评测报告

[evaluation/reports/README.md](../../evaluation/reports/README.md) 是全部历史报告的索引，
[PORTFOLIO.md 的"评测结果与已知局限"](../../PORTFOLIO.md#评测结果与已知局限)是当前结论的汇总。读之前先知
道这些数字的边界：

- **合成数据，不是你的数据**。题目是基于我们自己放进去的文档写的，不代表在你的文档上会得到同样的分数。
- **单机、小样本**。所有数字来自本地一台机器、几十道题，统计意义有限；报告里明确标注了哪些差异是"在波动
  范围内"（`run_repeated_eval.py compare` 工具的 WITHIN VARIATION / DISTINGUISHABLE 判定），不是每次数字
  变化都代表真实差异。
- **历史数字会变**。同一套配置在不同天、不同重启之间测出的分数有过几分的波动（见
  [corpus-v2 报告的"复现差距"附记](../../evaluation/reports/corpus-v2-paraphrase-local-2026-10-13.md)），
  原因尚未完全查明。引用数字时请带上测量日期。

这些报告展示的是**我们怎么做验收和对比**（方法本身），不是"你的系统会得到这些确切分数"的承诺——这也是第
3.5 节建议你用自己的数据重跑一遍的原因。

## 5. Agent 工程证据

如果你关心的不只是"RAG 答得准不准"，还有"这套系统背后的工程是否扎实"，这里是可以直接核实的证据：

| 维度 | 证据 |
|---|---|
| 工具调用可靠性 | Python Agent 层通过 MCP 只读 Tool 接入知识库，复用同一套身份、ACL、调用预算和审计；参数校验失败、调用超时都有明确的失败分类，不会把失败当成功处理 |
| 评测严谨性 | `run_repeated_eval.py` 要求同一配置至少跑 3 轮才允许下"可区分"的结论，范围不重叠才算数——不是跑一次就报数字；最近一次跨模型对比（Qwen vs Gemma）见 [qwen3.8-27b-vs-gemma-corpus-v2-local-2026-10-15](../../evaluation/reports/qwen3.8-27b-vs-gemma-corpus-v2-local-2026-10-15.md) |
| 轨迹与可观测性 | 每次问答都有 `requestId`、分段耗时（embedding/retrieval/generation）、Token 用量、失败分类；Agent 调用记录每一步工具调用的结果（`outcome`） |
| 人机协作（HITL） | 任何不合规的模型输出（非法 JSON、引用越界、截断）一律 fail-closed 转人工，不编造一个"看起来对"的答案 |
| 权限与撤权的安全验证 | 授权→可答、撤权→拒答，Java 应用层有 PostgreSQL 集成测试，Python Agent 层有专门的撤权场景评测（3/3 泄漏在关闭强制检索时发生、0/3 在开启时发生），见 [enforce-retrieval 报告](../../evaluation/reports/enforce-retrieval-local-2026-10-15.md) |
| 成本与延迟 | 本地模型与云端模型（DeepSeek）的质量、延迟、Token 成本都做过并排对比，见 [云端对照报告](../../evaluation/reports/cloud-provider-deepseek-local-2026-09-26.md) |

## 6. 验收不通过时怎么办

这套系统**不承诺百分之百答对**——资料不足时拒答、权限不足时拒答，这是设计意图，不是缺陷。验收关注的是：
该拒答的时候有没有拒答、该答对的时候答得对不对、权限有没有被真正守住。如果 3.1-3.4 任何一项不通过（权
限、拒答、引用、撤权），这些是需要在交付前修复的问题，不是可以商量的质量权衡；3.5 的自测数字如果低于预
期，需要一起看是检索问题还是题目本身的设计问题（参考 PORTFOLIO.md 里的失败案例分析方法）。
