#!/usr/bin/env python3
"""Run a deterministic, ACL-aware keyword candidate benchmark.

The benchmark deliberately ignores model answers and answer-point match terms.
It derives candidates only from the question and the versioned fixture corpus,
then measures document recall, ACL safety, candidate latency, and context
interference at the current Top-K=5 boundary.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import time
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Any

try:
    from prepare_api_fixture import FIXTURE_DOCS
except ImportError as exc:  # pragma: no cover - protects direct module misuse
    raise RuntimeError("run from the repository root as evaluation/run_keyword_candidate_benchmark.py") from exc


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "keyword_candidates_v1.jsonl"
DEFAULT_OUTPUT = ROOT / "evaluation" / "reports" / "keyword-candidates-v1-local.json"
DEFAULT_CONTEXT_K = 5
DEFAULT_CANDIDATE_K = 10
VARIANTS = ("raw_char_ngram", "normalized_keyword")
ALLOWED_BEHAVIORS = {"ANSWER", "REFUSE", "ACL_FILTERED_REFUSAL"}

# These are generic question/form words, not expected answer terms. Removing
# them is the only query normalization used by normalized_keyword.
STOP_PHRASES = (
    "请问",
    "请说明",
    "请给出",
    "请同时说明",
    "告诉我",
    "有哪些",
    "哪个",
    "哪些",
    "什么",
    "如何",
    "怎么",
    "怎样",
    "为什么",
    "多少",
    "是否",
    "有没有",
    "可以",
    "能否",
    "分别",
    "说明",
    "给出",
    "需要",
    "要求",
    "情况",
    "相关",
    "时候",
    "以及",
    "后",
    "前",
    "的",
    "吗",
    "呢",
    "请",
)
CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+")
ASCII_RE = re.compile(r"[a-z0-9][a-z0-9._:/%+\-]*")


def fail(message: str) -> None:
    raise ValueError(message)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        fail(f"file not found: {path}")
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            fail(f"{path}:{line_number} invalid JSON: {exc.msg}")
        if not isinstance(value, dict):
            fail(f"{path}:{line_number} must contain an object")
        rows.append(value)
    return rows


def validate_cases(cases: list[dict[str, Any]]) -> str:
    if not cases:
        fail("keyword dataset must contain at least one case")
    versions = {case.get("dataset_version") for case in cases}
    if len(versions) != 1 or None in versions:
        fail(f"expected one dataset version, found {sorted(versions, key=str)}")
    ids = [case.get("id") for case in cases]
    if len(set(ids)) != len(ids):
        fail("case ids must be unique")
    for case in cases:
        case_id = case.get("id", "<unknown>")
        for field in ("question", "expected_source_documents", "acting_user", "forbidden_documents", "expected_behavior"):
            if field not in case:
                fail(f"{case_id} missing field: {field}")
        if not isinstance(case["question"], str) or not case["question"].strip():
            fail(f"{case_id}.question must be non-empty")
        if not isinstance(case["expected_source_documents"], list) or not all(
            isinstance(item, str) and item.strip() for item in case["expected_source_documents"]
        ):
            fail(f"{case_id}.expected_source_documents must contain strings")
        if not isinstance(case["forbidden_documents"], list) or not all(
            isinstance(item, str) and item.strip() for item in case["forbidden_documents"]
        ):
            fail(f"{case_id}.forbidden_documents must contain strings")
        if "expected_answer_points" in case or "refusal_match_terms" in case:
            fail(f"{case_id} must not contain model-answer or refusal match terms")
        if case["expected_behavior"] not in ALLOWED_BEHAVIORS:
            fail(f"{case_id} has unsupported behavior: {case['expected_behavior']}")
        user = case["acting_user"]
        if not isinstance(user, dict) or not isinstance(user.get("id"), str) or not user["id"].strip():
            fail(f"{case_id}.acting_user.id must be non-empty")
        sources = set(case["expected_source_documents"])
        forbidden = set(case["forbidden_documents"])
        if sources & forbidden:
            fail(f"{case_id} expected and forbidden documents overlap")
        if case["expected_behavior"] == "ANSWER" and not sources:
            fail(f"{case_id} ANSWER requires expected source documents")
        if case["expected_behavior"] != "ANSWER" and sources:
            fail(f"{case_id} refusal cases cannot have expected source documents")
    return str(next(iter(versions)))


def normalize_text(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold()


def normalized_query(value: str) -> str:
    text = normalize_text(value)
    for phrase in sorted(STOP_PHRASES, key=len, reverse=True):
        text = text.replace(phrase, " ")
    return text


def cjk_runs(value: str) -> list[str]:
    return CJK_RE.findall(normalize_text(value))


def tokenize(value: str, normalized: bool) -> list[str]:
    text = normalized_query(value) if normalized else normalize_text(value)
    tokens = ASCII_RE.findall(text)
    for run in cjk_runs(text):
        for size in (2, 3):
            tokens.extend(run[index : index + size] for index in range(len(run) - size + 1))
    return tokens


def phrase_tokens(value: str, normalized: bool) -> list[str]:
    text = normalized_query(value) if normalized else normalize_text(value)
    return [run for run in cjk_runs(text) if len(run) >= 2]


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 4)


def build_corpus() -> dict[str, str]:
    corpus = {"sample_faq.md": (ROOT / "sample_faq.md").read_text(encoding="utf-8")}
    corpus.update(FIXTURE_DOCS)
    return corpus


def visible_documents(actor_id: str, corpus: dict[str, str]) -> set[str]:
    if actor_id == "demo.admin":
        return set(corpus)
    if actor_id in {"demo.employee", "demo.auditor"}:
        return {
            "sample_faq.md",
            "device-installation.md",
            "support-sla.md",
            "release-notes-v2.md",
            "long-ops-manual.md",
        }
    if actor_id == "demo.outsider":
        return {"sample_faq.md"}
    fail(f"no ACL fixture defined for actor {actor_id}")


def build_index(corpus: dict[str, str], normalized: bool) -> tuple[dict[str, Counter[str]], dict[str, list[str]], dict[str, float], float]:
    counts = {filename: Counter(tokenize(text, normalized)) for filename, text in corpus.items()}
    phrases = {filename: phrase_tokens(text, normalized) for filename, text in corpus.items()}
    document_frequency: Counter[str] = Counter()
    for terms in counts.values():
        document_frequency.update(terms.keys())
    total_documents = len(corpus)
    idf = {
        term: math.log(1.0 + (total_documents - frequency + 0.5) / (frequency + 0.5))
        for term, frequency in document_frequency.items()
    }
    average_length = statistics.mean(sum(terms.values()) for terms in counts.values()) or 1.0
    return counts, phrases, idf, average_length


def rank_candidates(
    question: str,
    corpus: dict[str, str],
    visible: set[str],
    normalized: bool,
    limit: int,
    index: tuple[dict[str, Counter[str]], dict[str, list[str]], dict[str, float], float] | None = None,
) -> list[dict[str, Any]]:
    counts, _phrases, idf, average_length = index or build_index(corpus, normalized)
    query_terms = Counter(tokenize(question, normalized))
    query_phrases = phrase_tokens(question, normalized)
    k1 = 1.2
    b = 0.75
    ranked: list[dict[str, Any]] = []
    for filename in sorted(visible):
        terms = counts[filename]
        document_length = sum(terms.values()) or 1
        score = 0.0
        matched_terms: set[str] = set()
        for term, query_frequency in query_terms.items():
            term_frequency = terms.get(term, 0)
            if not term_frequency:
                continue
            matched_terms.add(term)
            denominator = term_frequency + k1 * (1 - b + b * document_length / average_length)
            score += idf.get(term, 0.0) * (term_frequency * (k1 + 1) / denominator) * min(query_frequency, 2)
        if normalized:
            document_text = normalize_text(corpus[filename])
            for phrase in set(query_phrases):
                if phrase in document_text:
                    score += min(2.0, 0.25 * len(phrase))
        if score > 0:
            ranked.append(
                {
                    "filename": filename,
                    "score": round(score, 6),
                    "matchedTermCount": len(matched_terms),
                }
            )
    ranked.sort(key=lambda item: (-item["score"], item["filename"]))
    for rank, item in enumerate(ranked[:limit], 1):
        item["rank"] = rank
    return ranked[:limit]


def score_case(case: dict[str, Any], hits: list[dict[str, Any]], context_k: int) -> dict[str, Any]:
    expected = set(case["expected_source_documents"])
    forbidden = set(case["forbidden_documents"])
    context_hits = hits[:context_k]
    context_names = {item["filename"] for item in context_hits}
    candidate_names = {item["filename"] for item in hits}
    leakage = sorted(candidate_names & forbidden)
    recall_at_k: dict[str, float | None] = {}
    for cutoff in (1, 3, 5, 10):
        if expected:
            recall_at_k[f"recall_at_{cutoff}"] = round(
                len(expected & {item["filename"] for item in hits if item["rank"] <= cutoff}) / len(expected), 4
            )
        else:
            recall_at_k[f"recall_at_{cutoff}"] = None
    missing_context_sources = sorted(expected - context_names)
    interference = sorted(context_names - expected)
    return {
        "id": case["id"],
        "behavior": case["expected_behavior"],
        "candidate_count": len(hits),
        "recall_at_k": recall_at_k,
        "first_expected_rank": min(
            (item["rank"] for item in hits if item["filename"] in expected),
            default=None,
        ),
        "context_source_documents": sorted(context_names),
        "missing_expected_context_sources": missing_context_sources,
        "context_interference_documents": interference,
        "context_interference_count": len(interference),
        "acl_leakage": bool(leakage),
        "acl_leaked_documents": leakage,
        "refusal_has_candidates": not expected and bool(hits),
        "passed": not leakage and not missing_context_sources and (bool(expected) or not hits),
        "hits": hits,
    }


def evaluate_variant(
    cases: list[dict[str, Any]],
    corpus: dict[str, str],
    variant: str,
    context_k: int,
    candidate_k: int,
    iterations: int,
) -> dict[str, Any]:
    normalized = variant == "normalized_keyword"
    index = build_index(corpus, normalized)
    results: list[dict[str, Any]] = []
    timings: list[float] = []
    for case in cases:
        visible = visible_documents(case["acting_user"]["id"], corpus)
        samples: list[float] = []
        hits: list[dict[str, Any]] = []
        for iteration in range(max(2, iterations + 1)):
            started = time.perf_counter_ns()
            hits = rank_candidates(case["question"], corpus, visible, normalized, candidate_k, index)
            elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
            if iteration > 0:
                samples.append(elapsed_ms)
                timings.append(elapsed_ms)
        result = score_case(case, hits, context_k)
        result["latency_ms"] = {
            "p50": percentile(samples, 0.50),
            "p95": percentile(samples, 0.95),
            "max": round(max(samples), 4) if samples else None,
        }
        result["acl_filtered_document_count"] = len(corpus) - len(visible)
        results.append(result)
    answerable = [item for item in results if item["behavior"] == "ANSWER"]
    refusal = [item for item in results if item["behavior"] != "ANSWER"]

    def average(values: list[float]) -> float | None:
        return round(statistics.mean(values), 4) if values else None

    answerable_context_slots = sum(min(context_k, item["candidate_count"]) for item in answerable)
    answerable_context_interference = sum(item["context_interference_count"] for item in answerable)

    summary: dict[str, Any] = {
        "variant": variant,
        "context_k": context_k,
        "candidate_k": candidate_k,
        "case_count": len(results),
        "answerable_case_count": len(answerable),
        "passed_cases": sum(1 for item in results if item["passed"]),
        "failed_cases": sum(1 for item in results if not item["passed"]),
        "recall_at_1": average([item["recall_at_k"]["recall_at_1"] for item in answerable if item["recall_at_k"]["recall_at_1"] is not None]),
        "recall_at_3": average([item["recall_at_k"]["recall_at_3"] for item in answerable if item["recall_at_k"]["recall_at_3"] is not None]),
        "recall_at_5": average([item["recall_at_k"]["recall_at_5"] for item in answerable if item["recall_at_k"]["recall_at_5"] is not None]),
        "recall_at_candidate_k": average([item["recall_at_k"]["recall_at_10"] for item in answerable if item["recall_at_k"]["recall_at_10"] is not None]),
        "first_expected_rank_avg": average([float(item["first_expected_rank"]) for item in answerable if item["first_expected_rank"] is not None]),
        "candidate_count_avg": average([float(item["candidate_count"]) for item in results]),
        "context_interference_count_avg": average([float(item["context_interference_count"]) for item in results]),
        "context_interference_rate": round(
            sum(item["context_interference_count"] for item in results)
            / sum(min(context_k, item["candidate_count"]) for item in results),
            4,
        )
        if sum(min(context_k, item["candidate_count"]) for item in results)
        else 0.0,
        "answerable_context_interference_count_avg": average(
            [float(item["context_interference_count"]) for item in answerable]
        ),
        "answerable_context_interference_rate": round(
            answerable_context_interference / answerable_context_slots, 4
        )
        if answerable_context_slots
        else 0.0,
        "refusal_candidate_count": sum(1 for item in refusal if item["refusal_has_candidates"]),
        "acl_leakage_count": sum(1 for item in results if item["acl_leakage"]),
        "acl_filtered_document_count_avg": average([float(item["acl_filtered_document_count"]) for item in results]),
        "latency_ms": {
            "p50": percentile(timings, 0.50),
            "p95": percentile(timings, 0.95),
            "max": round(max(timings), 4) if timings else None,
        },
        "case_results": results,
    }
    return summary


def decision(summaries: dict[str, dict[str, Any]]) -> dict[str, Any]:
    normalized = summaries["normalized_keyword"]
    return {
        "current_vector_reference": {
            "dataset": "retrieval-stress-v1",
            "recall_at_5": 1.0,
            "acl_leakage_count": 0,
            "note": "The checked-in vector report is API-level evidence; its end-to-end latency is not directly comparable with offline keyword CPU latency.",
        },
        "recommendation": "do_not_enable_hybrid_yet",
        "reason": (
            "This benchmark is a candidate-stage signal only. Keep the current vector Top-K=5 default and do not add a runtime keyword stage until a same-request vector-plus-keyword comparison shows unique source recovery without increasing ACL risk or context interference."
            if normalized["recall_at_5"] is None or normalized["recall_at_5"] <= 1.0
            else "Keyword candidates show potential additional recall; run a matched vector-plus-keyword API capture before enabling the feature."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--context-k", type=int, default=DEFAULT_CONTEXT_K)
    parser.add_argument("--candidate-k", type=int, default=DEFAULT_CANDIDATE_K)
    parser.add_argument("--iterations", type=int, default=8, help="Timed repetitions per case after one warm-up run")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        if args.context_k < 1 or args.candidate_k < args.context_k or args.iterations < 1:
            fail("require candidate-k >= context-k >= 1 and iterations >= 1")
        cases = load_jsonl(args.dataset)
        dataset_version = validate_cases(cases)
        corpus = build_corpus()
        all_expected = {filename for case in cases for filename in case["expected_source_documents"]}
        missing_corpus = sorted(all_expected - set(corpus))
        if missing_corpus:
            fail(f"dataset references documents missing from benchmark corpus: {missing_corpus}")
        summaries = {
            variant: evaluate_variant(cases, corpus, variant, args.context_k, args.candidate_k, args.iterations)
            for variant in VARIANTS
        }
        report = {
            "dataset": str(args.dataset),
            "dataset_version": dataset_version,
            "corpus_documents": sorted(corpus),
            "corpus_source": "sample_faq.md plus sanitized FIXTURE_DOCS from prepare_api_fixture.py",
            "algorithm": {
                "raw_char_ngram": "question-derived ASCII tokens plus CJK 2/3-character grams",
                "normalized_keyword": "the same tokens after generic question-word removal, with exact normalized phrase bonus",
                "acl": "filter visible documents before scoring; model answers and answer-point terms are never read",
            },
            "benchmarks": summaries,
            "decision": decision(summaries),
        }
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"keyword candidate benchmark error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
