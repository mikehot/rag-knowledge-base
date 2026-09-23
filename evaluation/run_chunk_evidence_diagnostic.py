#!/usr/bin/env python3
"""Score exact expected-answer-point evidence across ACL-visible vector chunks.

This is an evaluation-only diagnostic. It joins the protected metadata-only
vector candidate capture with locally fetched ACL-visible chunk text in memory,
then writes only chunk metadata, expected-point IDs, ranks, and coverage counts.
Chunk text and expected lexical match terms are never serialized.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import unicodedata
from pathlib import Path
from typing import Any

try:
    from run_online_retrieval_ab import fetch_acl_chunks, load_jsonl, load_manifest, uuid_text
except ImportError as exc:  # pragma: no cover - protects direct module misuse
    raise RuntimeError("run from the repository root as evaluation/run_chunk_evidence_diagnostic.py") from exc


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001"
DEFAULT_KNOWLEDGE_BASE_ID = "00000000-0000-0000-0000-000000000101"
DEFAULT_CONTEXT_BUDGETS = (5, 8, 10)


def fail(message: str) -> None:
    raise ValueError(message)


def compact_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return "".join(
        char for char in normalized
        if not char.isspace() and not unicodedata.category(char).startswith("P")
    )


def validate_vector_diagnostics(path: Path) -> dict[str, dict[str, Any]]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"invalid vector diagnostic report: {exc}")
    results = report.get("results") if isinstance(report, dict) else None
    if not isinstance(results, list) or not results:
        fail("vector diagnostic report requires non-empty results")
    indexed: dict[str, dict[str, Any]] = {}
    for row in results:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str):
            fail("vector diagnostic rows require string ids")
        case_id = row["id"]
        if case_id in indexed:
            fail(f"duplicate vector diagnostic case: {case_id}")
        hits = row.get("hits")
        if not isinstance(hits, list):
            fail(f"{case_id}.hits must be a list")
        by_chunk: dict[str, dict[str, Any]] = {}
        for expected_rank, hit in enumerate(hits, 1):
            if not isinstance(hit, dict) or hit.get("rank") != expected_rank:
                fail(f"{case_id}.hits ranks must be contiguous from 1")
            chunk_id = hit.get("chunkId")
            if not isinstance(chunk_id, str) or not chunk_id.strip():
                fail(f"{case_id}.hits[{expected_rank}].chunkId must be non-empty")
            if chunk_id in by_chunk:
                fail(f"{case_id} contains duplicate chunkId {chunk_id}")
            by_chunk[chunk_id] = {
                "rank": expected_rank,
                "chunkId": chunk_id,
                "filename": hit.get("filename"),
                "locator": hit.get("locator"),
                "similarity": hit.get("similarity"),
            }
        indexed[case_id] = {"hits": by_chunk, "aclLeakage": row.get("aclLeakage") is True}
    return indexed


def matched_point_ids(case: dict[str, Any], content: str) -> set[str]:
    haystack = compact_text(content)
    matched: set[str] = set()
    for point in case.get("expected_answer_points", []):
        point_id = point.get("id")
        terms = point.get("match_any", [])
        if isinstance(point_id, str) and isinstance(terms, list):
            if any((needle := compact_text(term)) and needle in haystack for term in terms if isinstance(term, str)):
                matched.add(point_id)
    return matched


def score_context_budgets(
    case: dict[str, Any],
    ranked_hits: list[dict[str, Any]],
    chunk_points: dict[str, set[str]],
    off_source_points: dict[str, set[str]],
    budgets: tuple[int, ...],
) -> dict[str, Any]:
    expected = {point["id"] for point in case.get("expected_answer_points", []) if isinstance(point.get("id"), str)}
    answer_points = {hit["chunkId"]: chunk_points.get(hit["chunkId"], set()) for hit in ranked_hits}
    point_ranks = {
        point_id: [hit["rank"] for hit in ranked_hits if point_id in answer_points[hit["chunkId"]]]
        for point_id in sorted(expected)
    }
    budget_results: list[dict[str, Any]] = []
    for budget in budgets:
        selected = ranked_hits[:budget]
        covered = set().union(*(answer_points[hit["chunkId"]] for hit in selected)) if selected else set()
        off_source = set().union(*(off_source_points.get(hit["chunkId"], set()) for hit in selected)) if selected else set()
        evidence_chunk_ids = {
            hit["chunkId"] for hit in selected
            if answer_points[hit["chunkId"]] & expected
        }
        selected_docs = {hit.get("filename") for hit in selected if isinstance(hit.get("filename"), str)}
        evidence_docs = {
            hit.get("filename") for hit in selected
            if answer_points[hit["chunkId"]] & expected and isinstance(hit.get("filename"), str)
        }
        budget_results.append({
            "contextK": budget,
            "selectedChunkCount": len(selected),
            "coveredPointIds": sorted(covered & expected),
            "pointCoverage": round(len(covered & expected) / len(expected), 4) if expected else None,
            "allExpectedPointsCovered": bool(expected) and expected <= covered,
            "offSourceMatchedPointIds": sorted(off_source & expected),
            "evidenceChunkIds": sorted(evidence_chunk_ids),
            "uniqueDocumentCount": len(selected_docs),
            "nonEvidenceChunkCount": sum(1 for hit in selected if not (answer_points[hit["chunkId"]] & expected)),
            "nonEvidenceDocumentCount": len(selected_docs - evidence_docs),
        })
    return {
        "expectedPointCount": len(expected),
        "pointRanks": point_ranks,
        "contextBudgets": budget_results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, action="append", required=True, help="Versioned dataset; repeat for multiple datasets")
    parser.add_argument("--case-id", action="append", dest="case_ids", help="Limit analysis to selected IDs; may be repeated")
    parser.add_argument("--diagnostics", type=Path, action="append", required=True, help="Metadata-only output; repeat for multiple captures")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--tenant-id", default=DEFAULT_TENANT_ID)
    parser.add_argument("--knowledge-base-id", default=DEFAULT_KNOWLEDGE_BASE_ID)
    parser.add_argument("--db-service", default="db")
    parser.add_argument("--db-user", default="rag")
    parser.add_argument("--db-name", default="rag_knowledge_base")
    parser.add_argument("--db-timeout", type=float, default=30.0)
    parser.add_argument("--context-budgets", type=int, nargs="+", default=list(DEFAULT_CONTEXT_BUDGETS))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if any(value < 1 for value in args.context_budgets) or len(set(args.context_budgets)) != len(args.context_budgets):
        parser.error("context budgets must be unique positive integers")

    try:
        cases = [case for dataset in args.dataset for case in load_jsonl(dataset)]
        ids = [case.get("id") for case in cases]
        if not cases or any(not isinstance(case_id, str) for case_id in ids) or len(ids) != len(set(ids)):
            fail("datasets must contain unique string case IDs")
        selected_ids = set(args.case_ids or ids)
        case_by_id = {case["id"]: case for case in cases}
        unknown = selected_ids - case_by_id.keys()
        if unknown:
            fail(f"unknown case ids: {sorted(unknown)}")
        cases = [case for case in cases if case["id"] in selected_ids]
        diagnostics: dict[str, dict[str, Any]] = {}
        for diagnostics_path in args.diagnostics:
            loaded = validate_vector_diagnostics(diagnostics_path)
            duplicates = diagnostics.keys() & loaded.keys()
            if duplicates:
                fail(f"duplicate vector diagnostics across files: {sorted(duplicates)}")
            diagnostics.update(loaded)
        if selected_ids - diagnostics.keys():
            fail(f"missing vector diagnostics for cases: {sorted(selected_ids - diagnostics.keys())}")
        manifest = load_manifest(args.manifest)
        tenant_id = uuid_text(args.tenant_id, "tenant-id")
        knowledge_base_id = uuid_text(args.knowledge_base_id, "knowledge-base-id")
        budget_max = max(args.context_budgets)
        case_results: list[dict[str, Any]] = []

        for case in cases:
            case_id = case["id"]
            diag = diagnostics[case_id]
            actor_id = case["acting_user"]["id"]
            user_id = uuid_text(manifest["actors"].get(actor_id), f"manifest.actors.{actor_id}")
            chunks, db_latency_ms = fetch_acl_chunks(
                user_id,
                tenant_id,
                knowledge_base_id,
                args.db_service,
                args.db_user,
                args.db_name,
                args.db_timeout,
            )
            chunk_by_id = {str(chunk.get("chunkId")): chunk for chunk in chunks}
            candidate_hits = sorted(diag["hits"].values(), key=lambda item: item["rank"])
            ranked_hits = candidate_hits[:budget_max]
            missing_chunks = [hit["chunkId"] for hit in ranked_hits if hit["chunkId"] not in chunk_by_id]
            if missing_chunks:
                fail(f"{case_id} vector candidates are not present in this actor's ACL-visible chunks")
            point_by_chunk = {
                hit["chunkId"]: (
                    matched_point_ids(case, str(chunk_by_id[hit["chunkId"]].get("content", "")))
                    if hit.get("filename") in set(case.get("expected_source_documents", []))
                    else set()
                )
                for hit in ranked_hits
            }
            off_source_point_by_chunk = {
                hit["chunkId"]: (
                    set() if hit.get("filename") in set(case.get("expected_source_documents", []))
                    else matched_point_ids(case, str(chunk_by_id[hit["chunkId"]].get("content", "")))
                )
                for hit in ranked_hits
            }
            rank_to_points = {
                hit["chunkId"]: sorted(point_by_chunk[hit["chunkId"]]) for hit in ranked_hits
            }
            rank_to_off_source_points = {
                hit["chunkId"]: sorted(off_source_point_by_chunk[hit["chunkId"]]) for hit in ranked_hits
            }
            score = score_context_budgets(
                case, ranked_hits, point_by_chunk, off_source_point_by_chunk, tuple(args.context_budgets)
            )
            case_results.append({
                "id": case_id,
                "actorId": actor_id,
                "candidateCount": len(candidate_hits),
                "inspectedContextK": budget_max,
                "dbFetchLatencyMs": db_latency_ms,
                "aclLeakage": diag["aclLeakage"],
                "rankedChunks": [
                    {
                        "rank": hit["rank"],
                        "chunkId": hit["chunkId"],
                        "filename": hit.get("filename"),
                        "locator": hit.get("locator"),
                        "matchedPointIds": rank_to_points[hit["chunkId"]],
                        "offSourceMatchedPointIds": rank_to_off_source_points[hit["chunkId"]],
                    }
                    for hit in ranked_hits
                ],
                **score,
            })

        all_answerable = [case for case in cases if case.get("expected_behavior") == "ANSWER"]
        aggregate: dict[str, Any] = {}
        for budget in args.context_budgets:
            rows = [
                next(item for item in row["contextBudgets"] if item["contextK"] == budget)
                for case, row in zip(cases, case_results)
                if case.get("expected_behavior") == "ANSWER"
            ]
            aggregate[str(budget)] = {
                "answerableCaseCount": len(rows),
                "meanExactEvidencePointCoverage": round(sum(row["pointCoverage"] or 0 for row in rows) / len(rows), 4) if rows else None,
                "casesWithAllExpectedPoints": sum(row["allExpectedPointsCovered"] for row in rows),
                "casesWithOffSourceLexicalMatches": sum(bool(row["offSourceMatchedPointIds"]) for row in rows),
                "meanNonEvidenceChunkCount": round(sum(row["nonEvidenceChunkCount"] for row in rows) / len(rows), 4) if rows else None,
                "meanNonEvidenceDocumentCount": round(sum(row["nonEvidenceDocumentCount"] for row in rows) / len(rows), 4) if rows else None,
                "aclLeakageCount": sum(row["aclLeakage"] for row in case_results),
            }
        report = {
            "schemaVersion": "chunk-evidence-diagnostic-v1",
            "productionPathChanged": False,
            "scoringMethod": "Exact normalized match_any term presence in ACL-visible vector candidate chunks; only chunks from expected_source_documents count as evidence; no model calls or answer text used.",
            "contentPersisted": False,
            "diagnosticCaseCount": len(cases),
            "answerableCaseCount": len(all_answerable),
            "contextBudgets": list(args.context_budgets),
            "aggregateByContextK": aggregate,
            "caseResults": case_results,
            "caveat": "Lexical evidence-point presence in expected source files is a deterministic diagnostic, not a semantic relevance or answer-quality judgment. Off-source lexical matches are reported separately and do not count as evidence. Larger context budgets do not imply runtime adoption.",
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote metadata-only chunk evidence report for {len(cases)} cases to {args.output}")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"chunk evidence diagnostic error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
