# /api/ask under 1, 2 and 4 concurrent callers — local, 2026-10-21

Question: how does latency grow when several users ask at once, and does anything fail?

## Configuration

| Item | Value |
|---|---|
| Machine | one Apple M5 Pro Mac, 48 GB, on AC power |
| Chat / embedding | `google/gemma-4-26b-a4b-qat` (Thinking off, verified by the stack preflight) / `text-embedding-bge-m3`, LM Studio with parallel=4 |
| Backend | the shipped defaults of commit `2e56341` (bge-m3, `AI_TEMPERATURE=0`), VECTOR, Top-K=5, disposable pgvector database, 28-document corpus fixture |
| Load | `evaluation/run_concurrency_baseline.py`, 12 requests per level, the first 12 answerable `corpus_v2` questions in dataset order, 5 test users, levels run in sequence 1 → 2 → 4 |
| Raw result | [concurrency-baseline-local-2026-10-21.json](concurrency-baseline-local-2026-10-21.json) |

## Result

| Concurrency | Requests | Wall time | Throughput | Latency p50 | Latency p95 / max | HTTP 200 | `found` |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12 | 39.3 s | 18.3 / min | 3.5 s | 4.9 s | 12/12 | 100% |
| 2 | 12 | 28.6 s | 25.1 / min | 4.3 s | 7.5 s | 12/12 | 100% |
| 4 | 12 | 24.5 s | 29.3 / min | 7.0 s | 14.0 s | 12/12 | 100% |

No request failed, timed out or was rate limited at any level.

## How to read it

- Going from 1 to 4 callers raised throughput by about 60% while the median latency doubled and the worst case roughly tripled. Requests queue behind the one local model; the backend itself is not what limits this.
- This says nothing about a cloud model, a larger machine, or a different model server. It is not a capacity or SLO figure.

## Limits

- One run per level, 12 requests each. With so few samples p95 is the maximum, and a single slow request moves it. The 1-caller level ran first and may include warm-up.
- Only 12 distinct questions, all answerable; refusals (which skip generation) and long answers are not represented.
- Order effects (1 → 2 → 4) were not randomized, and nothing was repeated, so the run-to-run variation is unknown. Do not quote differences between levels smaller than the gaps above.
- Answers were not scored here; `found` is the backend's flag, not correctness.
