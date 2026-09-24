# Chunk Size A/B — Local Disposable Evidence (2026-09-24)

## Why

`ROADMAP.md` Milestone 5 orders retrieval work as chunking/Top-K/threshold first, then keyword, fusion, reranker. Chunking had never been measured. An offline replay of `TextChunker` showed that at the default `700/100`, `sample_faq.md` becomes two chunks, and chunk#1 (615 chars) mixes four sections: specs, installation/pairing, warranty, and returns. `QUALITY-002` (installation) and `QUALITY-006` (returns) both depend on that blended chunk, which matched the earlier diagnosis of a Top-5 boundary miss (~0.003 similarity gap).

## Configuration record

- Chat `google/gemma-4-26b-a4b-qat` via LM Studio (Developer UI: `Enable Thinking` off, set by the user before the run; context configured 8,192, auto-fit effective 226,304); Embedding `text-embedding-nomic-embed-text-v1.5`, 768 dims.
- Backend built from `2d5b8bd` sources; `RAG_TOP_K=5`, similarity threshold 0.35, `AI_MAX_TOKENS=2400`, structured-output retries 1, VECTOR mode, `AI_DAILY_LIMIT=1000` (raised only so one fixture user can issue all captures).
- Each arm: fresh no-volume `pgvector/pgvector:pg16` on loopback port 55501 (5432 dev DB untouched), fresh backend on 8091, `prepare_api_fixture.py` (7 synthetic documents, same ACL grants), `probe_structured_output.py` (every arm: 2/2 contract-valid, `finish_reason=stop`, no `reasoning_content`), then `answer-quality-v1` ×3, `golden-v1` ×1, `retrieval-stress-v1` ×1, protected retrieval diagnostics, and ACL-filtered vector candidates.
- Scoring: `run_eval.py` with **rubric-v2**; the gate is the one frozen in `evaluation/README.md` on 2026-09-24.
- Order (16:47–16:56 local): 700/100 → 400/80 → 300/60 → 200/40 → 700/100 again (drift check).

## Results

| Chunk size/overlap | FAQ chunks | Visible chunks (employee) | Answer-quality gate ×3 | Gate pass | Golden | Stress | Mean tokens AQ / Golden | ACL leak | Schema fail |
|---|---:|---:|---|---|---:|---:|---|---:|---:|
| 700/100 (run A) | 2 | 7 | 9, 10, 9 | no | 17/20 | 8/8 | 1584 / 1557 | 0 | 0 |
| 700/100 (run B) | 2 | 7 | 8, 10, 9 | no | 15/20 | 8/8 | 1583 / 1619 | 0 | 0 |
| 400/80 | 3 | 9 | 9, 9, 9 | no | 15/20 | 7/8 | 1386 / 1354 | 0 | 0 |
| **300/60** | 4 | 10 | **10, 11, 11** | **yes** | 14/20 | 7/8 | 1350 / 1277 | 0 | 0 |
| 200/40 | 6 | 18 | 8, 8, 8 | no | 13/20 | 6/8 | 916 / 945 | 0 | 0 |

"Schema fail" is the evaluator's response-shape check. One golden case in 700/100 run B returned the backend's fail-closed `STRUCTURED_OUTPUT_INVALID`, which scored as a normal miss.

Retrieval diagnostics (document-level Recall@5, from protected diagnostics): answer-quality 87.5% at 700 vs 100% at 400/300/200; golden 93.75% at 700 vs 100% at the smaller sizes; stress 100% at 700 vs 91.67% at 400/300/200.

Chunk-level rank of the FAQ evidence (ACL-filtered vector candidates):

- `QUALITY-002`: FAQ first appears at rank 6 of 7 at 700, rank 2 at 400, **rank 1** at 300 and 200.
- `QUALITY-006`: at 300 the warranty+returns chunk (chunk#3) is rank 4, inside Top-5; at 700 the blended chunk#1 is rank 6.

## Per-case pattern

- **Diagnosis confirmed for retrieval.** At 300/60, Q002 and Q006 are retrieved and answered in most repeats (Q002 failed once on citation only; Q006 failed once). These two cases never passed at 700/100 in six repeats.
- **New failures at 300/60 are elsewhere.** Golden RAG-005/012/013/014 (all FAQ "pairing / fingerprint / offline / remote" questions) lost answer points or returned `INSUFFICIENT_CONTEXT`. `QUALITY-004`/`RAG-008` answered correctly but cited a non-FAQ source. STRESS-003 (cross-document) lost the `support-sla.md` citation. More, smaller chunks from `long-ops-manual.md` and other documents now compete for the same five slots, so the context is more fragmented.
- **200/40 is worse on every set.** It also hard-cuts the FAQ Q&A section in the middle of a paragraph.
- **Run-to-run noise.** Across the two 700/100 sessions, Golden moved 17→15 and individual answer-quality cases flipped. A single Golden or Stress capture per arm is therefore weak evidence; differences of ±2 on Golden are within observed noise.

## Decision

Under the pre-registered rules, **no candidate replaces the default**. `RAG_CHUNK_SIZE=700` / `RAG_CHUNK_OVERLAP=100` stays.

- 300/60 is the first configuration to pass the frozen answer-quality gate (3/3 captures ≥ 10/12, mean 10.67 vs 9.17 baseline) and uses about 15% fewer tokens. However, it fails the "not worse than baseline on golden and stress" condition (Golden 14 vs 15–17; Stress 7 vs 8). Summed over the three sets, the arms are essentially tied (700 A ≈ 34.3/40, 700 B ≈ 32, 300 ≈ 31.7, 400 ≈ 31, 200 ≈ 27).
- The result shows that fixed-size packing only moves the failures around. Smaller chunks fix blended-topic retrieval (Q002/Q006) but fragment other answers and citations.

## Next hypothesis (not run)

Structure-aware chunking: split Markdown at headings so one section maps to one chunk, never merge across headings, and never hard-cut inside a paragraph. Split an oversized section only at paragraph or list boundaries. This targets the exact failure mode seen at 700 (blended sections) without the fragmentation seen at 200. It requires a `TextChunker` code change, so run it first as an offline candidate-rank replay, then as an A/B under this same configuration record. Also increase Golden/Stress to ≥2 captures per arm so the ±2 noise can be separated from real effects.

## Limitations

- The corpus is seven short synthetic documents. Conclusions apply to this fixture and to short Markdown FAQs, not to production corpora.
- Local LM Studio only; no cloud-model or cost comparison. Answer text, credentials, manifests, and raw captures were kept only in the session scratchpad and deleted after aggregation; only aggregate numbers are recorded here.
