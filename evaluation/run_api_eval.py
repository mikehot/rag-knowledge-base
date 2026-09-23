#!/usr/bin/env python3
"""Collect real /api/ask responses for a versioned evaluation dataset.

Credentials are supplied by an external JSON file and are never written to the
repository. The optional retrieval mode is sent as an experiment header and is
also recorded in capture metadata so paired A/B files remain distinguishable.
The output is intentionally flattened so run_eval.py can score it without
knowing the API transport details.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "golden_v1.jsonl"


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{line_number} invalid JSON: {exc.msg}") from exc
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{line_number} must contain an object")
        rows.append(value)
    return rows


def load_credentials(path: Path) -> dict[str, dict[str, str]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("credentials file must be a JSON object keyed by acting_user.id")
    result: dict[str, dict[str, str]] = {}
    for actor_id, credentials in value.items():
        if not isinstance(credentials, dict) or not credentials.get("username") or not credentials.get("password"):
            raise ValueError(f"credentials for {actor_id} require username and password")
        result[actor_id] = {
            "username": str(credentials["username"]),
            "password": str(credentials["password"]),
        }
    return result


def request_json(
    base_url: str,
    path: str,
    payload: dict[str, Any],
    token: str | None,
    timeout: float,
    retrieval_mode: str | None = None,
) -> tuple[int, dict[str, Any], float]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if retrieval_mode:
        headers["X-RAG-Retrieval-Mode"] = retrieval_mode
    request = urllib.request.Request(f"{base_url.rstrip('/')}{path}", data=body, headers=headers, method="POST")
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read().decode("utf-8", errors="replace")
    elapsed_ms = round((time.monotonic() - started) * 1000, 2)
    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError:
        decoded = {"code": status, "message": raw[:500], "data": None}
    if not isinstance(decoded, dict):
        decoded = {"code": status, "message": "non-object API response", "data": None}
    return status, decoded, elapsed_ms


def login(base_url: str, credentials: dict[str, str], timeout: float) -> str:
    status, envelope, _ = request_json(base_url, "/api/auth/login", credentials, None, timeout)
    data = envelope.get("data")
    if status != 200 or envelope.get("code") != 0 or not isinstance(data, dict) or not data.get("token"):
        raise RuntimeError(f"login failed for {credentials['username']} (HTTP {status}, code {envelope.get('code')})")
    return str(data["token"])


def flatten_case(
    case: dict[str, Any],
    status: int,
    envelope: dict[str, Any],
    wall_latency_ms: float,
    actor_id: str,
    retrieval_mode: str,
) -> dict[str, Any]:
    data = envelope.get("data")
    if not isinstance(data, dict):
        data = {}
    return {
        "id": case["id"],
        "actorId": actor_id,
        "retrievalMode": retrieval_mode.upper().replace("-", "_"),
        "answer": data.get("answer", "") if isinstance(data.get("answer", ""), str) else "",
        "found": data.get("found", False) is True,
        "grounded": data.get("grounded", False) is True,
        "sources": data.get("sources", []) if isinstance(data.get("sources", []), list) else [],
        "failureReason": data.get("failureReason"),
        "statusCode": status,
        "apiCode": envelope.get("code"),
        "apiMessage": envelope.get("message"),
        "requestId": data.get("requestId"),
        "apiLatencyMs": data.get("latencyMs"),
        "httpLatencyMs": wall_latency_ms,
        "tokenUsage": data.get("tokenUsage"),
        "timings": data.get("timings"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--credentials", type=Path, required=True, help="External JSON map keyed by acting_user.id")
    parser.add_argument("--output", type=Path, required=True, help="JSONL response capture path")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument(
        "--retrieval-mode",
        choices=("vector", "vector-diversity", "keyword-rrf", "vector-adjacent"),
        default="vector",
    )
    args = parser.parse_args()
    try:
        cases = load_jsonl(args.dataset)
        credentials = load_credentials(args.credentials)
        actor_ids = {case["acting_user"]["id"] for case in cases}
        missing = sorted(actor_ids - credentials.keys())
        if missing:
            raise ValueError(f"credentials are missing acting users: {missing}")
        tokens: dict[str, str] = {}
        rows: list[dict[str, Any]] = []
        for case in cases:
            actor_id = case["acting_user"]["id"]
            if actor_id not in tokens:
                tokens[actor_id] = login(args.base_url, credentials[actor_id], args.timeout)
            status, envelope, elapsed_ms = request_json(
                args.base_url,
                "/api/ask",
                {"question": case["question"]},
                tokens[actor_id],
                args.timeout,
                args.retrieval_mode,
            )
            rows.append(flatten_case(case, status, envelope, elapsed_ms, actor_id, args.retrieval_mode))
        args.output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
        print(f"captured {len(rows)} responses to {args.output}")
        return 0
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as exc:
        print(f"API evaluation error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
