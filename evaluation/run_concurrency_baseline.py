#!/usr/bin/env python3
"""Measure /api/ask latency and error behaviour under a few fixed concurrency levels.

This is a single-machine baseline, not a capacity or SLO claim: with a local model server (for example
LM Studio) the model, not the backend, is normally the bottleneck, and requests queue behind it. Use it to
see how latency grows with concurrency and whether any request fails, and to record the numbers next to
the machine and model they came from.

Questions come from a versioned dataset (answerable ones only, round-robin, so every level asks the same
mix). Credentials come from the same external JSON file run_api_eval.py uses. Answers are not scored here;
use run_eval.py for quality. The report records HTTP status counts, `found` rate and failure reasons so a
run that "went fast" because requests failed is visible.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import run_api_eval as api

DEFAULT_DATASET = api.ROOT / "evaluation" / "datasets" / "corpus_v2.jsonl"


def percentile(sorted_values: list[float], fraction: float) -> float:
    """Nearest-rank percentile of an already sorted list; 0.0 for an empty list."""
    if not sorted_values:
        return 0.0
    rank = max(1, math.ceil(fraction * len(sorted_values)))
    return sorted_values[min(rank, len(sorted_values)) - 1]


def summarize_level(concurrency: int, rows: list[dict[str, Any]], wall_seconds: float) -> dict[str, Any]:
    latencies = sorted(row["httpLatencyMs"] for row in rows)
    statuses: dict[str, int] = {}
    reasons: dict[str, int] = {}
    for row in rows:
        statuses[str(row["statusCode"])] = statuses.get(str(row["statusCode"]), 0) + 1
        if row.get("failureReason"):
            reasons[str(row["failureReason"])] = reasons.get(str(row["failureReason"]), 0) + 1
    ok = [row for row in rows if row["statusCode"] == 200]
    return {
        "concurrency": concurrency,
        "requests": len(rows),
        "wallSeconds": round(wall_seconds, 2),
        "throughputPerMinute": round(len(rows) / wall_seconds * 60, 2) if wall_seconds > 0 else 0.0,
        "latencyMsP50": percentile(latencies, 0.50),
        "latencyMsP95": percentile(latencies, 0.95),
        "latencyMsMax": latencies[-1] if latencies else 0.0,
        "httpStatusCounts": statuses,
        "foundRate": round(sum(1 for row in ok if row["found"]) / len(ok), 3) if ok else 0.0,
        "failureReasons": reasons,
    }


def run_level(
    base_url: str,
    cases: list[dict[str, Any]],
    tokens: dict[str, str],
    concurrency: int,
    requests_per_level: int,
    timeout: float,
) -> dict[str, Any]:
    picked = [cases[index % len(cases)] for index in range(requests_per_level)]

    def ask(case: dict[str, Any]) -> dict[str, Any]:
        actor = case["acting_user"]["id"]
        try:
            status, envelope, elapsed = api.request_json(
                base_url, "/api/ask", {"question": case["question"]}, tokens[actor], timeout
            )
        except (OSError, urllib.error.URLError) as exc:  # timeout or connection reset counts as a failure
            return {"statusCode": 0, "httpLatencyMs": timeout * 1000, "found": False, "failureReason": type(exc).__name__}
        data = envelope.get("data") if isinstance(envelope.get("data"), dict) else {}
        return {
            "statusCode": status,
            "httpLatencyMs": elapsed,
            "found": data.get("found") is True,
            "failureReason": data.get("failureReason"),
        }

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        rows = list(pool.map(ask, picked))
    return summarize_level(concurrency, rows, time.monotonic() - started)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--credentials", type=Path, required=True, help="External JSON map keyed by acting_user.id")
    parser.add_argument("--output", type=Path, required=True, help="JSON report path")
    parser.add_argument("--levels", default="1,2,4", help="Comma-separated concurrency levels")
    parser.add_argument("--requests-per-level", type=int, default=12)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--note", default="", help="Free text recorded in the report, e.g. machine and models")
    args = parser.parse_args()
    try:
        levels = [int(part) for part in args.levels.split(",") if part.strip()]
        if not levels or any(level < 1 for level in levels) or args.requests_per_level < 1:
            raise ValueError("levels and requests-per-level must be positive")
        cases = [case for case in api.load_jsonl(args.dataset) if case.get("answerable")]
        if not cases:
            raise ValueError("dataset has no answerable cases")
        credentials = api.load_credentials(args.credentials)
        actors = {case["acting_user"]["id"] for case in cases}
        missing = sorted(actors - credentials.keys())
        if missing:
            raise ValueError(f"credentials are missing acting users: {missing}")
        tokens = {actor: api.login(args.base_url, credentials[actor], args.timeout) for actor in sorted(actors)}
        results = []
        for level in levels:
            print(f"concurrency {level}: {args.requests_per_level} requests ...", flush=True)
            summary = run_level(args.base_url, cases, tokens, level, args.requests_per_level, args.timeout)
            print(json.dumps(summary, ensure_ascii=False), flush=True)
            results.append(summary)
        report = {
            "dataset": str(args.dataset.name),
            "questionsInRotation": len(cases),
            "requestsPerLevel": args.requests_per_level,
            "note": args.note,
            "levels": results,
        }
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {args.output}")
        return 0
    except (OSError, ValueError, RuntimeError, urllib.error.URLError) as exc:
        print(f"concurrency baseline error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
