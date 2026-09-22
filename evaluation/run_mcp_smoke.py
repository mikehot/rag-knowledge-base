#!/usr/bin/env python3
"""Run a bounded, credential-backed smoke check for the read-only MCP adapter.

The script intentionally uses only the Python standard library. Credentials are
read from a file outside the repository and raw MCP responses are kept in
memory only. The output is an aggregate report: it never writes bearer tokens,
request IDs, answers, document content, or full tool results.

This validates the repository's declared adapter boundary. It is not an MCP
SDK certification or a claim of complete transport/auth conformance.
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


PROTOCOL_VERSION = "2026-07-28"
EXPECTED_TOOLS = {"search_knowledge", "list_documents", "get_document_status"}


def load_credentials(path: Path) -> dict[str, str]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("credentials file must be a JSON object")
    if value.get("username") and value.get("password"):
        return {"username": str(value["username"]), "password": str(value["password"])}
    raise ValueError("smoke credentials require username and password")


def post_json(
    base_url: str,
    path: str,
    payload: dict[str, Any],
    headers: dict[str, str] | None = None,
    timeout: float = 30.0,
) -> tuple[int, dict[str, Any], float]:
    request_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=body,
        headers=request_headers,
        method="POST",
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read().decode("utf-8", errors="replace")
    elapsed_ms = round((time.monotonic() - started) * 1000, 2)
    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError:
        decoded = {}
    if not isinstance(decoded, dict):
        decoded = {}
    return status, decoded, elapsed_ms


def login(base_url: str, credentials: dict[str, str], timeout: float) -> str:
    status, envelope, _ = post_json(base_url, "/api/auth/login", credentials, timeout=timeout)
    data = envelope.get("data")
    if status != 200 or envelope.get("code") != 0 or not isinstance(data, dict) or not data.get("token"):
        raise RuntimeError(f"login failed (HTTP {status}, code {envelope.get('code')})")
    return str(data["token"])


def mcp_headers(token: str | None, method: str, name: str | None = None) -> dict[str, str]:
    headers = {
        "MCP-Protocol-Version": PROTOCOL_VERSION,
        "Mcp-Method": method,
    }
    if name:
        headers["Mcp-Name"] = name
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def mcp_call(
    base_url: str,
    request_id: str,
    method: str,
    params: dict[str, Any] | None,
    token: str | None,
    name_header: str | None = None,
    method_header: str | None = None,
    timeout: float = 30.0,
) -> tuple[int, dict[str, Any], float]:
    payload: dict[str, Any] = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        payload["params"] = params
    headers = mcp_headers(token, method_header or method, name_header)
    return post_json(base_url, "/mcp", payload, headers=headers, timeout=timeout)


def check(checks: list[dict[str, Any]], name: str, passed: bool, observed: Any = None) -> None:
    item: dict[str, Any] = {"name": name, "passed": passed}
    if observed is not None:
        item["observed"] = observed
    checks.append(item)


def require_jsonrpc(response: dict[str, Any], checks: list[dict[str, Any]], label: str) -> None:
    check(checks, f"{label}: JSON-RPC 2.0", response.get("jsonrpc") == "2.0", response.get("jsonrpc"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--credentials", type=Path, required=True, help="External admin credential JSON")
    parser.add_argument("--output", type=Path, required=True, help="Aggregate JSON report path")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    checks: list[dict[str, Any]] = []
    timings: dict[str, float] = {}
    try:
        credentials = load_credentials(args.credentials)
        token = login(args.base_url, credentials, args.timeout)

        status, response, timings["discoverMs"] = mcp_call(
            args.base_url, "discover-1", "server/discover", None, token, timeout=args.timeout
        )
        check(checks, "discover: HTTP 200", status == 200, status)
        require_jsonrpc(response, checks, "discover")
        discover_result = response.get("result") if isinstance(response.get("result"), dict) else {}
        check(
            checks,
            "discover: protocol version",
            discover_result.get("protocolVersion") == PROTOCOL_VERSION,
            discover_result.get("protocolVersion"),
        )

        status, response, timings["listMs"] = mcp_call(
            args.base_url, "list-1", "tools/list", {}, token, timeout=args.timeout
        )
        check(checks, "tools/list: HTTP 200", status == 200, status)
        require_jsonrpc(response, checks, "tools/list")
        list_result = response.get("result") if isinstance(response.get("result"), dict) else {}
        tools = list_result.get("tools") if isinstance(list_result.get("tools"), list) else []
        tool_names = sorted(
            tool.get("name") for tool in tools if isinstance(tool, dict) and isinstance(tool.get("name"), str)
        )
        check(checks, "tools/list: exact read-only allowlist", set(tool_names) == EXPECTED_TOOLS, tool_names)
        check(checks, "tools/list: exactly three tools", len(tools) == 3, len(tools))
        check(checks, "tools/list: private no-cache hint", list_result.get("ttlMs") == 0 and list_result.get("cacheScope") == "private", {
            "ttlMs": list_result.get("ttlMs"),
            "cacheScope": list_result.get("cacheScope"),
        })

        status, response, timings["callMs"] = mcp_call(
            args.base_url,
            "call-1",
            "tools/call",
            {"name": "list_documents", "arguments": {}},
            token,
            name_header="list_documents",
            timeout=args.timeout,
        )
        check(checks, "tools/call list_documents: HTTP 200", status == 200, status)
        require_jsonrpc(response, checks, "tools/call list_documents")
        call_result = response.get("result") if isinstance(response.get("result"), dict) else {}
        check(checks, "tools/call list_documents: success", call_result.get("isError") is False, call_result.get("isError"))

        status, response, timings["identityOverrideMs"] = mcp_call(
            args.base_url,
            "call-2",
            "tools/call",
            {"name": "list_documents", "arguments": {"tenantId": "00000000-0000-0000-0000-000000000000"}},
            token,
            name_header="list_documents",
            timeout=args.timeout,
        )
        check(checks, "identity override: HTTP 200 JSON-RPC error result", status == 200, status)
        override_result = response.get("result") if isinstance(response.get("result"), dict) else {}
        check(checks, "identity override: rejected by tool boundary", override_result.get("isError") is True, override_result.get("isError"))

        status, response, timings["headerMismatchMs"] = mcp_call(
            args.base_url,
            "mismatch-1",
            "tools/list",
            {},
            token,
            name_header="list_documents",
            method_header="tools/call",
            timeout=args.timeout,
        )
        check(checks, "header mismatch: HTTP 200", status == 200, status)
        mismatch_error = response.get("error") if isinstance(response.get("error"), dict) else {}
        check(checks, "header mismatch: JSON-RPC -32600", mismatch_error.get("code") == -32600, mismatch_error.get("code"))

        status, response, timings["unauthenticatedMs"] = mcp_call(
            args.base_url, "unauth-1", "server/discover", None, None, timeout=args.timeout
        )
        check(checks, "unauthenticated: HTTP 401", status == 401, status)

        passed = all(item["passed"] for item in checks)
        report = {
            "schemaVersion": "mcp-readonly-smoke-v1",
            "baseUrl": args.base_url.rstrip("/"),
            "protocolVersion": PROTOCOL_VERSION,
            "passed": passed,
            "checks": checks,
            "timingsMs": timings,
            "boundary": {
                "tools": sorted(EXPECTED_TOOLS),
                "writesEnabled": False,
                "fullMcpConformanceClaim": False,
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"mcp smoke {'PASS' if passed else 'FAIL'}: {sum(item['passed'] for item in checks)}/{len(checks)} checks")
        print(f"aggregate report: {args.output}")
        return 0 if passed else 1
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as exc:
        print(f"MCP smoke error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
