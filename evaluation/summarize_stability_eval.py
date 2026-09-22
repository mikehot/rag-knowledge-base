#!/usr/bin/env python3
"""Summarize repeated API captures without exposing answers or credentials.

The input is JSONL produced by ``run_stability_eval.py``. This report is a
repeatability summary, not the deterministic answer-quality scorer: it reports
outcome consistency, failure reasons, sources, latency, and token usage while
omitting answer text and request IDs.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean
from typing import Any

from run_api_eval import load_jsonl


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    weight = index - lower
    return round(ordered[lower] + (ordered[upper] - ordered[lower]) * weight, 4)


def numeric_values(rows: list[dict[str, Any]], field: str) -> list[float]:
    return [float(row[field]) for row in rows if isinstance(row.get(field), (int, float)) and not isinstance(row.get(field), bool)]


def source_names(row: dict[str, Any]) -> tuple[str, ...]:
    names: set[str] = set()
    for source in row.get("sources", []):
        if not isinstance(source, dict):
            continue
        filename = source.get("filename") or source.get("document")
        if isinstance(filename, str) and filename.strip():
            names.add(filename.strip())
    return tuple(sorted(names))


def outcome_signature(row: dict[str, Any]) -> tuple[Any, ...]:
    failure = row.get("failureReason") or "NONE"
    return (
        row.get("statusCode"),
        row.get("found") is True,
        row.get("grounded") is True,
        str(failure),
        source_names(row),
    )


def summarize_group(rows: list[dict[str, Any]]) -> dict[str, Any]:
    failures = Counter(str(row.get("failureReason") or "NONE") for row in rows)
    sources = Counter(name for row in rows for name in source_names(row))
    signatures = {outcome_signature(row) for row in rows}
    api_latency = numeric_values(rows, "apiLatencyMs")
    http_latency = numeric_values(rows, "httpLatencyMs")
    tokens = numeric_values(rows, "tokenUsage")
    return {
        "responses": len(rows),
        "repeats": sorted({row["repeat"] for row in rows}),
        "found_count": sum(row.get("found") is True for row in rows),
        "grounded_count": sum(row.get("grounded") is True for row in rows),
        "failure_reasons": dict(sorted(failures.items())),
        "source_filenames": dict(sorted(sources.items())),
        "outcome_variants": len(signatures),
        "outcome_stable": len(signatures) <= 1,
        "api_latency_ms": {
            "p50": percentile(api_latency, 0.50),
            "p95": percentile(api_latency, 0.95),
            "max": round(max(api_latency), 4) if api_latency else None,
        },
        "http_latency_ms": {
            "p50": percentile(http_latency, 0.50),
            "p95": percentile(http_latency, 0.95),
            "max": round(max(http_latency), 4) if http_latency else None,
        },
        "token_usage": {
            "avg": round(mean(tokens), 4) if tokens else None,
            "max": round(max(tokens), 4) if tokens else None,
        },
    }


def validate_rows(rows: list[dict[str, Any]]) -> None:
    seen: set[tuple[str, str, int]] = set()
    for row in rows:
        case_id = row.get("id")
        mode = row.get("retrievalMode")
        repeat = row.get("repeat")
        if not isinstance(case_id, str) or not case_id.strip():
            raise ValueError("each row requires a non-empty id")
        if not isinstance(mode, str) or not mode.strip():
            raise ValueError(f"{case_id} requires retrievalMode")
        if isinstance(repeat, bool) or not isinstance(repeat, int) or repeat < 1:
            raise ValueError(f"{case_id} requires a positive integer repeat")
        key = (mode, case_id, repeat)
        if key in seen:
            raise ValueError(f"duplicate stability row: {key}")
        seen.add(key)


def normalize_capture_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Accept the current collector fields and the previous local diagnostic shape."""
    normalized: list[dict[str, Any]] = []
    for row in rows:
        copy = dict(row)
        if "retrievalMode" not in copy and isinstance(copy.get("mode"), str):
            copy["retrievalMode"] = copy["mode"]
        if "apiLatencyMs" not in copy and "latencyMs" in copy:
            copy["apiLatencyMs"] = copy["latencyMs"]
        normalized.append(copy)
    return normalized


def build_report(paths: list[Path]) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for path in paths:
        loaded = load_jsonl(path)
        rows.extend(normalize_capture_rows(loaded))
    validate_rows(rows)

    groups: defaultdict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    modes: defaultdict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        mode = str(row["retrievalMode"])
        case_id = str(row["id"])
        groups[(mode, case_id)].append(row)
        modes[mode].append(row)

    case_summary = {
        f"{mode}:{case_id}": summarize_group(group)
        for (mode, case_id), group in sorted(groups.items())
    }
    mode_summary = {mode: summarize_group(group) for mode, group in sorted(modes.items())}
    return {
        "capture_files": [str(path) for path in paths],
        "responses_evaluated": len(rows),
        "raw_answers_omitted": True,
        "mode_summary": mode_summary,
        "case_summary": case_summary,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, action="append", required=True, help="JSONL capture; repeat for multiple modes")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    args = parser.parse_args()
    try:
        report = build_report(args.input)
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, ValueError) as exc:
        print(f"stability summary error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
