#!/usr/bin/env python3
"""Collect repeated API responses for a small stability sample.

This is intentionally separate from ``run_api_eval.py``. Repeated rows keep
the original case id plus a ``repeat`` field, so the output must not be passed
to the normal deterministic scorer, which requires unique case ids.
Credentials and raw responses stay outside the repository.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from run_api_eval import flatten_case, load_credentials, load_jsonl, login, request_json


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "answer_quality_v1.jsonl"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case-id", action="append", dest="case_ids")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument(
        "--retrieval-mode",
        choices=("vector", "vector-diversity", "keyword-rrf", "vector-adjacent"),
        default="vector",
    )
    parser.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args()

    if args.repeats < 1:
        parser.error("--repeats must be positive")

    try:
        cases = load_jsonl(args.dataset)
        selected_ids = set(args.case_ids or [])
        if selected_ids:
            cases = [case for case in cases if case.get("id") in selected_ids]
            found_ids = {str(case.get("id")) for case in cases}
            missing_ids = sorted(selected_ids - found_ids)
            if missing_ids:
                raise ValueError(f"unknown case ids: {missing_ids}")
        if not cases:
            raise ValueError("no evaluation cases selected")

        credentials = load_credentials(args.credentials)
        actor_ids = {case["acting_user"]["id"] for case in cases}
        missing_actors = sorted(actor_ids - credentials.keys())
        if missing_actors:
            raise ValueError(f"credentials are missing acting users: {missing_actors}")

        tokens: dict[str, str] = {}
        rows: list[dict[str, object]] = []
        for repeat in range(1, args.repeats + 1):
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
                row = flatten_case(
                    case,
                    status,
                    envelope,
                    elapsed_ms,
                    actor_id,
                    args.retrieval_mode,
                )
                row["repeat"] = repeat
                rows.append(row)

        args.output.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )
        print(
            f"captured {len(rows)} responses ({len(cases)} cases x {args.repeats} repeats) "
            f"to {args.output}; use a stability-specific summary, not run_eval.py"
        )
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"stability evaluation error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
