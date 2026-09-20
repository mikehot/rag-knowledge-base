#!/usr/bin/env python3
"""Collect protected retrieval diagnostics for an existing ask response capture.

The admin/auditor credential and the response capture stay outside the repository.
This script does not send new questions; it only reads the protected diagnostic
endpoint by requestId and writes a flattened JSONL adapter for run_retrieval_eval.py.
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


def load_admin_credentials(path: Path, actor_id: str) -> dict[str, str]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("admin credentials must be a JSON object")
    if value.get("username") and value.get("password"):
        return {"username": str(value["username"]), "password": str(value["password"])}
    selected = value.get(actor_id)
    if not isinstance(selected, dict) or not selected.get("username") or not selected.get("password"):
        raise ValueError(f"admin credentials require username/password or actor key {actor_id}")
    return {"username": str(selected["username"]), "password": str(selected["password"])}


def request_json(
    base_url: str,
    path: str,
    token: str | None,
    timeout: float,
) -> tuple[int, dict[str, Any], float]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        headers=headers,
        method="GET",
    )
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
    body = json.dumps(credentials, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/auth/login",
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            envelope = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        status = exc.code
        envelope = json.loads(exc.read().decode("utf-8", errors="replace"))
    if not isinstance(envelope, dict):
        raise RuntimeError(f"admin login returned a non-object response (HTTP {status})")
    data = envelope.get("data")
    if status != 200 or envelope.get("code") != 0 or not isinstance(data, dict) or not data.get("token"):
        raise RuntimeError(f"admin login failed (HTTP {status}, code {envelope.get('code')})")
    return str(data["token"])


def flatten(row: dict[str, Any], status: int, envelope: dict[str, Any], latency_ms: float) -> dict[str, Any]:
    data = envelope.get("data")
    return {
        "id": row.get("id"),
        "requestId": row.get("requestId"),
        "statusCode": status,
        "apiCode": envelope.get("code"),
        "apiMessage": envelope.get("message"),
        "httpLatencyMs": latency_ms,
        "diagnostics": data if isinstance(data, dict) else None,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--responses", type=Path, required=True, help="JSONL output from run_api_eval.py")
    parser.add_argument("--admin-credentials", type=Path, required=True)
    parser.add_argument("--admin-actor-id", default="demo.admin")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    try:
        rows = load_jsonl(args.responses)
        credentials = load_admin_credentials(args.admin_credentials, args.admin_actor_id)
        token = login(args.base_url, credentials, args.timeout)
        output_rows: list[dict[str, Any]] = []
        for row in rows:
            request_id = row.get("requestId")
            if not isinstance(request_id, str) or not request_id:
                output_rows.append(flatten(row, 422, {"code": 422, "message": "missing requestId", "data": None}, 0))
                continue
            status, envelope, latency_ms = request_json(
                args.base_url,
                f"/api/admin/retrieval-diagnostics/{request_id}",
                token,
                args.timeout,
            )
            output_rows.append(flatten(row, status, envelope, latency_ms))
        args.output.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in output_rows) + "\n",
            encoding="utf-8",
        )
        print(f"captured {len(output_rows)} retrieval diagnostics to {args.output}")
        return 0
    except (OSError, ValueError, RuntimeError, urllib.error.URLError, json.JSONDecodeError) as exc:
        print(f"retrieval diagnostics error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
