#!/usr/bin/env python3
"""Inspect ACL-filtered vector ranks beyond the production Top-K boundary.

This is an evaluation-only diagnostic. It embeds sanitized dataset questions,
then repeats the backend's tenant/knowledge-base/ACL predicates in PostgreSQL
with a larger LIMIT. The output contains metadata and similarities only; it
never writes chunk content or changes the backend retrieval path.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

try:
    from run_online_retrieval_ab import load_manifest, load_jsonl, uuid_text
except ImportError as exc:  # pragma: no cover - protects direct module misuse
    raise RuntimeError("run from the repository root as evaluation/run_vector_candidate_diagnostic.py") from exc


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "answer_quality_v1.jsonl"
DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001"
DEFAULT_EMBEDDING_MODEL = "text-embedding-nomic-embed-text-v1.5"


def fail(message: str) -> None:
    raise ValueError(message)


def embedding_vector(base_url: str, model: str, text: str, timeout: float) -> tuple[list[float], float]:
    body = json.dumps(
        {"model": model, "input": [text]},
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    api_key = os.environ.get("AI_EMBEDDING_API_KEY", os.environ.get("AI_API_KEY", "")).strip()
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/embeddings",
        data=body,
        headers=headers,
        method="POST",
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"embedding provider returned HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise RuntimeError(f"embedding provider request failed: {type(exc).__name__}") from exc
    elapsed_ms = round((time.monotonic() - started) * 1000, 2)
    if status < 200 or status >= 300:
        raise RuntimeError(f"embedding provider returned HTTP {status}")
    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("embedding provider returned invalid JSON") from exc
    data = decoded.get("data") if isinstance(decoded, dict) else None
    if not isinstance(data, list) or not data or not isinstance(data[0], dict):
        raise RuntimeError("embedding provider returned no vector")
    values = data[0].get("embedding")
    if not isinstance(values, list) or not values or not all(isinstance(value, (int, float)) for value in values):
        raise RuntimeError("embedding provider returned an invalid vector")
    vector = [float(value) for value in values]
    if not all(math.isfinite(value) for value in vector):
        raise RuntimeError("embedding provider returned a non-finite vector")
    return vector, elapsed_ms


def vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(format(value, ".9g") for value in vector) + "]"


def fetch_vector_hits(
    user_id: str,
    tenant_id: str,
    knowledge_base_id: str,
    vector: list[float],
    candidate_k: int,
    db_service: str,
    db_user: str,
    db_name: str,
    timeout: float,
) -> tuple[list[dict[str, Any]], float]:
    query_vector = vector_literal(vector)
    sql = f"""
SELECT COALESCE(json_agg(json_build_object(
    'chunkId', ranked.chunk_id,
    'documentId', ranked.document_id,
    'filename', ranked.filename,
    'locator', ranked.locator,
    'similarity', ranked.similarity
) ORDER BY ranked.distance), '[]'::json)::text
FROM (
    SELECT c.id AS chunk_id,
           d.id AS document_id,
           d.filename,
           c.locator,
           (1 - (c.embedding <=> '{query_vector}'::vector)) AS similarity,
           c.embedding <=> '{query_vector}'::vector AS distance
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
      )
    ORDER BY distance, d.filename, c.seq, c.id
    LIMIT {candidate_k}
) ranked;
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
        fail(f"vector candidate query failed: {completed.stderr[-500:]}")
    try:
        rows = json.loads(completed.stdout.strip() or "[]")
    except json.JSONDecodeError as exc:
        fail(f"vector candidate query returned invalid JSON: {exc.msg}")
    if not isinstance(rows, list):
        fail("vector candidate query must return a JSON array")
    for rank, row in enumerate(rows, 1):
        if not isinstance(row, dict):
            fail("vector candidate metadata must contain objects")
        row["rank"] = rank
    return rows, elapsed_ms


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--case-id", action="append", dest="case_ids")
    parser.add_argument("--base-url", default="http://localhost:1234/v1")
    parser.add_argument("--embedding-model", default=DEFAULT_EMBEDDING_MODEL)
    parser.add_argument("--tenant-id", default=DEFAULT_TENANT_ID)
    parser.add_argument("--knowledge-base-id")
    parser.add_argument("--candidate-k", type=int, default=50)
    parser.add_argument("--db-service", default="db")
    parser.add_argument("--db-user", default="rag")
    parser.add_argument("--db-name", default="rag_knowledge_base")
    parser.add_argument("--embedding-timeout", type=float, default=60.0)
    parser.add_argument("--db-timeout", type=float, default=30.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.candidate_k < 5:
        parser.error("candidate-k must be at least the production Top-K=5")

    try:
        all_cases = load_jsonl(args.dataset)
        default_case_ids = ["QUALITY-002"] if args.dataset.resolve() == DEFAULT_DATASET.resolve() else [
            str(case.get("id")) for case in all_cases
        ]
        selected = set(args.case_ids or default_case_ids)
        cases = [case for case in all_cases if case.get("id") in selected]
        if {case.get("id") for case in cases} != selected:
            fail(f"unknown case ids: {sorted(selected - {case.get('id') for case in cases})}")
        manifest = load_manifest(args.manifest)
        tenant_id = uuid_text(args.tenant_id, "tenant-id")
        knowledge_base_id = uuid_text(
            args.knowledge_base_id or manifest.get("knowledgeBaseId"),
            "knowledge-base-id",
        )
        actor_manifest = manifest["actors"]
        results: list[dict[str, Any]] = []
        for case in cases:
            actor_id = case["acting_user"]["id"]
            user_id = uuid_text(actor_manifest.get(actor_id), f"manifest.actors.{actor_id}")
            vector, embedding_ms = embedding_vector(
                args.base_url,
                args.embedding_model,
                case["question"],
                args.embedding_timeout,
            )
            hits, db_ms = fetch_vector_hits(
                user_id,
                tenant_id,
                knowledge_base_id,
                vector,
                args.candidate_k,
                args.db_service,
                args.db_user,
                args.db_name,
                args.db_timeout,
            )
            expected = set(case.get("expected_source_documents", []))
            forbidden = set(case.get("forbidden_documents", []))
            target_ranks = {
                filename: next((hit["rank"] for hit in hits if hit["filename"] == filename), None)
                for filename in sorted(expected)
            }
            leakage = sorted({hit["filename"] for hit in hits} & forbidden)
            results.append(
                {
                    "id": case["id"],
                    "actorId": actor_id,
                    "candidateK": args.candidate_k,
                    "candidateCount": len(hits),
                    "embeddingModel": args.embedding_model,
                    "embeddingMs": embedding_ms,
                    "dbQueryMs": db_ms,
                    "expectedDocuments": sorted(expected),
                    "targetRanks": target_ranks,
                    "aclLeakage": bool(leakage),
                    "aclLeakedDocuments": leakage,
                    "hits": hits,
                }
            )
        report = {
            "schemaVersion": "vector-candidate-diagnostic-v1",
            "dataset": str(args.dataset),
            "manifest": str(args.manifest),
            "tenantId": tenant_id,
            "knowledgeBaseId": knowledge_base_id,
            "productionContextK": 5,
            "candidateK": args.candidate_k,
            "results": results,
            "decision": {
                "recommendation": "diagnostic_only_keep_vector_top_k_5",
                "reason": "A larger candidate pool is evidence about rank and similarity only; it must not change AskService context or ACL behavior.",
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {len(results)} vector candidate diagnostics to {args.output}")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"vector candidate diagnostic error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
