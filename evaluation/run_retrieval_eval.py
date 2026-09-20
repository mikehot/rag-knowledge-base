#!/usr/bin/env python3
"""Score protected retrieval diagnostics against a versioned evaluation dataset."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

from run_eval import load_jsonl, validate_dataset


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "golden_v1.jsonl"


def fail(message: str) -> None:
    raise ValueError(message)


def finite_number(value: Any, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        fail(f"{field} must be a finite number")
    return float(value)


def validate_hits(row_id: str, diagnostics: dict[str, Any]) -> list[dict[str, Any]]:
    hits = diagnostics.get("hits")
    if not isinstance(hits, list):
        fail(f"{row_id}.diagnostics.hits must be a list")
    validated: list[dict[str, Any]] = []
    for index, hit in enumerate(hits, 1):
        if not isinstance(hit, dict):
            fail(f"{row_id}.diagnostics.hits[{index}] must be an object")
        rank = hit.get("rank")
        if not isinstance(rank, int) or isinstance(rank, bool) or rank != index:
            fail(f"{row_id}.diagnostics.hits ranks must be contiguous from 1")
        filename = hit.get("filename")
        if not isinstance(filename, str) or not filename.strip():
            fail(f"{row_id}.diagnostics.hits[{index}].filename must be non-empty")
        similarity = finite_number(hit.get("similarity"), f"{row_id}.diagnostics.hits[{index}].similarity")
        if similarity < -1 or similarity > 1:
            fail(f"{row_id}.diagnostics.hits[{index}].similarity must be between -1 and 1")
        validated.append({"rank": rank, "filename": filename, "similarity": similarity})
    candidate_count = diagnostics.get("candidateCount")
    if candidate_count is not None and candidate_count != len(validated):
        fail(f"{row_id}.diagnostics.candidateCount does not match hits")
    return validated


def score_case(case: dict[str, Any], row: dict[str, Any], ks: list[int]) -> dict[str, Any]:
    errors: list[str] = []
    status = row.get("statusCode")
    if status != 200:
        errors.append(f"expected diagnostic statusCode=200, got {status}")
    diagnostics = row.get("diagnostics")
    if not isinstance(diagnostics, dict):
        errors.append("diagnostics must be an object")
        diagnostics = {"hits": []}
    try:
        hits = validate_hits(case["id"], diagnostics)
    except ValueError as exc:
        errors.append(str(exc))
        hits = []

    expected = set(case["expected_source_documents"])
    forbidden = set(case["forbidden_documents"])
    forbidden_seen = sorted({hit["filename"] for hit in hits} & forbidden)
    if forbidden_seen:
        errors.append(f"forbidden candidate leaked: {forbidden_seen}")
    hit_names = {hit["filename"] for hit in hits}
    missing_expected = sorted(expected - hit_names)
    if missing_expected:
        errors.append(f"missing expected candidate sources: {missing_expected}")
    recall_at_k: dict[str, float | None] = {}
    for k in ks:
        if expected:
            names = {hit["filename"] for hit in hits if hit["rank"] <= k}
            recall_at_k[f"recall_at_{k}"] = round(len(expected & names) / len(expected), 4)
        else:
            recall_at_k[f"recall_at_{k}"] = None
    matching_ranks = [hit["rank"] for hit in hits if hit["filename"] in expected]
    first_expected_rank = min(matching_ranks) if matching_ranks else None
    top_similarity = hits[0]["similarity"] if hits else None
    top1_margin = hits[0]["similarity"] - hits[1]["similarity"] if len(hits) > 1 else None
    return {
        "id": case["id"],
        "passed": not errors,
        "expected_sources": sorted(expected),
        "all_expected_sources_found": not missing_expected,
        "candidate_count": len(hits),
        "recall_at_k": recall_at_k,
        "first_expected_rank": first_expected_rank,
        "top_similarity": top_similarity,
        "top1_margin": top1_margin,
        "acl_leakage": bool(forbidden_seen),
        "errors": errors,
    }


def average(results: list[dict[str, Any]], field: str) -> float | None:
    values = [result[field] for result in results if result.get(field) is not None]
    return round(sum(values) / len(values), 4) if values else None


def evaluate(cases: list[dict[str, Any]], rows: list[dict[str, Any]], ks: list[int]) -> dict[str, Any]:
    case_by_id = {case["id"]: case for case in cases}
    row_by_id: dict[str, dict[str, Any]] = {}
    input_errors: list[str] = []
    for row in rows:
        row_id = row.get("id")
        if row_id not in case_by_id:
            input_errors.append(f"unknown diagnostics id: {row_id}")
        elif row_id in row_by_id:
            input_errors.append(f"duplicate diagnostics id: {row_id}")
        else:
            row_by_id[row_id] = row
    missing = sorted(set(case_by_id) - set(row_by_id))
    if missing:
        input_errors.append(f"missing diagnostics: {missing}")
    results = [score_case(case_by_id[row_id], row_by_id[row_id], ks) for row_id in sorted(row_by_id)]
    results.extend({"id": "<input>", "passed": False, "errors": [error]} for error in input_errors)
    answerable = [result for result in results if result.get("expected_sources")]
    report: dict[str, Any] = {
        "diagnostics_evaluated": len(row_by_id),
        "passed": sum(1 for result in results if result["passed"]),
        "failed": sum(1 for result in results if not result["passed"]),
        "answerable_cases": len(answerable),
        "acl_leakage_count": sum(1 for result in results if result.get("acl_leakage")),
        "schema_failure_count": sum(1 for result in results if result.get("errors") and not result.get("acl_leakage")),
        "candidate_count_avg": average(results, "candidate_count"),
        "first_expected_rank_avg": average(answerable, "first_expected_rank"),
        "top_similarity_avg": average(results, "top_similarity"),
        "top1_margin_avg": average(results, "top1_margin"),
        "case_results": results,
    }
    for k in ks:
        key = f"recall_at_{k}"
        values = [result["recall_at_k"][key] for result in answerable if result["recall_at_k"][key] is not None]
        report[key] = round(sum(values) / len(values), 4) if values else None
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--expected-case-count", type=int, default=20)
    parser.add_argument("--diagnostics", type=Path, required=True)
    parser.add_argument("--ks", default="1,3,5", help="Comma-separated cutoffs; defaults to 1,3,5")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        ks = sorted({int(value) for value in args.ks.split(",") if value.strip()})
        if not ks or any(k < 1 for k in ks):
            fail("--ks must contain positive integers")
        cases = load_jsonl(args.dataset)
        version, behaviors = validate_dataset(cases, args.expected_case_count)
        rows = load_jsonl(args.diagnostics)
        report = {
            "dataset": str(args.dataset),
            "dataset_version": version,
            "expected_case_count": args.expected_case_count,
            "behavior_counts": dict(sorted(behaviors.items())),
            "ks": ks,
            "retrieval_evaluation": evaluate(cases, rows, ks),
        }
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"retrieval evaluation error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
