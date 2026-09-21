#!/usr/bin/env python3
"""Compare paired backend-owned VECTOR and KEYWORD_RRF captures.

The input captures are already produced by run_api_eval.py and protected
diagnostics. This scorer deliberately omits answer text and chunk content;
it reports only contract behavior, cited filenames, retrieval metadata, stage
timings, token usage, and failure categories.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "keyword_candidates_v1.jsonl"


def fail(message: str) -> None:
    raise ValueError(message)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            fail(f"{path}:{line_number} invalid JSON: {exc.msg}")
        if not isinstance(value, dict):
            fail(f"{path}:{line_number} must contain an object")
        rows.append(value)
    return rows


def by_id(rows: list[dict[str, Any]], label: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        case_id = row.get("id")
        if not isinstance(case_id, str) or case_id in result:
            fail(f"{label} contains invalid or duplicate id: {case_id}")
        result[case_id] = row
    return result


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 4)


def average(values: list[float]) -> float | None:
    return round(statistics.mean(values), 4) if values else None


def api_summary(cases: dict[str, dict[str, Any]], responses: dict[str, dict[str, Any]], diagnostics: dict[str, dict[str, Any]], expected_mode: str) -> dict[str, Any]:
    if set(responses) != set(cases) or set(diagnostics) != set(cases):
        fail(f"{expected_mode} capture IDs do not match dataset")
    api_latencies: list[float] = []
    http_latencies: list[float] = []
    tokens: list[float] = []
    stage_latencies: dict[str, list[float]] = {"embeddingMs": [], "retrievalMs": [], "generationMs": []}
    status_failures = 0
    behavior_failures = 0
    citation_failures = 0
    failure_reasons: dict[str, int] = {}
    retrieval_metrics: list[dict[str, Any]] = []

    for case_id, case in cases.items():
        response = responses[case_id]
        diagnostic = diagnostics[case_id].get("diagnostics")
        if not isinstance(diagnostic, dict):
            fail(f"{expected_mode} {case_id} diagnostics missing")
        if diagnostic.get("retrievalMode") != expected_mode:
            fail(f"{expected_mode} {case_id} diagnostics mode is {diagnostic.get('retrievalMode')}")
        status = response.get("statusCode")
        if status != 200 or response.get("apiCode") != 0:
            status_failures += 1
        found = response.get("found") is True
        grounded = response.get("grounded") is True
        sources = response.get("sources") if isinstance(response.get("sources"), list) else []
        source_names = {str(source.get("filename")) for source in sources if isinstance(source, dict) and source.get("filename")}
        expected_sources = set(case["expected_source_documents"])
        answerable = case["expected_behavior"] == "ANSWER"
        behavior_ok = (found and grounded and bool(sources)) if answerable else (not found and not sources)
        if not behavior_ok:
            behavior_failures += 1
        if answerable and not expected_sources.issubset(source_names):
            citation_failures += 1
        failure_reason = response.get("failureReason") or "NONE"
        failure_reasons[failure_reason] = failure_reasons.get(failure_reason, 0) + 1
        if isinstance(response.get("apiLatencyMs"), (int, float)):
            api_latencies.append(float(response["apiLatencyMs"]))
        if isinstance(response.get("httpLatencyMs"), (int, float)):
            http_latencies.append(float(response["httpLatencyMs"]))
        if isinstance(response.get("tokenUsage"), (int, float)):
            tokens.append(float(response["tokenUsage"]))
        timings = response.get("timings") if isinstance(response.get("timings"), dict) else {}
        for stage in stage_latencies:
            if isinstance(timings.get(stage), (int, float)):
                stage_latencies[stage].append(float(timings[stage]))

        hits = diagnostic.get("hits") if isinstance(diagnostic.get("hits"), list) else []
        hit_names = [str(hit.get("filename")) for hit in hits if isinstance(hit, dict) and hit.get("filename")]
        expected = set(case["expected_source_documents"])
        forbidden = set(case["forbidden_documents"])
        first_five = set(hit_names[:5])
        candidate_names = set(hit_names)
        recall_at_k = {
            f"recall_at_{cutoff}": round(len(expected & set(hit_names[:cutoff])) / len(expected), 4) if expected else None
            for cutoff in (1, 3, 5)
        }
        retrieval_metrics.append({
            "id": case_id,
            "behavior": case["expected_behavior"],
            "recall_at_k": recall_at_k,
            "first_expected_rank": next((index for index, name in enumerate(hit_names, 1) if name in expected), None),
            "context_interference_count": len(first_five - expected),
            "refusal_has_candidates": not expected and bool(hit_names),
            "acl_leakage": sorted(candidate_names & forbidden),
            "candidate_filenames": hit_names[:5],
        })

    answerable_metrics = [item for item in retrieval_metrics if item["behavior"] == "ANSWER"]
    refusal_metrics = [item for item in retrieval_metrics if item["behavior"] != "ANSWER"]
    context_slots = sum(min(5, len(item["candidate_filenames"])) for item in answerable_metrics)
    return {
        "mode": expected_mode,
        "case_count": len(cases),
        "status_failures": status_failures,
        "behavior_failures": behavior_failures,
        "citation_failures": citation_failures,
        "failure_reasons": dict(sorted(failure_reasons.items())),
        "api_latency_ms": {
            "p50": percentile(api_latencies, 0.50),
            "p95": percentile(api_latencies, 0.95),
            "max": round(max(api_latencies), 4) if api_latencies else None,
        },
        "http_latency_ms": {
            "p50": percentile(http_latencies, 0.50),
            "p95": percentile(http_latencies, 0.95),
            "max": round(max(http_latencies), 4) if http_latencies else None,
        },
        "token_usage": {"avg": average(tokens), "total": round(sum(tokens), 4)},
        "stage_latency_ms": {
            stage: {"p50": percentile(values, 0.50), "p95": percentile(values, 0.95), "max": round(max(values), 4) if values else None}
            for stage, values in stage_latencies.items()
        },
        "retrieval": {
            "recall_at_1": average([item["recall_at_k"]["recall_at_1"] for item in answerable_metrics]),
            "recall_at_3": average([item["recall_at_k"]["recall_at_3"] for item in answerable_metrics]),
            "recall_at_5": average([item["recall_at_k"]["recall_at_5"] for item in answerable_metrics]),
            "first_expected_rank_avg": average([float(item["first_expected_rank"]) for item in answerable_metrics if item["first_expected_rank"] is not None]),
            "answerable_context_interference_rate": round(
                sum(item["context_interference_count"] for item in answerable_metrics) / context_slots, 4
            ) if context_slots else 0.0,
            "refusal_candidate_count": sum(1 for item in refusal_metrics if item["refusal_has_candidates"]),
            "acl_leakage_count": sum(1 for item in retrieval_metrics if item["acl_leakage"]),
        },
        "cases": retrieval_metrics,
    }


def response_deltas(cases: dict[str, dict[str, Any]], vector: dict[str, dict[str, Any]], keyword: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    deltas: list[dict[str, Any]] = []
    for case_id in cases:
        left = vector[case_id]
        right = keyword[case_id]
        deltas.append({
            "id": case_id,
            "vector_found": left.get("found") is True,
            "keyword_rrf_found": right.get("found") is True,
            "vector_failure_reason": left.get("failureReason"),
            "keyword_rrf_failure_reason": right.get("failureReason"),
            "vector_source_filenames": sorted({str(item.get("filename")) for item in left.get("sources", []) if isinstance(item, dict) and item.get("filename")}),
            "keyword_rrf_source_filenames": sorted({str(item.get("filename")) for item in right.get("sources", []) if isinstance(item, dict) and item.get("filename")}),
        })
    return deltas


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--vector-responses", type=Path, required=True)
    parser.add_argument("--vector-diagnostics", type=Path, required=True)
    parser.add_argument("--keyword-responses", type=Path, required=True)
    parser.add_argument("--keyword-diagnostics", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        cases = by_id(load_jsonl(args.dataset), "dataset")
        vector_responses = by_id(load_jsonl(args.vector_responses), "vector responses")
        keyword_responses = by_id(load_jsonl(args.keyword_responses), "keyword responses")
        vector_diagnostics = by_id(load_jsonl(args.vector_diagnostics), "vector diagnostics")
        keyword_diagnostics = by_id(load_jsonl(args.keyword_diagnostics), "keyword diagnostics")
        vector_summary = api_summary(cases, vector_responses, vector_diagnostics, "VECTOR")
        keyword_summary = api_summary(cases, keyword_responses, keyword_diagnostics, "KEYWORD_RRF")
        report = {
            "dataset": str(args.dataset),
            "dataset_version": next(iter(cases.values())).get("dataset_version"),
            "comparison": "same authenticated request set; only X-RAG-Retrieval-Mode differs",
            "vector": vector_summary,
            "keyword_rrf": keyword_summary,
            "delta": {
                "api_p95_ms": round(keyword_summary["api_latency_ms"]["p95"] - vector_summary["api_latency_ms"]["p95"], 4),
                "token_usage_avg": round(keyword_summary["token_usage"]["avg"] - vector_summary["token_usage"]["avg"], 4),
                "behavior_failures": keyword_summary["behavior_failures"] - vector_summary["behavior_failures"],
                "citation_failures": keyword_summary["citation_failures"] - vector_summary["citation_failures"],
                "recall_at_1": round(keyword_summary["retrieval"]["recall_at_1"] - vector_summary["retrieval"]["recall_at_1"], 4),
                "recall_at_5": round(keyword_summary["retrieval"]["recall_at_5"] - vector_summary["retrieval"]["recall_at_5"], 4),
            },
            "response_deltas": response_deltas(cases, vector_responses, keyword_responses),
            "cost_boundary": "API capture exposes token usage but not estimated cost; no cost conclusion is made.",
        }
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote paired backend A/B report to {args.output}")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"backend A/B scoring error: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
