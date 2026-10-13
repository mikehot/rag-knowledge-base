#!/usr/bin/env python3
"""Run an evaluation dataset several times and report run-to-run variation.

A single run of a non-deterministic model cannot support a comparison: on 2026-10-13 an unchanged
configuration scored 44, 44, 44, 43 and 41 answerable questions across backend restarts, which turned
two reported "gains" of 3-4 cases into noise. This tool makes the spread part of every result.

    # N repeats of one configuration (optionally shuffling question order, or restarting the backend)
    run_repeated_eval.py run --dataset D --credentials C --label baseline --repeats 3 --out-dir DIR

    # decide whether two configurations differ more than they vary
    run_repeated_eval.py compare DIR/baseline.summary.json DIR/candidate.summary.json

The verdict is deliberately conservative: two configurations are DISTINGUISHABLE only when the ranges of
their per-run scores do not overlap and each side has at least three runs. Anything else is reported as
WITHIN VARIATION or INSUFFICIENT RUNS, never as a win.
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import run_api_eval
import run_eval

MIN_RUNS_FOR_VERDICT = 3
METRICS = ("answerable_ok", "refusals_ok", "all_ok", "quality_gate_ok")


# ---------------------------------------------------------------------------------------------
# Pure scoring and aggregation (unit tested)
# ---------------------------------------------------------------------------------------------

def case_ok(result: dict[str, Any]) -> bool:
    """A case counts only if it passed its contract and its quality rules (when it has any)."""
    return bool(result.get("passed")) and result.get("quality_gate_pass") is not False


def score_run(cases: list[dict[str, Any]], responses: list[dict[str, Any]]) -> dict[str, Any]:
    """Score one captured run with the deterministic scorer and keep only what is needed to aggregate."""
    evaluation = run_eval.evaluate_responses(cases, [dict(row) for row in responses])
    behavior = {case["id"]: case["expected_behavior"] for case in cases}
    unexpected = [item for item in evaluation["case_results"] if item["id"] not in behavior]
    if unexpected:
        raise ValueError(f"capture does not match the dataset: {unexpected[0].get('errors')}")
    by_id = {item["id"]: item for item in evaluation["case_results"]}
    per_case = {cid: case_ok(result) for cid, result in by_id.items()}
    answerable = [cid for cid in per_case if behavior[cid] == "ANSWER"]
    other = [cid for cid in per_case if behavior[cid] != "ANSWER"]
    gated = [cid for cid, r in by_id.items() if r.get("quality_gate_pass") is not None]
    tokens = [row.get("tokenUsage") for row in responses if isinstance(row.get("tokenUsage"), (int, float))]
    reasons: dict[str, int] = {}
    for row in responses:
        reason = row.get("failureReason")
        if reason:
            reasons[reason] = reasons.get(reason, 0) + 1
    return {
        "answerable_ok": sum(per_case[cid] for cid in answerable),
        "answerable_total": len(answerable),
        "refusals_ok": sum(per_case[cid] for cid in other),
        "refusals_total": len(other),
        "all_ok": sum(per_case.values()),
        "total": len(per_case),
        "quality_gate_ok": sum(1 for cid in gated if by_id[cid].get("quality_gate_pass")),
        "quality_gate_total": len(gated),
        "acl_leakage": evaluation.get("acl_leakage_count", 0),
        "schema_failures": evaluation.get("schema_failure_count", 0),
        "mean_tokens": round(statistics.mean(tokens), 1) if tokens else None,
        "failure_reasons": reasons,
        "per_case": per_case,
    }


def _spread(values: list[float]) -> dict[str, float]:
    return {
        "mean": round(statistics.mean(values), 2),
        "min": min(values),
        "max": max(values),
        "stdev": round(statistics.stdev(values), 2) if len(values) > 1 else 0.0,
    }


def aggregate(runs: list[dict[str, Any]]) -> dict[str, Any]:
    """Per-metric spread over runs, plus per-case stability (pass rate over runs)."""
    result: dict[str, Any] = {"runs": len(runs)}
    for metric in METRICS:
        result[metric] = _spread([run[metric] for run in runs])
    tokens = [run["mean_tokens"] for run in runs if run.get("mean_tokens") is not None]
    result["mean_tokens"] = _spread(tokens) if tokens else None
    result["acl_leakage_max"] = max(run["acl_leakage"] for run in runs)
    result["schema_failures_max"] = max(run["schema_failures"] for run in runs)
    ids = list(runs[0]["per_case"]) if runs else []
    rates = {cid: sum(run["per_case"][cid] for run in runs) / len(runs) for cid in ids}
    result["case_pass_rate"] = {cid: round(rate, 3) for cid, rate in rates.items()}
    result["flaky_cases"] = sorted(cid for cid, rate in rates.items() if 0 < rate < 1)
    result["always_failing_cases"] = sorted(cid for cid, rate in rates.items() if rate == 0)
    return result


def compare(a: dict[str, Any], b: dict[str, Any], metric: str = "answerable_ok") -> dict[str, Any]:
    """Decide whether two summaries differ by more than their own run-to-run variation."""
    agg_a, agg_b = a["aggregate"], b["aggregate"]
    sa, sb = agg_a[metric], agg_b[metric]
    runs_a, runs_b = agg_a["runs"], agg_b["runs"]
    delta = round(sb["mean"] - sa["mean"], 2)
    if min(runs_a, runs_b) < MIN_RUNS_FOR_VERDICT:
        verdict = "INSUFFICIENT RUNS"
        reason = f"need at least {MIN_RUNS_FOR_VERDICT} runs per side, have {runs_a} and {runs_b}"
    elif sb["min"] > sa["max"]:
        verdict = "DISTINGUISHABLE"
        reason = f"B is higher: its worst run ({sb['min']}) beats A's best run ({sa['max']})"
    elif sa["min"] > sb["max"]:
        verdict = "DISTINGUISHABLE"
        reason = f"A is higher: its worst run ({sa['min']}) beats B's best run ({sb['max']})"
    else:
        verdict = "WITHIN VARIATION"
        reason = f"ranges overlap: A {sa['min']}-{sa['max']}, B {sb['min']}-{sb['max']}"
    rates_a, rates_b = agg_a["case_pass_rate"], agg_b["case_pass_rate"]
    shared = sorted(set(rates_a) & set(rates_b))
    better_in_b = [cid for cid in shared if rates_b[cid] - rates_a[cid] >= 0.5]
    better_in_a = [cid for cid in shared if rates_a[cid] - rates_b[cid] >= 0.5]
    return {
        "metric": metric,
        "a": {"label": a["label"], **sa, "runs": runs_a},
        "b": {"label": b["label"], **sb, "runs": runs_b},
        "mean_difference_b_minus_a": delta,
        "verdict": verdict,
        "reason": reason,
        "cases_clearly_better_in_b": better_in_b,
        "cases_clearly_better_in_a": better_in_a,
    }


def question_order(case_ids: list[str], shuffle: bool, seed: int, run_index: int) -> list[str]:
    """Question order for one run. Shuffled orders are reproducible from (seed, run_index)."""
    if not shuffle:
        return list(case_ids)
    order = list(case_ids)
    random.Random(seed * 1000 + run_index).shuffle(order)
    return order


def render_markdown(summary: dict[str, Any]) -> str:
    agg = summary["aggregate"]
    lines = [
        f"### {summary['label']}",
        "",
        f"Dataset `{summary['dataset_version']}`, {agg['runs']} runs, "
        f"order {'shuffled (seed ' + str(summary['seed']) + ')' if summary['shuffle'] else 'fixed'}"
        f"{', backend restarted before each run' if summary['restart_before_each_run'] else ''}.",
        "",
        "| Run | Answerable | Refusals | Quality gate | ACL leakage | Mean tokens |",
        "|---|---|---|---|---|---|",
    ]
    for index, run in enumerate(summary["runs"], 1):
        lines.append(
            f"| {index} | {run['answerable_ok']}/{run['answerable_total']} | {run['refusals_ok']}/{run['refusals_total']} "
            f"| {run['quality_gate_ok']}/{run['quality_gate_total']} | {run['acl_leakage']} | {run['mean_tokens']} |"
        )
    a = agg["answerable_ok"]
    lines += [
        "",
        f"**Answerable: mean {a['mean']}, range {a['min']}–{a['max']}, stdev {a['stdev']}.** "
        f"Flaky cases ({len(agg['flaky_cases'])}): {', '.join(agg['flaky_cases']) or 'none'}. "
        f"Always failing ({len(agg['always_failing_cases'])}): {', '.join(agg['always_failing_cases']) or 'none'}.",
    ]
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------
# Live execution
# ---------------------------------------------------------------------------------------------

def wait_ready(base_url: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{base_url.rstrip('/')}/readyz", timeout=3) as response:
                if response.status == 200 and b"UP" in response.read():
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(2)
    raise TimeoutError(f"backend at {base_url} did not become ready within {timeout:.0f}s")


def capture_run(
    cases: list[dict[str, Any]],
    base_url: str,
    credentials: dict[str, dict[str, str]],
    retrieval_mode: str,
    order: list[str],
    timeout: float,
) -> list[dict[str, Any]]:
    by_id = {case["id"]: case for case in cases}
    tokens: dict[str, str] = {}
    rows: dict[str, dict[str, Any]] = {}
    for cid in order:
        case = by_id[cid]
        actor = case["acting_user"]["id"]
        if actor not in tokens:
            tokens[actor] = run_api_eval.login(base_url, credentials[actor], timeout)
        status, envelope, elapsed = run_api_eval.request_json(
            base_url, "/api/ask", {"question": case["question"]}, tokens[actor], timeout, retrieval_mode
        )
        rows[cid] = run_api_eval.flatten_case(case, status, envelope, elapsed, actor, retrieval_mode)
    return [rows[case["id"]] for case in cases]  # dataset order, whatever the asking order was


def run_command(args: argparse.Namespace) -> int:
    cases = run_api_eval.load_jsonl(args.dataset)
    run_eval.validate_dataset(cases, args.expected_case_count or len(cases))
    credentials = run_api_eval.load_credentials(args.credentials)
    missing = sorted({c["acting_user"]["id"] for c in cases} - credentials.keys())
    if missing:
        raise ValueError(f"credentials are missing acting users: {missing}")
    args.out_dir.mkdir(parents=True, exist_ok=True)
    version = cases[0]["dataset_version"]
    runs: list[dict[str, Any]] = []
    for index in range(1, args.repeats + 1):
        if args.restart_cmd:
            subprocess.run(args.restart_cmd, shell=True, check=True)
            wait_ready(args.base_url, args.ready_timeout)
        order = question_order([c["id"] for c in cases], args.shuffle, args.seed, index)
        started = time.monotonic()
        responses = capture_run(cases, args.base_url, credentials, args.retrieval_mode, order, args.timeout)
        capture_path = args.out_dir / f"{args.label}.run{index}.jsonl"
        capture_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in responses) + "\n", encoding="utf-8"
        )
        scored = score_run(cases, responses)
        scored["seconds"] = round(time.monotonic() - started, 1)
        runs.append(scored)
        print(
            f"run {index}/{args.repeats}: answerable {scored['answerable_ok']}/{scored['answerable_total']}, "
            f"refusals {scored['refusals_ok']}/{scored['refusals_total']}, "
            f"acl_leakage {scored['acl_leakage']} ({scored['seconds']}s)"
        )
    summary = {
        "label": args.label,
        "dataset": str(args.dataset),
        "dataset_version": version,
        "created": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "retrieval_mode": args.retrieval_mode,
        "shuffle": args.shuffle,
        "seed": args.seed,
        "restart_before_each_run": bool(args.restart_cmd),
        "runs": runs,
        "aggregate": aggregate(runs),
    }
    path = args.out_dir / f"{args.label}.summary.json"
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(render_markdown(summary))
    print(f"summary written to {path}")
    return 0


def compare_command(args: argparse.Namespace) -> int:
    a = json.loads(args.a.read_text(encoding="utf-8"))
    b = json.loads(args.b.read_text(encoding="utf-8"))
    if a["dataset_version"] != b["dataset_version"]:
        raise ValueError(f"datasets differ: {a['dataset_version']} vs {b['dataset_version']}")
    for metric in args.metric:
        result = compare(a, b, metric)
        print(
            f"{metric}: A '{result['a']['label']}' mean {result['a']['mean']} "
            f"[{result['a']['min']}-{result['a']['max']}] ({result['a']['runs']} runs) | "
            f"B '{result['b']['label']}' mean {result['b']['mean']} "
            f"[{result['b']['min']}-{result['b']['max']}] ({result['b']['runs']} runs)"
        )
        print(f"  difference (B-A): {result['mean_difference_b_minus_a']:+}  ->  {result['verdict']}: {result['reason']}")
        if result["cases_clearly_better_in_b"] or result["cases_clearly_better_in_a"]:
            print(f"  clearly better in B: {', '.join(result['cases_clearly_better_in_b']) or '-'}")
            print(f"  clearly better in A: {', '.join(result['cases_clearly_better_in_a']) or '-'}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="run a dataset several times and summarise the variation")
    run.add_argument("--base-url", default="http://localhost:8080")
    run.add_argument("--dataset", type=Path, required=True)
    run.add_argument("--credentials", type=Path, required=True, help="External JSON map keyed by acting_user.id")
    run.add_argument("--label", required=True, help="Short name; used in output file names")
    run.add_argument("--out-dir", type=Path, required=True, help="Directory for per-run captures and the summary")
    run.add_argument("--repeats", type=int, default=3)
    run.add_argument("--shuffle", action="store_true", help="Ask the questions in a different order each run")
    run.add_argument("--seed", type=int, default=1)
    run.add_argument("--retrieval-mode", default="vector",
                     choices=("vector", "vector-diversity", "keyword-rrf", "vector-adjacent"))
    run.add_argument("--expected-case-count", type=int)
    run.add_argument("--timeout", type=float, default=180.0)
    run.add_argument("--restart-cmd", help="Shell command run before every run (e.g. restart the backend)")
    run.add_argument("--ready-timeout", type=float, default=240.0)

    cmp_ = sub.add_parser("compare", help="compare two summaries against their own variation")
    cmp_.add_argument("a", type=Path)
    cmp_.add_argument("b", type=Path)
    cmp_.add_argument("--metric", action="append", choices=METRICS, default=None)

    args = parser.parse_args()
    if args.command == "compare" and not args.metric:
        args.metric = ["answerable_ok", "refusals_ok"]
    if args.command == "run" and args.repeats < 1:
        parser.error("--repeats must be at least 1")
    try:
        return run_command(args) if args.command == "run" else compare_command(args)
    except (OSError, ValueError, RuntimeError, TimeoutError, subprocess.CalledProcessError,
            urllib.error.URLError) as exc:
        print(f"repeated evaluation error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
