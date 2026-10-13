# corpus-v1 — baseline and retrieval A/B, local, 2026-10-13

First evaluation on a corpus large enough to show differences: 28 documents (24 Kubernetes zh-cn pages and
4 statutes, about 245k characters, 419 chunks at 700/100), 57 questions (45 answerable, 4 out of scope,
8 ACL-filtered), three access groups. Dataset and fixture are described in `evaluation/corpus/README.md`.

## Configuration

| Item | Value |
|---|---|
| Chat / embedding | `google/gemma-4-26b-a4b-qat` (Thinking off, probe 4/4 valid) / `text-embedding-nomic-embed-text-v1.5`, LM Studio |
| Defaults | VECTOR, Top-K=5, threshold 0.35, chunk 700/100, `AI_MAX_TOKENS=2400`, prompt with the safety rule (v2) |
| Tenant | disposable pgvector container per configuration, fixture rebuilt for every chunking variant |
| Scoring | deterministic answer-point and citation rules in `run_eval.py`; **chunk-level evidence** = every expected answer point appears literally in at least one of the Top-5 retrieved chunks |

## Baseline (default configuration)

| Measure | Result |
|---|---|
| Answerable questions passed | **24, 25, 24 of 45** over three runs (about 54%) |
| Out-of-scope and ACL-filtered refusals | 12 / 12 |
| ACL leakage, schema failures | 0, 0 |
| By category | single-fact 9/14, multi-part 15/28, cross-document 0/3 |

The same run twice gives identical results here, so differences of 2–3 cases between configurations are
not run-to-run noise; they still come from one model and one question set.

### Where the failures are

| Evidence in Top-5 | Cases | Passed |
|---|---|---|
| Complete (all points in retrieved chunks) | 28 | 24 (86%) |
| Partial | 6 | 0 |
| None | 11 | 0 |

When the evidence chunk reaches the model it is answered correctly 86% of the time; when it does not,
nothing passes. The bottleneck is **chunk-level retrieval**, not generation. Document-level recall would
have hidden this: for 12 refused cases the expected document was rank 1 with similarity 0.7–0.9, but the
chunk holding the fact was not in the Top-5 (the model correctly said the context was insufficient).

## Retrieval modes (Top-K=5, 700/100)

| Mode | Answerable /45 | Evidence-complete /45 | Evidence recall | Tokens / question |
|---|---|---|---|---|
| **VECTOR (default)** | **25** | 28 | 0.700 | 2235 |
| VECTOR_ADJACENT | 23–24 | 27 | 0.685 | 2240 |
| VECTOR_DIVERSITY | 23 | 26 | 0.622 | 2200 |
| KEYWORD_RRF | 18 | 21 | 0.519 | 2360 |

No existing experiment beats the default; keyword RRF is clearly worse (−7 cases). This confirms, on a
real corpus, the earlier conclusion drawn on the 7-document fixture.

## Top-K and chunk size (VECTOR)

| Configuration | Answerable /45 | Evidence-complete /45 | Tokens / question | Chunks |
|---|---|---|---|---|
| 700/100, K=5 (default) | 25 | 28 | 2235 | 419 |
| 700/100, **K=8** | **29** | 33 | 3224 (+44%) | 419 |
| 350/60, K=5 | 20 | 22 | 1436 | 890 |
| 500/80, K=5 | 20 | 24 | 1754 | 601 |
| **1000/150, K=5** | 28 | 33 | 2917 (+30%) | 291 |
| 1400/200, K=5 | 23 | 29 | 3900 | 202 |

## Reading

- Smaller chunks are worse here (the 300/60 result on the old fixture does not carry over). The best
  settings (K=8 or 1000/150) buy 3–4 cases for 30–44% more tokens. **Both break the frozen "tokens must not
  rise more than 15%" rule**, so no default was changed; this is a cost/quality trade-off for the owner.
- Even the best setting leaves 12 of 45 questions without complete evidence in the Top-5 (recall plateau
  about 0.78–0.80). More candidates or larger chunks do not fix this; the likely lever is a multilingual
  embedding model and a reranker. `nomic-embed-text-v1.5` is mainly English-trained (background knowledge,
  not verified in this repository), and every mismatch above is a Chinese-language retrieval miss.
- Cross-document questions were 0/3 in every configuration; they need evidence from two documents inside
  one Top-5, so they are the first to benefit from a better retriever.

## Limits

- 45 answerable questions from one author, scored by deterministic substring rules, not by a judge. An
  answer with correct facts but different wording can be marked wrong; the match terms were written to
  tolerate common paraphrases and each is checked to exist in its source.
- Questions were written after reading the cleaned documents, so they are answerable by construction. This
  measures retrieval and answering on documents of this kind, not real user traffic.
- One chat model and one embedding model; chunk-size results also change the amount of context shown to
  the model, so they mix chunking with context budget.
- Kubernetes pages were cleaned of Hugo markup at upload time (see the attribution file).
