#!/usr/bin/env python3
"""Validate the Golden Dataset and optionally score flattened RAG responses.

The default mode is offline and deterministic: it does not call a model, database,
or network. A response file is optional so the dataset can be checked before the
real API/model evaluation is available.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "golden_v1.jsonl"
ALLOWED_BEHAVIORS = {"ANSWER", "REFUSE", "ACL_FILTERED_REFUSAL", "PERMISSION_DENIED"}
REQUIRED_CASE_FIELDS = {
    "dataset_version",
    "id",
    "question",
    "answerable",
    "expected_answer_points",
    "expected_source_documents",
    "acting_user",
    "forbidden_documents",
    "expected_behavior",
    "refusal_match_terms",
}
REQUIRED_USER_FIELDS = {"id", "department", "role", "knowledge_base"}
QUALITY_RULE_FIELDS = {
    "min_point_coverage",
    "max_unexpected_source_count",
    "max_source_count",
    "require_grounded",
    "require_refusal_contract",
}


def fail(message: str) -> None:
    raise ValueError(message)


def as_nonempty_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{field} must be a non-empty string")
    return value


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        fail(f"file not found: {path}")
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            fail(f"{path}:{line_number} invalid JSON: {exc.msg}")
        if not isinstance(value, dict):
            fail(f"{path}:{line_number} must contain a JSON object")
        rows.append(value)
    return rows


def validate_case(case: dict[str, Any], expected_version: str) -> None:
    missing = REQUIRED_CASE_FIELDS - case.keys()
    if missing:
        fail(f"{case.get('id', '<unknown>')} missing fields: {sorted(missing)}")
    if case["dataset_version"] != expected_version:
        fail(f"{case['id']} has mixed dataset version: {case['dataset_version']}")
    if not re.fullmatch(r"[A-Z][A-Z0-9_-]*-\d{3}", str(case["id"])):
        fail(f"invalid case id: {case['id']}")
    as_nonempty_string(case["question"], f"{case['id']}.question")
    if not isinstance(case["answerable"], bool):
        fail(f"{case['id']}.answerable must be boolean")
    if not isinstance(case["expected_answer_points"], list):
        fail(f"{case['id']}.expected_answer_points must be a list")
    for point in case["expected_answer_points"]:
        if not isinstance(point, dict):
            fail(f"{case['id']} answer points must be objects")
        as_nonempty_string(point.get("id"), f"{case['id']}.point.id")
        as_nonempty_string(point.get("description"), f"{case['id']}.point.description")
        terms = point.get("match_any")
        if not isinstance(terms, list) or not terms or not all(isinstance(term, str) and term.strip() for term in terms):
            fail(f"{case['id']}.point.match_any must contain strings")
    for field in ("expected_source_documents", "forbidden_documents", "refusal_match_terms"):
        if not isinstance(case[field], list) or not all(isinstance(item, str) and item.strip() for item in case[field]):
            fail(f"{case['id']}.{field} must be a list of non-empty strings")
    user = case["acting_user"]
    if not isinstance(user, dict) or not REQUIRED_USER_FIELDS.issubset(user.keys()):
        fail(f"{case['id']}.acting_user missing fields")
    for field in REQUIRED_USER_FIELDS:
        as_nonempty_string(user[field], f"{case['id']}.acting_user.{field}")
    behavior = case["expected_behavior"]
    if behavior not in ALLOWED_BEHAVIORS:
        fail(f"{case['id']} has unsupported behavior: {behavior}")
    sources = set(case["expected_source_documents"])
    forbidden = set(case["forbidden_documents"])
    if sources & forbidden:
        fail(f"{case['id']} source and forbidden documents overlap")
    if behavior == "ANSWER":
        if not case["answerable"] or not case["expected_answer_points"] or not sources:
            fail(f"{case['id']} ANSWER cases require answerable, points, and sources")
    elif behavior in {"REFUSE", "ACL_FILTERED_REFUSAL"}:
        if case["answerable"] or case["expected_answer_points"] or sources or not case["refusal_match_terms"]:
            fail(f"{case['id']} refusal cases have inconsistent expectations")
        if behavior == "ACL_FILTERED_REFUSAL" and not forbidden:
            fail(f"{case['id']} ACL_FILTERED_REFUSAL requires forbidden documents")
    elif behavior == "PERMISSION_DENIED" and case["answerable"]:
        fail(f"{case['id']} PERMISSION_DENIED cannot be answerable")
    quality_rules = case.get("quality_rules", {})
    if not isinstance(quality_rules, dict):
        fail(f"{case['id']}.quality_rules must be an object")
    unknown_rules = set(quality_rules) - QUALITY_RULE_FIELDS
    if unknown_rules:
        fail(f"{case['id']}.quality_rules has unsupported fields: {sorted(unknown_rules)}")
    if "min_point_coverage" in quality_rules:
        value = quality_rules["min_point_coverage"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1:
            fail(f"{case['id']}.quality_rules.min_point_coverage must be between 0 and 1")
    for field in ("max_unexpected_source_count", "max_source_count"):
        if field in quality_rules:
            value = quality_rules[field]
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                fail(f"{case['id']}.quality_rules.{field} must be a non-negative integer")
    for field in ("require_grounded", "require_refusal_contract"):
        if field in quality_rules and not isinstance(quality_rules[field], bool):
            fail(f"{case['id']}.quality_rules.{field} must be boolean")


def validate_dataset(cases: list[dict[str, Any]], expected_case_count: int = 20) -> tuple[str, Counter[str]]:
    if len(cases) != expected_case_count:
        fail(f"expected exactly {expected_case_count} cases, found {len(cases)}")
    versions = {case.get("dataset_version") for case in cases}
    if len(versions) != 1 or None in versions:
        fail(f"expected one dataset version, found {sorted(versions, key=str)}")
    version = next(iter(versions))
    ids = [case.get("id") for case in cases]
    if len(set(ids)) != len(ids):
        fail("case ids must be unique")
    for case in cases:
        validate_case(case, version)
    behaviors = Counter(case["expected_behavior"] for case in cases)
    return version, behaviors


def normalize(value: str) -> str:
    return "".join(value.casefold().split())


def source_names(response: dict[str, Any]) -> set[str]:
    names: set[str] = set()
    for source in response.get("sources", []):
        if isinstance(source, dict):
            filename = source.get("filename") or source.get("document")
            if isinstance(filename, str) and filename.strip():
                names.add(filename)
    return names


def score_response(case: dict[str, Any], response: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
    answer = response.get("answer")
    found = response.get("found")
    grounded = response.get("grounded")
    sources = response.get("sources")
    if not isinstance(answer, str):
        errors.append("answer must be a string")
        answer = ""
    if not isinstance(found, bool):
        errors.append("found must be boolean")
        found = False
    if not isinstance(grounded, bool):
        errors.append("grounded must be boolean")
        grounded = False
    if not isinstance(sources, list):
        errors.append("sources must be a list")
        sources = []
    response["sources"] = sources
    response["grounded"] = grounded
    names = source_names(response)
    forbidden = set(case["forbidden_documents"])
    leakage = sorted(names & forbidden)
    if leakage:
        errors.append(f"forbidden source leaked: {leakage}")

    point_results: list[dict[str, Any]] = []
    answer_normalized = normalize(answer)
    for point in case["expected_answer_points"]:
        matched_terms = [term for term in point["match_any"] if normalize(term) in answer_normalized]
        point_results.append({"id": point["id"], "matched": bool(matched_terms), "matched_terms": matched_terms})
    expected_sources = set(case["expected_source_documents"])
    unexpected_sources = sorted(names - expected_sources)
    citation_coverage = bool(names) if expected_sources else None
    citation_correctness = bool(expected_sources <= names) if expected_sources else None
    behavior = case["expected_behavior"]
    if behavior == "ANSWER":
        errors.extend([] if found else ["expected found=true"])
        errors.extend([] if grounded else ["expected grounded=true"])
        missing_sources = sorted(expected_sources - names)
        if missing_sources:
            errors.append(f"missing expected sources: {missing_sources}")
        missing_points = [item["id"] for item in point_results if not item["matched"]]
        if missing_points:
            errors.append(f"missing answer points: {missing_points}")
    elif behavior in {"REFUSE", "ACL_FILTERED_REFUSAL"}:
        if found:
            errors.append("expected found=false")
        if grounded:
            errors.append("expected grounded=false")
        if not any(normalize(term) in answer_normalized for term in case["refusal_match_terms"]):
            errors.append("answer does not match the refusal contract")
    elif behavior == "PERMISSION_DENIED":
        if response.get("statusCode") != 403:
            errors.append("expected statusCode=403")

    point_coverage = (
        sum(1 for item in point_results if item["matched"]) / len(point_results)
        if point_results
        else None
    )
    quality_rules = case.get("quality_rules", {})
    quality_errors: list[str] = []
    if "min_point_coverage" in quality_rules and (
        point_coverage is None or point_coverage < quality_rules["min_point_coverage"]
    ):
        quality_errors.append(
            f"point coverage below {quality_rules['min_point_coverage']:.4f}"
        )
    if "max_unexpected_source_count" in quality_rules and len(unexpected_sources) > quality_rules["max_unexpected_source_count"]:
        quality_errors.append(f"unexpected source count: {len(unexpected_sources)}")
    if "max_source_count" in quality_rules and len(names) > quality_rules["max_source_count"]:
        quality_errors.append(f"source count: {len(names)}")
    if quality_rules.get("require_grounded") and not grounded:
        quality_errors.append("grounded contract is false")
    if quality_rules.get("require_refusal_contract"):
        if found or grounded or names:
            quality_errors.append("refusal contract is not fail-closed")
        if not any(normalize(term) in answer_normalized for term in case["refusal_match_terms"]):
            quality_errors.append("refusal answer does not contain an allowed handoff term")
    quality_gate_pass = not quality_errors if quality_rules else None
    return {
        "id": case["id"],
        "passed": not errors,
        "behavior": behavior,
        "schema_ok": not any(item.startswith(("answer", "found", "grounded", "sources")) for item in errors),
        "point_coverage": point_coverage,
        "citation_coverage": citation_coverage,
        "citation_correctness": citation_correctness,
        "unexpected_sources": unexpected_sources,
        "quality_gate_pass": quality_gate_pass,
        "quality_errors": quality_errors,
        "acl_leakage": bool(leakage),
        "errors": errors,
    }


def evaluate_responses(cases: list[dict[str, Any]], responses: list[dict[str, Any]]) -> dict[str, Any]:
    case_by_id = {case["id"]: case for case in cases}
    response_by_id: dict[str, dict[str, Any]] = {}
    errors: list[str] = []
    for response in responses:
        response_id = response.get("id")
        if response_id not in case_by_id:
            errors.append(f"unknown response id: {response_id}")
        elif response_id in response_by_id:
            errors.append(f"duplicate response id: {response_id}")
        else:
            response_by_id[response_id] = response
    missing = sorted(set(case_by_id) - set(response_by_id))
    if missing:
        errors.append(f"missing responses: {missing}")
    results = [score_response(case_by_id[case_id], response_by_id[case_id]) for case_id in sorted(response_by_id)]
    all_results = results + [{"id": "<input>", "passed": False, "errors": [error]} for error in errors]

    def average(field: str, only: list[dict[str, Any]]) -> float | None:
        values = [item[field] for item in only if item.get(field) is not None]
        return round(sum(values) / len(values), 4) if values else None

    answerable = [item for item in results if item["behavior"] == "ANSWER"]
    refusals = [item for item in results if item["behavior"] in {"REFUSE", "ACL_FILTERED_REFUSAL"}]
    quality_results = [item for item in results if item.get("quality_gate_pass") is not None]
    return {
        "responses_evaluated": len(results),
        "passed": sum(1 for item in all_results if item["passed"]),
        "failed": sum(1 for item in all_results if not item["passed"]),
        "answerable_pass_rate": round(sum(1 for item in answerable if item["passed"]) / len(answerable), 4) if answerable else None,
        "citation_coverage": average("citation_coverage", answerable),
        "citation_correctness": average("citation_correctness", answerable),
        "expected_answer_point_coverage": average("point_coverage", answerable),
        "refusal_correctness": round(sum(1 for item in refusals if item["passed"]) / len(refusals), 4) if refusals else None,
        "quality_gate_pass_rate": round(sum(1 for item in quality_results if item["quality_gate_pass"]) / len(quality_results), 4) if quality_results else None,
        "quality_gate_failure_count": sum(1 for item in quality_results if not item["quality_gate_pass"]),
        "acl_leakage_count": sum(1 for item in results if item["acl_leakage"]),
        "schema_failure_count": sum(1 for item in results if not item["schema_ok"]),
        "case_results": all_results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument(
        "--expected-case-count",
        type=int,
        default=20,
        help="Expected number of cases; defaults to the frozen 20-case golden baseline",
    )
    parser.add_argument("--responses", type=Path, help="Optional flattened response JSONL for deterministic scoring")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    args = parser.parse_args()
    try:
        cases = load_jsonl(args.dataset)
        if args.expected_case_count < 1:
            fail("--expected-case-count must be positive")
        version, behaviors = validate_dataset(cases, args.expected_case_count)
        report: dict[str, Any] = {
            "dataset": str(args.dataset),
            "dataset_version": version,
            "dataset_valid": True,
            "case_count": len(cases),
            "expected_case_count": args.expected_case_count,
            "behavior_counts": dict(sorted(behaviors.items())),
            "responses_evaluated": 0,
        }
        if args.responses:
            responses = load_jsonl(args.responses)
            report["response_evaluation"] = evaluate_responses(cases, responses)
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, ValueError) as exc:
        print(f"evaluation error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
