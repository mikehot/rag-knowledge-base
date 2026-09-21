#!/usr/bin/env python3
"""Run an authenticated vector-vs-keyword retrieval A/B capture.

The vector side is an existing /api/ask capture plus protected retrieval
diagnostics. The keyword side reads only ACL-visible chunks from the local
disposable PostgreSQL fixture, ranks them with the answer-term-independent
normalization used by the offline benchmark, and simulates keyword-weight-2
RRF. This is evaluation-only; it does not change the backend retrieval path.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any

from run_keyword_candidate_benchmark import build_index, load_jsonl, rank_candidates


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "keyword_candidates_v1.jsonl"
DEFAULT_OUTPUT = ROOT / "evaluation" / "reports" / "retrieval-online-ab-v1-local.json"
DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001"
DEFAULT_KNOWLEDGE_BASE_ID = "00000000-0000-0000-0000-000000000101"


def fail(message: str) -> None:
    raise ValueError(message)


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 4)


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"invalid fixture manifest: {exc}")
    if not isinstance(value, dict) or not isinstance(value.get("actors"), dict):
        fail("fixture manifest requires an actors object")
    return value


def uuid_text(value: Any, field: str) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, AttributeError, TypeError) as exc:
        fail(f"{field} must be a UUID")
        raise AssertionError from exc


def load_diagnostics(path: Path) -> dict[str, dict[str, Any]]:
    rows = load_jsonl(path)
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        case_id = row.get("id")
        diagnostics = row.get("diagnostics")
        if not isinstance(case_id, str) or not isinstance(diagnostics, dict):
            fail("diagnostics rows require id and diagnostics")
        hits = diagnostics.get("hits")
        if not isinstance(hits, list):
            fail(f"{case_id}.diagnostics.hits must be a list")
        validated: list[dict[str, Any]] = []
        for expected_rank, hit in enumerate(hits, 1):
            if not isinstance(hit, dict) or hit.get("rank") != expected_rank:
                fail(f"{case_id} vector hit ranks must be contiguous")
            validated.append(
                {
                    "chunkId": uuid_text(hit.get("chunkId"), f"{case_id}.chunkId"),
                    "documentId": uuid_text(hit.get("documentId"), f"{case_id}.documentId"),
                    "filename": str(hit.get("filename")),
                    "locator": str(hit.get("locator")),
                    "rank": expected_rank,
                    "similarity": hit.get("similarity"),
                }
            )
        result[case_id] = {
            "hits": validated,
            "diagnosticFetchLatencyMs": row.get("httpLatencyMs"),
            "topK": diagnostics.get("topK"),
        }
    return result


def load_responses(path: Path) -> dict[str, dict[str, Any]]:
    rows = load_jsonl(path)
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        case_id = row.get("id")
        if not isinstance(case_id, str) or case_id in result:
            fail(f"invalid or duplicate response id: {case_id}")
        result[case_id] = row
    return result


def fetch_acl_chunks(
    user_id: str,
    tenant_id: str,
    knowledge_base_id: str,
    db_service: str,
    db_user: str,
    db_name: str,
    timeout: float,
) -> tuple[list[dict[str, Any]], float]:
    # The predicates intentionally mirror ChunkJdbcRepository.search(). The
    # query returns only metadata and content for this local benchmark; output
    # files never persist content.
    sql = f"""
SELECT COALESCE(json_agg(json_build_object(
    'chunkId', c.id::text,
    'documentId', d.id::text,
    'filename', d.filename,
    'locator', c.locator,
    'content', c.content
) ORDER BY d.filename, c.seq), '[]'::json)::text
FROM chunk c
JOIN document d ON d.id = c.document_id
WHERE d.tenant_id = '{tenant_id}'::uuid
  AND d.knowledge_base_id = '{knowledge_base_id}'::uuid
  AND d.status = 'READY'
  AND d.deleted_at IS NULL
  AND d.disabled_at IS NULL
  AND (
    d.user_id = '{user_id}'::uuid
    OR EXISTS (
      SELECT 1 FROM user_role ur
      JOIN app_role r ON r.id = ur.role_id
      WHERE ur.user_id = '{user_id}'::uuid
        AND r.tenant_id = d.tenant_id
        AND r.code = 'SYSTEM_ADMIN'
    )
    OR EXISTS (
      SELECT 1 FROM knowledge_base_membership m
      WHERE m.knowledge_base_id = d.knowledge_base_id
        AND m.tenant_id = d.tenant_id
        AND m.permission IN ('READ', 'MANAGE')
        AND (
          (m.principal_type = 'USER' AND m.principal_id = '{user_id}'::uuid)
          OR (m.principal_type = 'DEPARTMENT' AND m.principal_id = (
            SELECT u.department_id FROM app_user u
            WHERE u.id = '{user_id}'::uuid AND u.tenant_id = d.tenant_id
          ))
          OR (m.principal_type = 'ROLE' AND EXISTS (
            SELECT 1 FROM user_role ur
            WHERE ur.user_id = '{user_id}'::uuid AND ur.role_id = m.principal_id
          ))
        )
    )
    OR EXISTS (
      SELECT 1 FROM document_acl a
      WHERE a.document_id = d.id
        AND a.tenant_id = d.tenant_id
        AND a.permission IN ('READ', 'MANAGE')
        AND (
          (a.principal_type = 'USER' AND a.principal_id = '{user_id}'::uuid)
          OR (a.principal_type = 'DEPARTMENT' AND a.principal_id = (
            SELECT u.department_id FROM app_user u
            WHERE u.id = '{user_id}'::uuid AND u.tenant_id = d.tenant_id
          ))
          OR (a.principal_type = 'ROLE' AND EXISTS (
            SELECT 1 FROM user_role ur
            WHERE ur.user_id = '{user_id}'::uuid AND ur.role_id = a.principal_id
          ))
        )
    )
  );
"""
    command = [
        "docker",
        "compose",
        "exec",
        "-T",
        db_service,
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        db_user,
        "-d",
        db_name,
        "-At",
        "-c",
        sql,
    ]
    started = time.monotonic()
    completed = subprocess.run(
        command,
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    elapsed_ms = round((time.monotonic() - started) * 1000, 2)
    if completed.returncode != 0:
        fail(f"ACL chunk query failed: {completed.stderr[-500:]}")
    try:
        rows = json.loads(completed.stdout.strip() or "[]")
    except json.JSONDecodeError as exc:
        fail(f"ACL chunk query returned invalid JSON: {exc.msg}")
    if not isinstance(rows, list):
        fail("ACL chunk query must return a JSON array")
    return rows, elapsed_ms


def rank_keyword(question: str, chunks: list[dict[str, Any]], limit: int) -> tuple[list[dict[str, Any]], float]:
    corpus = {chunk["chunkId"]: str(chunk["content"]) for chunk in chunks}
    index = build_index(corpus, normalized=True)
    started = time.perf_counter_ns()
    raw_hits = rank_candidates(question, corpus, set(corpus), True, limit, index)
    elapsed_ms = round((time.perf_counter_ns() - started) / 1_000_000, 4)
    metadata = {chunk["chunkId"]: chunk for chunk in chunks}
    hits: list[dict[str, Any]] = []
    for hit in raw_hits:
        chunk = metadata[hit["filename"]]
        hits.append(
            {
                "chunkId": chunk["chunkId"],
                "documentId": chunk["documentId"],
                "filename": chunk["filename"],
                "locator": chunk["locator"],
                "rank": hit["rank"],
                "score": hit["score"],
            }
        )
    return hits, elapsed_ms


def fuse(vector_hits: list[dict[str, Any]], keyword_hits: list[dict[str, Any]], keyword_weight: float, rrf_k: int) -> list[dict[str, Any]]:
    by_chunk: dict[str, dict[str, Any]] = {}
    scores: dict[str, float] = {}
    for hit in vector_hits:
        key = hit["chunkId"]
        by_chunk[key] = dict(hit)
        scores[key] = scores.get(key, 0.0) + 1 / (rrf_k + hit["rank"])
    for hit in keyword_hits:
        key = hit["chunkId"]
        by_chunk.setdefault(key, dict(hit)).update({"keywordRank": hit["rank"]})
        scores[key] = scores.get(key, 0.0) + keyword_weight / (rrf_k + hit["rank"])
    ordered = sorted(scores, key=lambda key: (-scores[key], by_chunk[key]["filename"], key))
    result: list[dict[str, Any]] = []
    for rank, key in enumerate(ordered, 1):
        item = dict(by_chunk[key])
        item["rank"] = rank
        item["rrfScore"] = round(scores[key], 8)
        result.append(item)
    return result


def retrieval_case_score(case: dict[str, Any], hits: list[dict[str, Any]], context_k: int) -> dict[str, Any]:
    expected = set(case["expected_source_documents"])
    forbidden = set(case["forbidden_documents"])
    context_names = {hit["filename"] for hit in hits[:context_k]}
    hit_names = {hit["filename"] for hit in hits}
    recall = {}
    for cutoff in (1, 3, 5):
        recall[f"recall_at_{cutoff}"] = (
            round(len(expected & {hit["filename"] for hit in hits if hit["rank"] <= cutoff}) / len(expected), 4)
            if expected
            else None
        )
    leakage = sorted(hit_names & forbidden)
    return {
        "id": case["id"],
        "behavior": case["expected_behavior"],
        "recall_at_k": recall,
        "first_expected_rank": min((hit["rank"] for hit in hits if hit["filename"] in expected), default=None),
        "context_interference_count": len(context_names - expected),
        "context_documents": sorted(context_names),
        "acl_leakage": bool(leakage),
        "acl_leaked_documents": leakage,
        "refusal_has_candidates": not expected and bool(hits),
        "hits": [
            {key: value for key, value in hit.items() if key not in {"content"}}
            for hit in hits[:context_k]
        ],
    }


def aggregate(results: list[dict[str, Any]], timings: list[float], method: str, context_k: int) -> dict[str, Any]:
    answerable = [result for result in results if result["behavior"] == "ANSWER"]
    refusal = [result for result in results if result["behavior"] != "ANSWER"]

    def average(values: list[float]) -> float | None:
        return round(statistics.mean(values), 4) if values else None

    context_slots = sum(min(context_k, len(result["hits"])) for result in answerable)
    interference = sum(result["context_interference_count"] for result in answerable)
    return {
        "method": method,
        "case_count": len(results),
        "answerable_case_count": len(answerable),
        "recall_at_1": average([result["recall_at_k"]["recall_at_1"] for result in answerable]),
        "recall_at_3": average([result["recall_at_k"]["recall_at_3"] for result in answerable]),
        "recall_at_5": average([result["recall_at_k"]["recall_at_5"] for result in answerable]),
        "first_expected_rank_avg": average([float(result["first_expected_rank"]) for result in answerable if result["first_expected_rank"] is not None]),
        "answerable_context_interference_rate": round(interference / context_slots, 4) if context_slots else 0.0,
        "refusal_candidate_count": sum(1 for result in refusal if result["refusal_has_candidates"]),
        "acl_leakage_count": sum(1 for result in results if result["acl_leakage"]),
        "stage_latency_ms": {
            "p50": percentile(timings, 0.50),
            "p95": percentile(timings, 0.95),
            "max": round(max(timings), 4) if timings else None,
        },
        "case_results": results,
    }


def api_summary(cases: list[dict[str, Any]], responses: dict[str, dict[str, Any]]) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    latencies: list[float] = []
    token_values: list[float] = []
    behavior_failures = 0
    for case in cases:
        response = responses[case["id"]]
        found = response.get("found") is True
        expected_answer = case["expected_behavior"] == "ANSWER"
        if found != expected_answer:
            behavior_failures += 1
        if isinstance(response.get("httpLatencyMs"), (int, float)):
            latencies.append(float(response["httpLatencyMs"]))
        if isinstance(response.get("tokenUsage"), (int, float)):
            token_values.append(float(response["tokenUsage"]))
        rows.append(
            {
                "id": case["id"],
                "statusCode": response.get("statusCode"),
                "found": found,
                "failureReason": response.get("failureReason"),
                "httpLatencyMs": response.get("httpLatencyMs"),
                "apiLatencyMs": response.get("apiLatencyMs"),
                "tokenUsage": response.get("tokenUsage"),
            }
        )
    return {
        "case_count": len(rows),
        "status_failures": sum(1 for row in rows if row["statusCode"] != 200),
        "behavior_failures": behavior_failures,
        "latency_ms": {"p50": percentile(latencies, 0.50), "p95": percentile(latencies, 0.95), "max": round(max(latencies), 4) if latencies else None},
        "token_usage_avg": round(statistics.mean(token_values), 2) if token_values else None,
        "case_results": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--responses", type=Path, required=True)
    parser.add_argument("--diagnostics", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--tenant-id", default=DEFAULT_TENANT_ID)
    parser.add_argument("--knowledge-base-id", default=DEFAULT_KNOWLEDGE_BASE_ID)
    parser.add_argument("--db-service", default="db")
    parser.add_argument("--db-user", default="rag")
    parser.add_argument("--db-name", default="rag_knowledge_base")
    parser.add_argument("--db-timeout", type=float, default=30.0)
    parser.add_argument("--candidate-k", type=int, default=10)
    parser.add_argument("--context-k", type=int, default=5)
    parser.add_argument("--rrf-k", type=int, default=60)
    parser.add_argument("--keyword-weight", type=float, default=2.0)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        if args.candidate_k < args.context_k or args.context_k < 1 or args.keyword_weight <= 0:
            fail("require candidate-k >= context-k >= 1 and keyword-weight > 0")
        cases = load_jsonl(args.dataset)
        case_ids = {case["id"] for case in cases}
        manifest = load_manifest(args.manifest)
        tenant_id = uuid_text(args.tenant_id, "tenant-id")
        knowledge_base_id = uuid_text(args.knowledge_base_id, "knowledge-base-id")
        responses = load_responses(args.responses)
        diagnostics = load_diagnostics(args.diagnostics)
        if set(responses) != case_ids or set(diagnostics) != case_ids:
            fail("dataset, responses, and diagnostics IDs must match exactly")

        actor_ids = manifest["actors"]
        vector_results: list[dict[str, Any]] = []
        keyword_results: list[dict[str, Any]] = []
        fused_results: list[dict[str, Any]] = []
        db_timings: list[float] = []
        rank_timings: list[float] = []
        fusion_timings: list[float] = []
        actor_chunk_counts: dict[str, int] = {}
        for case in cases:
            case_id = case["id"]
            actor_id = case["acting_user"]["id"]
            user_id = uuid_text(actor_ids.get(actor_id), f"manifest.actors.{actor_id}")
            chunks, db_latency = fetch_acl_chunks(
                user_id,
                tenant_id,
                knowledge_base_id,
                args.db_service,
                args.db_user,
                args.db_name,
                args.db_timeout,
            )
            db_timings.append(db_latency)
            actor_chunk_counts[actor_id] = len(chunks)
            keyword_hits, rank_latency = rank_keyword(case["question"], chunks, args.candidate_k)
            rank_timings.append(rank_latency)
            vector_hits = diagnostics[case_id]["hits"]
            started = time.perf_counter_ns()
            fused_hits = fuse(vector_hits, keyword_hits, args.keyword_weight, args.rrf_k)
            fusion_timings.append(round((time.perf_counter_ns() - started) / 1_000_000, 4))
            vector_results.append(retrieval_case_score(case, vector_hits, args.context_k))
            keyword_results.append(retrieval_case_score(case, keyword_hits, args.context_k))
            fused_results.append(retrieval_case_score(case, fused_hits, args.context_k))

        benchmarks = {
            "vector_api_diagnostics": aggregate(vector_results, [float(item.get("diagnosticFetchLatencyMs") or 0) for item in diagnostics.values()], "vector_api_diagnostics", args.context_k),
            "keyword_acl_db_plus_rank": aggregate(keyword_results, [*db_timings, *rank_timings], "keyword_acl_db_plus_rank", args.context_k),
            "keyword_weighted_rrf": aggregate(fused_results, fusion_timings, "keyword_weighted_rrf", args.context_k),
        }
        vector_recall_at_1 = benchmarks["vector_api_diagnostics"]["recall_at_1"]
        fused_recall_at_1 = benchmarks["keyword_weighted_rrf"]["recall_at_1"]
        report = {
            "dataset": str(args.dataset),
            "diagnostics": str(args.diagnostics),
            "responses": str(args.responses),
            "fixture_boundary": "local disposable tenant; ACL SQL mirrors ChunkJdbcRepository.search; no backend retrieval code changed",
            "fusion": {"rrfK": args.rrf_k, "keywordWeight": args.keyword_weight, "candidateK": args.candidate_k, "contextK": args.context_k},
            "actor_visible_chunk_counts": actor_chunk_counts,
            "api_vector_summary": api_summary(cases, responses),
            "benchmarks": benchmarks,
            "decision": {
                "recommendation": "do_not_enable_runtime_hybrid_yet",
                "vector_recall_at_1": vector_recall_at_1,
                "fused_recall_at_1": fused_recall_at_1,
                "vector_recall_at_5": benchmarks["vector_api_diagnostics"]["recall_at_5"],
                "fused_recall_at_5": benchmarks["keyword_weighted_rrf"]["recall_at_5"],
                "reason": "This online capture measures ACL-filtered retrieval candidates, but the keyword side does not inject fused context into AskService. Keep the production vector path unchanged until a backend-owned A/B can compare generated answers, tokens, cost, and failure behavior under the same request.",
            },
        }
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"online retrieval A/B error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
