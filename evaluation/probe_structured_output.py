#!/usr/bin/env python3
"""Probe OpenAI-compatible structured output without sending project data.

The probe deliberately sends only synthetic questions and never writes model
content to its report. It records contract validity, finish reason, token
usage, response size, and latency so a local model can be evaluated before it
is selected for the RAG service.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


PROBES = (
    (
        "single_point",
        "只输出一个 JSON 对象。回答：安装客户端后如何启动？没有资料时请返回 found=false。",
    ),
    (
        "multi_point",
        "只输出一个 JSON 对象。回答：请同时说明安装客户端后如何启动，以及安装后无法启动时应检查什么？没有资料时请返回 found=false。",
    ),
)


def structured_schema() -> dict[str, Any]:
    return {
        "type": "json_schema",
        "json_schema": {
            "name": "rag_answer",
            "strict": True,
            "schema": {
                "type": "object",
                "properties": {
                    "answer": {"type": "string"},
                    "found": {"type": "boolean"},
                    "grounded": {"type": "boolean"},
                    "sourceIndexes": {
                        "type": "array",
                        "items": {"type": "integer", "minimum": 1},
                    },
                },
                "required": ["answer", "found", "grounded", "sourceIndexes"],
                "additionalProperties": False,
            },
        },
    }


def validate_contract(value: Any) -> tuple[bool, str | None]:
    if not isinstance(value, dict):
        return False, "root_not_object"
    expected = {"answer", "found", "grounded", "sourceIndexes"}
    if set(value) != expected:
        return False, "keys_mismatch"
    if not isinstance(value["answer"], str):
        return False, "answer_not_string"
    if type(value["found"]) is not bool:
        return False, "found_not_boolean"
    if type(value["grounded"]) is not bool:
        return False, "grounded_not_boolean"
    source_indexes = value["sourceIndexes"]
    if not isinstance(source_indexes, list):
        return False, "source_indexes_not_array"
    if any(type(item) is not int or item < 1 for item in source_indexes):
        return False, "source_indexes_invalid"
    return True, None


def parse_response(raw: str, status: int) -> dict[str, Any]:
    try:
        root = json.loads(raw)
    except json.JSONDecodeError:
        return {
            "statusCode": status,
            "responseJson": False,
            "responseErrorType": "invalid_json",
            "contentNonEmpty": False,
        }
    if not isinstance(root, dict):
        return {
            "statusCode": status,
            "responseJson": False,
            "responseErrorType": "root_not_object",
            "contentNonEmpty": False,
        }
    if isinstance(root.get("error"), dict):
        error = root["error"]
        return {
            "statusCode": status,
            "responseJson": True,
            "responseErrorType": str(error.get("type") or error.get("code") or "provider_error"),
            "contentNonEmpty": False,
        }
    choices = root.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        return {
            "statusCode": status,
            "responseJson": True,
            "responseErrorType": "choices_missing",
            "contentNonEmpty": False,
        }
    choice = choices[0]
    message = choice.get("message")
    content = message.get("content") if isinstance(message, dict) else None
    content = content if isinstance(content, str) else ""
    result: dict[str, Any] = {
        "statusCode": status,
        "responseJson": True,
        "contentNonEmpty": bool(content.strip()),
        "contentLength": len(content),
        "messageKeys": sorted(message) if isinstance(message, dict) else [],
        "reasoningContentPresent": bool(
            isinstance(message, dict)
            and isinstance(message.get("reasoning_content"), str)
            and message["reasoning_content"].strip()
        ),
        "finishReason": choice.get("finish_reason"),
        "truncated": choice.get("finish_reason") == "length",
    }
    if not content.strip():
        result.update({"jsonValid": False, "contractValid": False, "contractError": "content_empty"})
    else:
        try:
            parsed = json.loads(content)
        except json.JSONDecodeError:
            result.update({"jsonValid": False, "contractValid": False, "contractError": "invalid_content_json"})
        else:
            valid, error = validate_contract(parsed)
            result.update({"jsonValid": True, "contractValid": valid, "contractError": error})
    usage = root.get("usage")
    if isinstance(usage, dict):
        for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
            if isinstance(usage.get(key), int):
                result[key] = usage[key]
    return result


def call_model(base_url: str, model: str, question: str, max_tokens: int, timeout: float) -> dict[str, Any]:
    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": question}],
            "response_format": structured_schema(),
            "max_tokens": max_tokens,
            "stream": False,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    api_key = os.environ.get("AI_API_KEY", "").strip()
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/chat/completions",
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
        status = exc.code
        raw = exc.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return {
            "statusCode": None,
            "requestErrorType": type(exc).__name__,
            "latencyMs": round((time.monotonic() - started) * 1000, 2),
        }
    result = parse_response(raw, status)
    result["latencyMs"] = round((time.monotonic() - started) * 1000, 2)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:1234/v1")
    parser.add_argument("--model", action="append", required=True, help="Model id; repeat to compare models")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--max-tokens", type=int, default=2400)
    parser.add_argument("--complex-max-tokens", type=int, default=3200)
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.repeats < 1 or args.max_tokens < 1 or args.complex_max_tokens < 1:
        parser.error("repeats and token budgets must be positive")

    rows: list[dict[str, Any]] = []
    for model in args.model:
        for repeat in range(1, args.repeats + 1):
            for probe_index, (probe_id, question) in enumerate(PROBES):
                max_tokens = args.complex_max_tokens if probe_index else args.max_tokens
                result = call_model(args.base_url, model, question, max_tokens, args.timeout)
                rows.append(
                    {
                        "model": model,
                        "repeat": repeat,
                        "probe": probe_id,
                        "maxTokens": max_tokens,
                        **result,
                    }
                )

    report = {
        "schemaVersion": "structured-output-probe-v1",
        "baseUrl": args.base_url,
        "models": args.model,
        "repeats": args.repeats,
        "results": rows,
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
        print(f"wrote {len(rows)} structured-output probe results to {args.output}")
    else:
        print(encoded, end="")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exc:
        print(f"structured-output probe error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
