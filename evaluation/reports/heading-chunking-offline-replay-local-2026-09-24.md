# Heading-Aware Chunking — Offline Replay (2026-09-24)

## Why

The chunk-size A/B (`chunking-ab-local-2026-09-24.md`) found that 700/100 blends four FAQ sections into one chunk, while 300/60 fixes Q002/Q006 but fragments other answers. Heading-aware Markdown chunking was the next candidate. Per the roadmap rule, it was replayed offline before any `TextChunker` change.

## Method

`evaluation/run_chunking_replay.py` re-chunks the seven fixture documents, embeds chunks and all answerable questions from `answer-quality-v1` (8), `golden-v1` (16), and `retrieval-stress-v1` (6) with `text-embedding-nomic-embed-text-v1.5` via LM Studio, applies each actor's document visibility from `prepare_api_fixture.py`, and ranks by cosine similarity. A case has **full evidence** when every expected answer point has a `match_any` term inside a Top-5 chunk from an expected source document. No chat model or backend is called, and no chunk text is printed.

Strategies:

- **S700 / S300**: exact replay of the current `TextChunker` size packing (700/100 and 300/60).
- **H**: one chunk per Markdown section (a heading with no body, such as a document title, is carried into the next section). Only an oversized section is packed by paragraph.
- **HP**: H, with the parent heading path (usually the document title) prefixed to each section.

**Simulator validation:** the replay reproduces the real API evidence ranks from the chunk-size A/B. Q002 is at rank 7 at S700 and rank 1 at S300; Q006 is at rank 6 at S700 and rank 4 at S300. It also predicts the real S300 misses: RAG-005, RAG-012, and RAG-013 (partial), plus STRESS-003 (partial).

## Results (full evidence in Top-5)

| Strategy | long-ops chunks | answer-quality (8) | golden (16) | stress (6) | Total (30) |
|---|---:|---:|---:|---:|---:|
| S700 (default) | 2 | 6 | 12 | 6 | **24** |
| S300 | 3 | 8 | 11 | 5 | **24** |
| H | 9 | 3 | 7 | 4 | 14 |
| HP | 9 | 5 | 8 | 4 | 17 |
| S700 + nomic task prefixes | 2 | 6 | 13 | 6 | 25 |
| S300 + nomic task prefixes | 3 | 7 | 11 | 5 | 23 |

With `--prefix`, H scored 4/9/4 and HP 6/10/4.

## Findings

- **Heading-aware chunking fails the offline gate.** Pure heading splits cut `long-ops-manual.md` into nine sections of about 90 characters each. Those short, generic chunks outrank the FAQ for unrelated questions; for example, QUALITY-007/008 and RAG-011/015 drop from rank 3 to rank 12–15. The heading-path prefix recovers part of the loss but stays well below S700. **No `TextChunker` change was made.**
- **The nomic task prefixes help slightly.** They add +1 case at S700 (RAG-003 remains a miss) and do not fix Q002/Q006 at 700. This is within the noise of a 30-case fixture, so it is not enough to justify a change on its own.
- **Chunking is saturated on this fixture.** S700 and S300 tie at 24/30 and trade different cases. The remaining misses are rank 6–7 boundary cases, where the embedding does not separate a relevant FAQ section from nearby operational text. `text-embedding-nomic-embed-text-v1.5` is primarily an English model, and all questions and documents here are Chinese. The next measurable lever is a **multilingual embedding model**, compared with this same replay before any runtime change. That needs a model download, and if the dimension differs from 768, a schema/reindex decision.

## Limitations

This is lexical evidence presence, not generated-answer quality. The corpus is seven short synthetic documents. Visibility mirrors the fixture grants in Python rather than running backend SQL. The simulator was validated only on the ranks listed above.
