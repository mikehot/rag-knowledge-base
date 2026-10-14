# corpus-v2 — the same 57 questions in everyday wording — local, 2026-10-13

Why: the corpus-v1 headline (44 of 45 answerable) was called optimistic because its questions were written
close to the source text. `corpus-v2` keeps every expected answer point, source document, actor, and rule of
corpus-v1 and rewrites only the question.

## How v2 was built

| Item | Value |
|---|---|
| Questions | 57 (45 answerable, 4 out of scope, 8 ACL-filtered), one rewrite per v1 question |
| Styles | 36 colloquial, 14 scenario ("合同签两年，试用期最多能约定几个月"), 4 keyword-only, 3 abbreviation/English term |
| Wording distance | mean character-bigram Jaccard v1 vs v2 = 0.25 (min 0.03, max 0.71) |
| Checks | unit tests assert that only the question differs from v1 and that every answer point still occurs literally in its source |

## Configuration

`google/gemma-4-26b-a4b-qat` (Thinking off) with `text-embedding-bge-m3`, VECTOR, Top-K=5, 700/100,
`AI_TEMPERATURE=0`, citation mode REPAIR, deterministic prompt boundary. Same database and fixture for every run.

## Result

| Setting (3 runs each) | v1 answerable /45 | v2 answerable /45 |
|---|---|---|
| one backend process, fixed order | 44, 44, 44 | 43, 43, 43 |
| backend restarted before every run | 44, 45, 43 | 43, 43, 43 |

Refusals were 12/12 and ACL leakage 0 in all 12 runs. `compare --across-datasets` on the restart runs:
**WITHIN VARIATION** (v1 43–45, v2 43–43; mean difference −1).

### Retrieval did not degrade

| | v1 | v2 |
|---|---|---|
| Questions whose evidence chunks are all in the Top-5 | 45/45 | 45/45 |
| Median top-1 similarity (answerable) | 0.725 | 0.703 |
| Refusals short-circuited by the similarity threshold | 4 | 4 |

The embedding handled the rewording without losing evidence. All the movement is in generation.

### What fails

| Case | Where | What happens |
|---|---|---|
| CORP2-001 "旧的 ReplicaSet 默认留几份？" | v2 | "留几份" is read as a replica count and the answer says the old ReplicaSets are scaled to 0 replicas, not that 10 revisions are kept. A real wording failure; failed in all 3 single-process runs and 1 of 3 restart runs |
| CORP2-045 "出了数据安全事件或者个人信息泄露，公司该怎么处理、通知谁" | v2 | Both statutes' evidence is retrieved, but the answer covers only the personal-information law and omits reporting to the authority. Cross-document completeness; failed in every v2 run |
| CORP-023 "劳动者解除劳动合同需要提前多久…试用期内呢？" | v1 | The corpus holds two statutes that say different things for the probation period (劳动法: may leave at any time; 劳动合同法: three days' notice). The model sometimes cites one, sometimes the other; both answers are grounded. The expected answer follows 劳动合同法, so this is a dataset ambiguity, not a model error |
| CORP-035, CORP2-035, CORP2-044 | both | Flip between runs after restarts (pass rate 0.33–0.67) |

## What this shows

- **The 44/45 was not an artifact of wording that mirrors the documents.** With everyday phrasing the same
  model and embedding answered 43/45, within the variation between restarts (43–45).
- **Within one backend process the results are now exactly repeatable** (zero spread over 3 runs for both
  sets, no flaky cases), which the earlier random-boundary prompt did not give. Across restarts the same
  prompts still vary by up to 2 cases, so the remaining variation is not in the prompt; the model server's
  batching or cache state is a candidate and was not isolated.
- **A zero-width range in one process is not stability.** `compare` therefore now labels such a verdict
  "DISTINGUISHABLE (single session)" unless a backend restart was part of the runs.
- The remaining weaknesses are ambiguity in a few questions and answers that cover only one of two
  documents. Retrieval is not the limiting factor on this corpus.

## Limits

- The rewrites were written by the same author as v1, not collected from users; there are few keyword-only
  or abbreviated queries and no deliberate typos.
- Same documents, one chat model, one embedding model, one machine.
- Three runs per setting; differences of one or two cases are inside the observed variation.
- CORP-023's expected answer was not changed to avoid creating a new dataset version mid-comparison.

## Addendum, 2026-10-14: the same configuration scored 38/45 on a fresh stack

The next day the stack was rebuilt from scratch with `scripts/eval_stack.sh` (new database, same fixture,
same code at `f376f4a`, same chat model, embedding model, `AI_TEMPERATURE=0`, Thinking off) and corpus-v2
was run again.

| | 2026-10-13 | 2026-10-14 |
|---|---|---|
| Answerable | 43/45 (3 runs, restarts included) | **38/45** (4 independent runs, one after a full model reload from the UI, all identical) |
| Refusals / ACL leakage | 12/12 / 0 | 12/12 / 0 |
| Median top-1 similarity (answerable) | 0.703 | 0.703 |
| Expected documents all in Top-5 | 45/45 | 45/45 |
| Failing cases | CORP2-001, CORP2-045 (+ flips) | CORP2-001, 003, 009, 016, 022, 035, 045 |

Retrieval was reproduced exactly; the five extra failures are all in generation. Nothing that was checked
differs between the days: the LM Studio application (0.4.26, 2026-10-08), the MLX runtime for the chat
model (1.13.1, 2026-10-08), the llama.cpp runtime for the embedding model (2.56.0, 2026-10-13), and the
model files (August). The cause was **not isolated**.

Two observations that should travel with the numbers:

- With Thinking accidentally left on, the same run scored 42/45. That run is not a valid gate result, but
  it shows how much the outcome depends on model-server state that this repository cannot pin.
- Both days' results are deterministic within their own environment, so a repeat inside one session does
  not detect this kind of shift. A claim about answer quality should name the day and the server state it
  was measured in, and be re-measured after any model-server change.

The 43/45 above is therefore a measurement of 2026-10-13, not a number a reader can expect to reproduce.
