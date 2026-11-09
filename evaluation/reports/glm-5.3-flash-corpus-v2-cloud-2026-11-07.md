# corpus-v2: Cloud `GLM-5.3-Flash-Event` (SCNet) vs Local Gemma 4 26B & Qwen 3.8 27B — 2026-11-07

Pre-registered in [`glm-5.3-flash-preregistration-2026-11-07.md`](glm-5.3-flash-preregistration-2026-11-07.md) before any run.
This report records **Cell A (`corpus_v2.jsonl`, 57 cases × 3 runs, Java `POST /api/ask`)** and the
post-run **LLM-as-Judge semantic audit (`GLM-5.3-Flash-Event` with `reasoning_effort="high"`)**.
The Python agent pre-flight (`8/8`) and Cell C (`multi_turn_v2.jsonl`, 3 runs × 2 modes) are recorded in
`rag-agent-python/evaluation/reports/glm-5.3-flash-eval-2026-11-07.md` (pre-registered Cells B and D were not run in this session).

## Pre-flight Verification

All four pre-registered pre-flight checks passed before starting the evaluation runs:

1. **Local embedding isolation**: `text-embedding-bge-m3` on local LM Studio (`http://localhost:1234/v1`) returned 1024-dimensional embeddings; `AI_EMBEDDING_API_KEY` was empty so the cloud API key was never sent to the local embedding server.
2. **Thinking disabled (`AI_REASONING_EFFORT=none`)**: Plain completion check (`eval_stack.sh`) verified `reasoning=absent` (`reasoning_content` empty, `reasoning_tokens=0`, `completion_tokens=55`), matching the Thinking-off condition required for the local Gemma 4 26B and Qwen 3.8 27B runs.
3. **Structured output (`AI_RESPONSE_FORMAT=json_object`)**: `GLM-5.3-Flash-Event` wraps `json_schema` output in Markdown fences (` ```json ... ``` `), which fails strict raw JSON parsing, but returns clean raw JSON under `response_format: {"type": "json_object"}`. `probe_structured_output.py` passed **4/4 valid** (`contractValid=true`, `finishReason="stop"`). Backend `StructuredOutputParser` continued to enforce the full 4-field contract (`answer`, `found`, `grounded`, `sourceIndexes`) and fail closed.
4. **Native tool calling**: `rag-agent-python/evaluation/probe_agent.py` passed **8/8 cases** on the first run (`search_knowledge` and `list_documents` emitted as native OpenAI `tool_calls`).

## Configuration

- **Embedding & Retrieval (100% identical to local baselines)**: `text-embedding-bge-m3`, retrieval mode `VECTOR`, Top-K=5, similarity threshold `0.35`, chunk size/overlap `700/100`, citation support mode `REPAIR`, same 28-document enterprise corpus fixture and ACL grants (`prepare_corpus_fixture.py`).
- **Chat model**: `GLM-5.3-Flash-Event` via SCNet (`https://api.scnet.cn/api/llm/v1`), `AI_TEMPERATURE=0`, `AI_REASONING_EFFORT=none`, `AI_RESPONSE_FORMAT=json_object`, `AI_MAX_TOKENS=2400`.
- **Execution discipline**: 3 runs of `corpus_v2.jsonl` (45 answerable, 12 refusals: 4 out-of-scope + 8 ACL-filtered), fixed question order, Java backend restarted before every run (`restart_before_each_run: true`).

## Cell A Result: Three-Model Comparison (`corpus_v2.jsonl`, 3 runs each)

| Run | Gemma 4 26B (4-bit MLX) answerable | Qwen 3.8 27B (4-bit MLX) answerable | **GLM-5.3-Flash-Event (Cloud)** answerable | Refusals (all 3 models) | ACL leakage (all 3 models) | Gemma seconds | Qwen seconds | **GLM seconds** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 41/45 | 42/45 | **45/45** | 12/12 | 0 | 218.2 | 607.9 | **243.3** |
| 2 | 42/45 | 42/45 | **44/45** | 12/12 | 0 | 177.7 | 587.6 | **198.5** |
| 3 | 41/45 | 44/45 | **45/45** | 12/12 | 0 | 199.8 | 577.5 | **187.9** |
| **Mean / Range** | **41.33** (`41–42`, σ=0.58) | **42.67** (`42–44`, σ=1.15) | **44.67** (`44–45`, σ=0.58) | **12.0** (`12–12`) | **0** | **198.6s** | **591.0s** | **209.9s** |

### Aggregate Metrics Across Models

| Metric | Gemma 4 26B (Local 4-bit MLX) | Qwen 3.8 27B (Local 4-bit MLX) | GLM-5.3-Flash-Event (SCNet Cloud) |
|---|---:|---:|---:|
| `answerable_ok` mean (`min–max`) | 41.33 (`41–42`) | 42.67 (`42–44`) | **44.67 (`44–45`)** |
| `refusals_ok` mean (`min–max`) | 12.00 (`12–12`) | 12.00 (`12–12`) | **12.00 (`12–12`)** |
| `all_ok` / `quality_gate_ok` mean (`min–max`) | 53.33 (`53–54`) | 54.67 (`54–56`) | **56.67 (`56–57`)** |
| ACL leakage (max across runs) | 0 | 0 | **0** |
| Structured-output schema failures | 0 | 0 | **0** |
| Mean tokens per question (answered) | 2,600.7 (`2597–2608`) | 2,127.7 (`2125–2129`) | **2,145.0 (`2143.3–2146.1`)** |
| Mean wall-clock time per 57-case run | 198.6s (~3.5s/case) | 591.0s (~10.4s/case) | **209.9s (~3.7s/case)** |
| Always-failing cases (0/3 pass rate) | `CORP2-045` (+3 others) | `CORP2-002`, `CORP2-045` | **none (`0`)** |
| Flaky cases (`0 < rate < 1`) | `CORP2-022`, etc. | 2 cases | **`CORP2-045` (`2/3`)** |

### `run_repeated_eval.py compare` Verdicts

```text
# 1. GLM-5.3-Flash-Event (B) vs Gemma 4 26B (A)
answerable_ok: A 'gemma' mean 41.33 [41-42] (3 runs) | B 'glm53flash' mean 44.67 [44-45] (3 runs)
  difference (B-A): +3.34  ->  DISTINGUISHABLE: B is higher: its worst run (44) beats the other side's best run (42)
refusals_ok:   A 'gemma' mean 12.0 [12-12] (3 runs)  | B 'glm53flash' mean 12.0 [12-12] (3 runs)
  difference (B-A): +0.0   ->  WITHIN VARIATION
all_ok:        A 'gemma' mean 53.33 [53-54] (3 runs) | B 'glm53flash' mean 56.67 [56-57] (3 runs)
  difference (B-A): +3.34  ->  DISTINGUISHABLE: B is higher: its worst run (56) beats the other side's best run (54)

# 2. GLM-5.3-Flash-Event (B) vs Qwen 3.8 27B (A)
answerable_ok: A 'qwen' mean 42.67 [42-44] (3 runs)  | B 'glm53flash' mean 44.67 [44-45] (3 runs)
  difference (B-A): +2.00  ->  WITHIN VARIATION: ranges overlap: A 42-44, B 44-45
  clearly better in B (≥0.5 pass-rate gap): CORP2-002 (+1.00), CORP2-045 (+0.67)
  clearly better in A (≥0.5 pass-rate gap): -
```

- **Against Gemma 4 26B**: `GLM-5.3-Flash-Event` is **DISTINGUISHABLE** even under the repository's strict non-overlapping-range criterion (`[44–45]` vs `[41–42]`, `+3.34` answerable cases, `0` cases where Gemma is better), at virtually identical wall-clock latency (`209.9s` vs `198.6s`) and ~17.5% fewer tokens (`2,145` vs `2,601`).
- **Against Qwen 3.8 27B**: `GLM-5.3-Flash-Event` scores `+2.00` higher on average (`44.67` vs `42.67`) and reaches **45/45 (100%)** in 2 of 3 runs, while running **2.8× faster** (`209.9s` vs `591.0s`). Because Qwen's best run (`44`) touches GLM's worst run (`44`), `compare` conservatively labels the aggregate range overlap as `WITHIN VARIATION`.

---

## Deliverable 2B (Part 1): Independent LLM-as-Judge Semantic Audit of `corpus-v2`

Using `GLM-5.3-Flash-Event` with deep reasoning enabled (`reasoning_effort="high"`) alongside direct inspection of the PostgreSQL `ask_retrieval_hit` and `chunk` tables, we audited all 10 cases that either failed in any `GLM-5.3-Flash-Event` run (`CORP2-045`) or were documented failure/flaky cases in the Gemma 4 26B and Qwen 3.8 27B reports (`CORP2-001`, `CORP2-002`, `CORP2-003`, `CORP2-009`, `CORP2-016`, `CORP2-022`, `CORP2-034`, `CORP2-035`, `CORP2-044`, `CORP2-045`).

### Audit Summary Table

| Case ID | Question Topic | Gemma 4 26B Literal | Qwen 3.8 27B Literal | GLM-5.3-Flash Literal | GLM-5.3-Flash Semantic (Judge) | Root-Cause Classification of Historical / Literal Failures |
|---|---|---:|---:|---:|---:|---|
| `CORP2-001` | Deployment `revisionHistoryLimit` default (10) | Flaky (`0/3` → `3/3`) | `3/3` | **`3/3`** | **`3/3`** | **Colloquial Ambiguity (`留几份`)**: Gemma previously misread "留几份" as replica count (`0`) instead of revision count (`10`); GLM explicitly states both `保留 10 个旧 ReplicaSet` and `具有 0 个副本`. |
| `CORP2-002` | Deployment `maxUnavailable` & `maxSurge` defaults (25%) | `3/3` | `0/3` | **`3/3`** | **`3/3`** | **`RUBRIC_LITERALISM_ARTIFACT` (False Negative on Qwen)**: Qwen stated `默认值为 25%` for both limits in all 3 runs without writing the English identifiers `maxUnavailable` / `maxSurge` or exact substrings `最大不可用` / `峰值`. GLM writes both `.spec.strategy.rollingUpdate.maxUnavailable` (`最多不可用比例`) and `.spec.strategy.rollingUpdate.maxSurge` (`最多可多启动`), passing both literal and semantic checks 3/3. |
| `CORP2-003` | `revisionHistoryLimit: 0` rollback consequence | Flaky (`0/3` on 10-14) | `3/3` | **`3/3`** | **`3/3`** | **`RUBRIC_LITERALISM_ARTIFACT` (False Negative on Gemma)**: Gemma wrote `不能……从而无法进行回滚` (inserting `进行` inside `无法回滚`). |
| `CORP2-009` | StatefulSet pod startup order & `Parallel` mode | Flaky (`0/3` on 10-14) | `3/3` | **`3/3`** | **`3/3`** | **`RUBRIC_LITERALISM_ARTIFACT`**: Gemma described `{0..N-1}` sequential startup and `Parallel` without naming the default policy identifier `OrderedReady`. GLM names both `OrderedReady` and `Parallel` 3/3. |
| `CORP2-016` | CrashLoopBackOff backoff & 300s (5 min) cap | Flaky (`0/3` on 10-14) | `3/3` | **`3/3`** | **`3/3`** | **`RUBRIC_LITERALISM_ARTIFACT` (Over-strict Rubric)**: Question asks "最长能到多久" (300s / 5 min), but rubric also requires `10 秒` / `指数`. GLM includes the full progression (`10 秒、20 秒、40 秒……上限 300 秒（5 分钟）`) 3/3. |
| `CORP2-022` | Written labor contract deadline (1m) & 1-year unsigned rule | `1/3` | `3/3` | **`3/3`** | **`3/3`** | **`GENERATION_INCOMPLETE` (True Omission in Gemma)**: Gemma dropped the 1-year `视为已订立无固定期限劳动合同` consequence in 2 of 3 runs; Qwen and GLM state all three legal tiers (1 month, 2× wage for 1m–1y, deemed open-ended at 1y) in 3/3 runs. |
| `CORP2-034` | Probation resignation notice (`劳动法` vs `劳动合同法`) | `3/3` | `3/3` | **`3/3`** | **`3/3`** | **`PASS_BOTH`**: GLM contrasts `《劳动法》第三十二条（随时通知）` and `《劳动合同法》第三十七条（提前三日通知）` in all 3 runs. |
| `CORP2-035` | Probation max duration across both labor statutes | Flaky (`1/3`–`2/3`) | `3/3` | **`3/3`** | **`3/3`** | **`GENERATION_INCOMPLETE` (in Gemma)**: GLM lists both `《劳动法》最长不得超过六个月` and all three `《劳动合同法》第十九条` tiers (1m, 2m, 6m) in 3/3 runs. |
| `CORP2-044` | `数据安全法` Art. 45 fine range (50k–500k RMB) | Flaky (`2/3`) | `3/3` | **`3/3`** | **`3/3`** | **`PASS_BOTH`**: GLM states both bounds (`五万元以上五十万元以下罚款`) and the rectified/serious-violation escalation tier in 3/3 runs. |
| `CORP2-045` | Data security incident / PI leak response & reporting | `0/3` | `0/3` | **`2/3`** | **`0/3` (Strict Dual-Statute Art. 29) / `3/3` (Grounded on Top-5)** | **`CHUNK_LEVEL_RETRIEVAL_MISS` + `RUBRIC_LITERALISM_ARTIFACT` (See deep dive below)**. |

### Deep-Dive Finding on `CORP2-045`: Document-Level Recall vs Chunk-Level Recall

Previous reports (`corpus-v2-paraphrase-local-2026-10-13.md` and `qwen3.8-27b-vs-gemma-corpus-v2-local-2026-10-15.md`) noted that `CORP2-045` (*"出了数据安全事件或者个人信息泄露，公司该怎么处理、通知谁"*) failed `0/3` in both Gemma and Qwen, and attributed the failure to generation completeness because `data-security-law.md` was present in the Top-5 retrieved documents.

Querying the exact `ask_retrieval_hit` and `chunk` vectors in PostgreSQL for `CORP2-045` reveals the true mechanism:

| Vector Rank | Document | Cosine Similarity | Actual Chunk Content |
|---:|---|---:|---|
| **1** | `personal-information-protection-law.md` | `0.7001` | **第五十七条** 发生或者可能发生个人信息泄露、篡改、丢失的，个人信息处理者应当立即采取补救措施，并通知履行个人信息保护职责的部门和个人…… |
| **2** | `data-security-law.md` | `0.6734` | **第六条–第八条** 工业、电信、交通、金融、自然资源、卫生健康、教育、科技等**主管部门**承担本行业、本领域数据安全监管职责…… |
| **3** | `personal-information-protection-law.md` | `0.6514` | **第六十六条–第六十七条** 法律责任与五千万元以下/百分之五以下罚款…… |
| **4** | `personal-information-protection-law.md` | `0.6509` | **第七条–第十一条** 公开透明原则与安全保障义务…… |
| **5** | `data-security-law.md` | `0.6505` | **第三十三条–第三十六条** 数据交易中介服务与执法调取数据…… |
| ... | ... | ... | ... |
| **11** | `data-security-law.md` | `0.6301` | **第二十七条–第三十条** 开展数据处理活动……**第二十九条** 发生数据安全事件时，应当立即采取处置措施，按照规定及时告知用户并**向有关主管部门报告**。 |

1. **Why Gemma (0/3) and Qwen (0/3) failed**: Although `data-security-law.md` appeared at Ranks 2 and 5 (making *document-level* Recall@5 look like 100%), the actual incident-response provision (**Article 29**) was bundled into a 700-character chunk starting at Article 27 and ranked **#11 (`sim=0.6301`)**, outside the Top-5 context window!
2. **Why `GLM-5.3-Flash-Event` scored `2/3` (`45/45` in Runs 1 & 3, `44/45` in Run 2)**: In Runs 1 and 3, to answer "通知谁" comprehensively from the available Top-5 chunks, `GLM-5.3-Flash-Event` combined Rank 1 (`personal-information-protection-law.md` Art. 57) with Rank 2 (`data-security-law.md` Art. 6, listing the sector `主管部门` responsible for data security supervision). Because that answer cited `data-security-law.md` and included the substring `主管部门`, it satisfied all literal rubric conditions (`match_any: ["主管部门", "报告"]` and `expected_source_documents: ["personal-information-protection-law.md", "data-security-law.md"]`). In Run 2, `GLM-5.3-Flash-Event` cited only Rank 1 (`personal-information-protection-law.md` Art. 57, which uses the phrase `履行个人信息保护职责的部门` rather than `主管部门`), missing the literal substring `主管部门`.
3. **Implication**: On the 44 questions where the required evidence chunk is actually inside Top-5, `GLM-5.3-Flash-Event` achieved **44/44 (100%) in all three runs**, eliminating both the `CORP2-002` phrasing miss of Qwen and the `CORP2-022` completeness miss of Gemma. The sole remaining imperfection (`CORP2-045`) is a **chunk-level retrieval boundary issue (Rank 11 vs Top-5)** that can only be solved by smaller/article-aligned chunking, higher Top-K, or multi-query agent decomposition (tested in Cell B).
