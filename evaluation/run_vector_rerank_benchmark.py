#!/usr/bin/env python3
"""Compare document-level vector context selection without calling a model.

The input is produced by ``run_vector_candidate_diagnostic.py`` and contains
ACL-filtered vector metadata only. This benchmark compares the current first
Top-K chunks with two bounded document-diversity selectors. It measures
candidate recall, context interference, refusal candidates, and ACL leakage;
it does not claim answer quality and does not modify production retrieval.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "retrieval_stress_v1.jsonl"
VARIANTS = ("vector_top_k", "document_diversity_first", "document_cap_one")


def fail(message: str) -> None:
    raise ValueError(message)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as exc:
            fail(f"{path}:{line_number} invalid JSON: {exc.msg}")
        if not isinstance(value, dict):
            fail(f"{path}:{line_number} must contain an object")
        rows.append(value)
    if not rows:
        fail(f"{path} is empty")
    return rows


def load_diagnostic(path: Path) -> dict[str, dict[str, Any]]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"invalid diagnostic report: {exc}")
    rows = report.get("results") if isinstance(report, dict) else None
    if not isinstance(rows, list) or not rows:
        fail("diagnostic report requires a non-empty results list")
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str):
            fail("diagnostic results require string ids")
        case_id = row["id"]
        if case_id in result:
            fail(f"duplicate diagnostic case: {case_id}")
        hits = row.get("hits")
        if not isinstance(hits, list):
            fail(f"{case_id}.hits must be a list")
        validated: list[dict[str, Any]] = []
        for rank, hit in enumerate(hits, 1):
            if not isinstance(hit, dict) or hit.get("rank") != rank:
                fail(f"{case_id}.hits ranks must be contiguous")
            if not isinstance(hit.get("filename"), str) or not hit["filename"]:
                fail(f"{case_id}.hits.filename must be non-empty")
            validated.append(
                {
                    "rank": rank,
                    "filename": hit["filename"],
                    "locator": hit.get("locator"),
                    "similarity": hit.get("similarity"),
                    "chunkId": hit.get("chunkId"),
                }
            )
        result[case_id] = {"hits": validated, "aclLeakage": row.get("aclLeakage") is True}
    return result


def vector_top_k(hits: list[dict[str, Any]], context_k: int) -> list[dict[str, Any]]:
    return hits[:context_k]


def document_diversity_first(hits: list[dict[str, Any]], context_k: int) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    seen_documents: set[str] = set()
    for hit in hits:
        if hit["filename"] in seen_documents:
            continue
        selected.append(hit)
        seen_documents.add(hit["filename"])
        if len(selected) == context_k:
            return selected
    for hit in hits:
        if hit in selected:
            continue
        selected.append(hit)
        if len(selected) == context_k:
            break
    return selected


def document_cap_one(hits: list[dict[str, Any]], context_k: int) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    seen_documents: set[str] = set()
    for hit in hits:
        if hit["filename"] in seen_documents:
            continue
        selected.append(hit)
        seen_documents.add(hit["filename"])
        if len(selected) == context_k:
            break
    return selected


SELECTORS: dict[str, Callable[[list[dict[str, Any]], int], list[dict[str, Any]]]] = {
    "vector_top_k": vector_top_k,
    "document_diversity_first": document_diversity_first,
    "document_cap_one": document_cap_one,
}


def score_case(case: dict[str, Any], selected: list[dict[str, Any]], source_acl_leakage: bool) -> dict[str, Any]:
    expected = set(case.get("expected_source_documents", []))
    forbidden = set(case.get("forbidden_documents", []))
    context_documents = {hit["filename"] for hit in selected}
    leakage = sorted(context_documents & forbidden)
    expected_in_context = expected & context_documents
    return {
        "id": case["id"],
        "behavior": case.get("expected_behavior"),
        "selectedCount": len(selected),
        "uniqueDocumentCount": len(context_documents),
        "expectedSourcesInContext": sorted(expected_in_context),
        "missingExpectedSources": sorted(expected - context_documents),
        "recallAtContext": round(len(expected_in_context) / len(expected), 4) if expected else None,
        "contextInterferenceCount": len(context_documents - expected),
        "refusalHasCandidates": not expected and bool(selected),
        "aclLeakage": source_acl_leakage or bool(leakage),
        "aclLeakedDocuments": leakage,
        "hits": [
            {key: hit.get(key) for key in ("rank", "filename", "locator", "similarity")}
            for hit in selected
        ],
    }


def aggregate(rows: list[dict[str, Any]], method: str) -> dict[str, Any]:
    answerable = [row for row in rows if row["behavior"] == "ANSWER"]
    refusal = [row for row in rows if row["behavior"] != "ANSWER"]
    context_slots = sum(row["selectedCount"] for row in answerable)
    interference = sum(row["contextInterferenceCount"] for row in answerable)
    return {
        "method": method,
        "caseCount": len(rows),
        "answerableCaseCount": len(answerable),
        "answerableRecallAtContext": round(
            statistics.mean(row["recallAtContext"] for row in answerable), 4
        ) if answerable else None,
        "answerableContextInterferenceRate": round(interference / context_slots, 4) if context_slots else 0.0,
        "refusalCandidateCount": sum(1 for row in refusal if row["refusalHasCandidates"]),
        "aclLeakageCount": sum(1 for row in rows if row["aclLeakage"]),
        "uniqueDocumentCountAvg": round(statistics.mean(row["uniqueDocumentCount"] for row in rows), 4),
        "passedCandidateBehavior": sum(
            1
            for row in rows
            if (row["behavior"] == "ANSWER" and row["recallAtContext"] == 1.0)
            or (row["behavior"] != "ANSWER" and not row["refusalHasCandidates"])
        ),
        "caseResults": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--diagnostic", type=Path, required=True)
    parser.add_argument("--case-id", action="append", dest="case_ids")
    parser.add_argument("--context-k", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.context_k < 1:
        parser.error("context-k must be positive")

    try:
        all_cases = load_jsonl(args.dataset)
        selected_ids = set(args.case_ids or [str(case.get("id")) for case in all_cases])
        cases = [case for case in all_cases if case.get("id") in selected_ids]
        if {str(case.get("id")) for case in cases} != selected_ids:
            fail(f"unknown case ids: {sorted(selected_ids - {str(case.get('id')) for case in cases})}")
        diagnostics = load_diagnostic(args.diagnostic)
        case_ids = {str(case.get("id")) for case in cases}
        if case_ids != set(diagnostics):
            fail("dataset and diagnostic case IDs must match exactly")
        benchmarks: dict[str, Any] = {}
        for method, selector in SELECTORS.items():
            scored: list[dict[str, Any]] = []
            for case in cases:
                diagnostic = diagnostics[case["id"]]
                selected = selector(diagnostic["hits"], args.context_k)
                scored.append(score_case(case, selected, diagnostic["aclLeakage"]))
            benchmarks[method] = aggregate(scored, method)
        report = {
            "schemaVersion": "vector-rerank-benchmark-v1",
            "dataset": str(args.dataset),
            "diagnostic": str(args.diagnostic),
            "contextK": args.context_k,
            "productionPathChanged": False,
            "benchmarks": benchmarks,
            "decision": {
                "recommendation": "keep_vector_top_k_5_until_end_to_end_gate",
                "reason": "This is candidate-level evidence only; a selector must pass answer-quality, refusal, ACL, latency, and token gates before runtime adoption.",
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote vector rerank benchmark to {args.output}")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"vector rerank benchmark error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
