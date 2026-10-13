# Embedding: bge-m3 vs nomic-embed-text-v1.5 — local, 2026-10-13

Question: is the chunk-level retrieval bottleneck found in
[corpus-v1-baseline-and-retrieval-ab](corpus-v1-baseline-and-retrieval-ab-local-2026-10-13.md) an embedding
problem? One candidate was tested: `bge-m3-Q8_0.gguf` (gpustack/bge-m3-GGUF, MIT, 634,553,760 bytes,
SHA-256 `950f4a8e…1167` verified, 1024 dimensions, normalised), served by LM Studio.

## Configuration

| Item | Value |
|---|---|
| Chat model | `google/gemma-4-26b-a4b-qat`, Thinking off, safety-rule prompt v2 |
| Embeddings compared | `text-embedding-nomic-embed-text-v1.5` (768 d) vs `text-embedding-bge-m3` (1024 d) |
| Everything else | VECTOR, threshold 0.35, chunk 700/100, 419 chunks, disposable DB per configuration (`AI_EMBEDDING_DIM=1024` for bge-m3) |
| Data | corpus-v1: 28 documents, 57 questions (45 answerable) |

## corpus-v1

| Embedding | Top-K | Answerable /45 | Evidence-complete /45 | Refusals /12 | ACL leakage | Tokens / question |
|---|---|---|---|---|---|---|
| nomic (previous default) | 5 | 25 | 28 | 12 | 0 | 2235 |
| nomic | 8 | 29 | 33 | 12 | 0 | 3224 |
| **bge-m3** | **5** | **44** | **45** | 12 | 0 | **2412 (+8%)** |
| bge-m3 | 8 | 43 | 45 | 12 | 0 | 3472 |

- With bge-m3 the answer-bearing chunk is in the Top-5 for **every** answerable question, and K=5 is enough:
  K=8 adds 44% tokens for no gain.
- The single miss is CORP-035, a cross-document question. The evidence was retrieved from both statutes, but
  the model answered from one and described the contract types instead of the probation tiers: a generation
  failure, not retrieval.
- Token cost stays inside the frozen +15% limit (+8%), unlike Top-K=8 and larger chunks on nomic.

## Frozen synthetic fixture (7 documents), same chat model and prompt

| Set | nomic (same session, prompt v2) | bge-m3 |
|---|---|---|
| answer-quality-v1 ×3 | 10, 9, 9 | 9, 10, 10 |
| golden-v1 | 15 | **19 / 20** |
| stress-v1 | 8 | 8 / 8 |
| ACL leakage, schema failures | 0, 0 | 0, 0 |

The answer-quality gate (≥ 10/12 in three consecutive runs) is **not passed**: run 1 scored 9 because Q001,
Q002, and Q006 failed; Q001 is the known rubric wording sensitivity (not changed, to keep rubric-v2), and Q006
failed in every run. Q002, a standing failure on nomic, passed in two of three runs.

## Similarity scale changed

Top-1 cosine similarity on corpus-v1 with bge-m3 (min / median / max):

| Question type | Top-1 similarity |
|---|---|
| Answerable | 0.53 / 0.73 / 0.80 |
| Out of scope | 0.31 / 0.44 / 0.64 |
| ACL-filtered | 0.33 / 0.46 / 0.53 |

The fixed threshold of 0.35 short-circuits only 4 of the 12 refusals; the other 8 are refused by the model
(12/12 correct). The gap between the highest out-of-scope similarity (0.64) and the lowest answerable (0.53)
is negative, so the threshold cannot be raised safely. It was not tuned: the same 57 questions were used to
pick the embedding, and tuning on them would overfit.

## Not claimed

- One extra candidate, one chat model, one run per configuration (repeated nomic runs were identical, so run
  noise is small, but a different run order could move a case).
- The questions were written from the documents by one author, so their wording is close to the source text;
  real users paraphrase more. The 44/45 should be read as an upper-end figure for this question style.
- The data is Kubernetes documentation and statutes, not a customer's documents.

## What adopting it would require

- The `chunk.embedding` column is `vector(768)` on existing databases (the dimension is a Flyway placeholder
  evaluated once at V1). Switching needs a migration, a full re-embed, and a pause in answering during the
  rebuild.
- `chunk` does not record which model produced a vector, so old and new vectors cannot be told apart; add the
  column before changing models.
- README, Compose, and the runbook name the nomic model; users would download a 635 MB model instead of 84 MB.
- Re-baseline the similarity threshold on a labelled out-of-scope set before relying on it as a gate.
