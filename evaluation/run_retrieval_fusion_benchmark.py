#!/usr/bin/env python3
"""Compare vector and keyword document candidates with an offline RRF simulation.

This is a matched-case experiment, not an online retrieval change. Vector rows
come from the protected diagnostics capture of authenticated requests, while
keyword rows come from the answer-term-independent offline benchmark. Results
are evaluated at document level because the offline keyword corpus is document
based; no model answer or answer-point term is read.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
from pathlib import Path
from typing import Any

from run_keyword_candidate_benchmark import load_jsonl, score_case, validate_cases


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "keyword_candidates_v1.jsonl"
DEFAULT_KEYWORD_REPORT = Path("/private/tmp/keyword-candidates-v1-local.json")
DEFAULT_OUTPUT = ROOT / "evaluation" / "reports" / "retrieval-fusion-v1-local.json"


def fail(message: str) -> None:
    raise ValueError(message)


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 4)


def load_report(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"invalid keyword report {path}: {exc}")
    if not isinstance(value, dict):
        fail("keyword report must be an object")
    return value


def load_vector_rows(path: Path) -> dict[str, dict[str, Any]]:
    rows = load_jsonl(path)
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        case_id = row.get("id")
        diagnostics = row.get("diagnostics")
        if not isinstance(case_id, str) or not isinstance(diagnostics, dict):
            fail(f"{path} rows require id and diagnostics object")
        if case_id in result:
            fail(f"duplicate vector diagnostics id: {case_id}")
        hits = diagnostics.get("hits")
        if not isinstance(hits, list):
            fail(f"{case_id}.diagnostics.hits must be a list")
        validated: list[dict[str, Any]] = []
        for expected_rank, hit in enumerate(hits, 1):
            if not isinstance(hit, dict) or hit.get("rank") != expected_rank:
                fail(f"{case_id}.diagnostics.hits ranks must start at 1")
            filename = hit.get("filename")
            if not isinstance(filename, str) or not filename.strip():
                fail(f"{case_id}.diagnostics.hits[{expected_rank}] filename is required")
            validated.append({"filename": filename, "rank": expected_rank, "similarity": hit.get("similarity")})
        result[case_id] = {
            "hits": validated,
            "diagnostic_fetch_latency_ms": row.get("httpLatencyMs"),
            "top_k": diagnostics.get("topK"),
        }
    return result


def collapse_vector_hits(hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    best_by_document: dict[str, dict[str, Any]] = {}
    for hit in hits:
        filename = hit["filename"]
        current = best_by_document.get(filename)
        if current is None or hit["rank"] < current["sourceRank"]:
            best_by_document[filename] = {
                "filename": filename,
                "sourceRank": hit["rank"],
                "similarity": hit.get("similarity"),
            }
    ordered = sorted(best_by_document.values(), key=lambda item: (item["sourceRank"], item["filename"]))
    for rank, item in enumerate(ordered, 1):
        item["rank"] = rank
    return ordered


def keyword_case_results(report: dict[str, Any]) -> dict[str, dict[str, Any]]:
    benchmarks = report.get("benchmarks")
    if not isinstance(benchmarks, dict) or not isinstance(benchmarks.get("normalized_keyword"), dict):
        fail("keyword report does not contain normalized_keyword benchmark")
    case_results = benchmarks["normalized_keyword"].get("case_results")
    if not isinstance(case_results, list):
        fail("keyword report normalized_keyword.case_results must be a list")
    result: dict[str, dict[str, Any]] = {}
    for case in case_results:
        case_id = case.get("id")
        hits = case.get("hits")
        if not isinstance(case_id, str) or not isinstance(hits, list):
            fail("keyword case results require id and hits")
        result[case_id] = {"hits": hits}
    return result


def rrf(
    vector_hits: list[dict[str, Any]],
    keyword_hits: list[dict[str, Any]],
    rrf_k: int,
    vector_weight: float = 1.0,
    keyword_weight: float = 1.0,
) -> list[dict[str, Any]]:
    scores: dict[str, float] = {}
    vector_rank: dict[str, int] = {}
    keyword_rank: dict[str, int] = {}
    for hit in vector_hits:
        filename = hit["filename"]
        vector_rank[filename] = hit["rank"]
        scores[filename] = scores.get(filename, 0.0) + vector_weight / (rrf_k + hit["rank"])
    for hit in keyword_hits:
        filename = hit["filename"]
        keyword_rank[filename] = hit["rank"]
        scores[filename] = scores.get(filename, 0.0) + keyword_weight / (rrf_k + hit["rank"])
    ordered = sorted(scores, key=lambda filename: (-scores[filename], filename))
    return [
        {
            "filename": filename,
            "score": round(scores[filename], 8),
            "vectorRank": vector_rank.get(filename),
            "keywordRank": keyword_rank.get(filename),
            "rank": rank,
        }
        for rank, filename in enumerate(ordered, 1)
    ]


def evaluate_method(
    cases: list[dict[str, Any]],
    hits_by_id: dict[str, list[dict[str, Any]]],
    context_k: int,
    method: str,
    diagnostic_latency_by_id: dict[str, float],
    iterations: int,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    fusion_timings: list[float] = []
    for case in cases:
        case_id = case["id"]
        hits = hits_by_id.get(case_id)
        if hits is None:
            fail(f"missing {method} candidates for {case_id}")
        samples: list[float] = []
        evaluated_hits: list[dict[str, Any]] = []
        for iteration in range(max(2, iterations + 1)):
            started = time.perf_counter_ns()
            evaluated_hits = [dict(hit) for hit in hits]
            evaluated_hits.sort(key=lambda item: item["rank"])
            elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
            if iteration > 0:
                samples.append(elapsed_ms)
                fusion_timings.append(elapsed_ms)
        result = score_case(case, evaluated_hits, context_k)
        result["latency_ms"] = {
            "p50": percentile(samples, 0.50),
            "p95": percentile(samples, 0.95),
            "max": round(max(samples), 4) if samples else None,
        }
        result["vector_diagnostic_fetch_latency_ms"] = diagnostic_latency_by_id.get(case_id)
        results.append(result)
    answerable = [item for item in results if item["behavior"] == "ANSWER"]
    refusal = [item for item in results if item["behavior"] != "ANSWER"]

    def average(values: list[float]) -> float | None:
        return round(statistics.mean(values), 4) if values else None

    context_slots = sum(min(context_k, item["candidate_count"]) for item in answerable)
    context_interference = sum(item["context_interference_count"] for item in answerable)
    return {
        "method": method,
        "context_k": context_k,
        "case_count": len(results),
        "answerable_case_count": len(answerable),
        "recall_at_1": average([item["recall_at_k"]["recall_at_1"] for item in answerable]),
        "recall_at_3": average([item["recall_at_k"]["recall_at_3"] for item in answerable]),
        "recall_at_5": average([item["recall_at_k"]["recall_at_5"] for item in answerable]),
        "first_expected_rank_avg": average([float(item["first_expected_rank"]) for item in answerable if item["first_expected_rank"] is not None]),
        "answerable_context_interference_rate": round(context_interference / context_slots, 4) if context_slots else 0.0,
        "refusal_candidate_count": sum(1 for item in refusal if item["refusal_has_candidates"]),
        "acl_leakage_count": sum(1 for item in results if item["acl_leakage"]),
        "fusion_cpu_latency_ms": {
            "p50": percentile(fusion_timings, 0.50),
            "p95": percentile(fusion_timings, 0.95),
            "max": round(max(fusion_timings), 4) if fusion_timings else None,
        },
        "case_results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--diagnostics", type=Path, required=True, help="Protected diagnostics JSONL from the same case fixture")
    parser.add_argument("--keyword-report", type=Path, default=DEFAULT_KEYWORD_REPORT)
    parser.add_argument("--context-k", type=int, default=5)
    parser.add_argument("--rrf-k", type=int, default=60)
    parser.add_argument("--iterations", type=int, default=20)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        if args.context_k < 1 or args.rrf_k < 1 or args.iterations < 1:
            fail("context-k, rrf-k, and iterations must be positive")
        cases = load_jsonl(args.dataset)
        dataset_version = validate_cases(cases)
        vector_rows = load_vector_rows(args.diagnostics)
        keyword_results = keyword_case_results(load_report(args.keyword_report))
        case_ids = {case["id"] for case in cases}
        if set(vector_rows) != case_ids:
            fail(f"vector diagnostics IDs do not match dataset: {sorted(set(vector_rows) ^ case_ids)}")
        if set(keyword_results) != case_ids:
            fail(f"keyword result IDs do not match dataset: {sorted(set(keyword_results) ^ case_ids)}")

        vector_hits = {case_id: collapse_vector_hits(row["hits"]) for case_id, row in vector_rows.items()}
        keyword_hits = {case_id: row["hits"] for case_id, row in keyword_results.items()}
        fused_hits = {
            case_id: rrf(vector_hits[case_id], keyword_hits[case_id], args.rrf_k)
            for case_id in case_ids
        }
        keyword_weighted_fused_hits = {
            case_id: rrf(vector_hits[case_id], keyword_hits[case_id], args.rrf_k, keyword_weight=2.0)
            for case_id in case_ids
        }
        diagnostic_latency = {
            case_id: float(row["diagnostic_fetch_latency_ms"])
            for case_id, row in vector_rows.items()
            if isinstance(row.get("diagnostic_fetch_latency_ms"), (int, float))
        }
        benchmarks = {
            "vector_document_rank": evaluate_method(cases, vector_hits, args.context_k, "vector_document_rank", diagnostic_latency, args.iterations),
            "keyword_document_rank": evaluate_method(cases, keyword_hits, args.context_k, "keyword_document_rank", diagnostic_latency, args.iterations),
            "rrf_document_rank": evaluate_method(cases, fused_hits, args.context_k, "rrf_document_rank", diagnostic_latency, args.iterations),
            "rrf_keyword_weight_2_document_rank": evaluate_method(
                cases,
                keyword_weighted_fused_hits,
                args.context_k,
                "rrf_keyword_weight_2_document_rank",
                diagnostic_latency,
                args.iterations,
            ),
        }
        vector_recall = benchmarks["vector_document_rank"]["recall_at_5"]
        fusion_candidates = [
            benchmarks["rrf_document_rank"],
            benchmarks["rrf_keyword_weight_2_document_rank"],
        ]
        fused_recall = max(item["recall_at_5"] for item in fusion_candidates)
        vector_recall_at_1 = benchmarks["vector_document_rank"]["recall_at_1"]
        best_fused_recall_at_1 = max(item["recall_at_1"] for item in fusion_candidates)
        report = {
            "dataset": str(args.dataset),
            "dataset_version": dataset_version,
            "diagnostics": str(args.diagnostics),
            "keyword_report": str(args.keyword_report),
            "experiment_boundary": "matched case and ACL fixture; keyword is offline and fusion is document-level; no online retrieval code changed",
            "fusion": {"method": "Reciprocal Rank Fusion", "rrf_k": args.rrf_k, "context_k": args.context_k},
            "benchmarks": benchmarks,
            "decision": {
                "recommendation": (
                    "run_online_ab_comparison"
                    if fused_recall > vector_recall or best_fused_recall_at_1 > vector_recall_at_1
                    else "do_not_enable_hybrid_yet"
                ),
                "vector_recall_at_5": vector_recall,
                "fused_recall_at_5": fused_recall,
                "vector_recall_at_1": vector_recall_at_1,
                "best_fused_recall_at_1": best_fused_recall_at_1,
                "reason": (
                    "The weighted RRF candidate improves ranking at Recall@1 but not Recall@5; run a bounded online authenticated A/B capture before implementing any runtime Hybrid Search."
                    if best_fused_recall_at_1 > vector_recall_at_1 and fused_recall <= vector_recall
                    else "RRF did not improve matched-case document recall over the vector document baseline; keep Top-K=5 and the online vector path unchanged."
                    if fused_recall <= vector_recall
                    else "RRF shows a candidate recall gain in the matched-case simulation; verify with an online authenticated A/B capture before implementation."
                ),
            },
        }
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, ValueError, RuntimeError, KeyError) as exc:
        print(f"retrieval fusion benchmark error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
