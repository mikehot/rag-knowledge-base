#!/usr/bin/env python3
"""Offline chunking-strategy replay for the synthetic evaluation fixture.

Re-chunks the fixture documents (`sample_faq.md` plus
`prepare_api_fixture.FIXTURE_DOCS`) with several strategies, embeds chunks and
dataset questions through an OpenAI-compatible embedding endpoint, applies the
fixture's per-actor document visibility, and reports whether the Top-5 chunks
contain expected answer-point evidence. It never calls a chat model or the
backend, and prints only aggregate and per-case rank metadata, never chunk text.

Strategies: S700/S300 replay `TextChunker` size packing; H splits at Markdown
headings; HP additionally prefixes each section with its parent heading path.
`--prefix` adds the nomic `search_query:` / `search_document:` task prefixes.
Validated on 2026-09-24 against real API candidate ranks for QUALITY-002/006 at
700/100 and 300/60.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import re
import urllib.request
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
DATASETS = ("answer_quality_v1", "golden_v1", "retrieval_stress_v1")
STRESS_DOCS = ["device-installation.md", "support-sla.md", "release-notes-v2.md", "long-ops-manual.md"]
HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
TOP_K = 5


def load_fixture_docs() -> dict[str, str]:
    spec = importlib.util.spec_from_file_location("fixture", ROOT / "evaluation" / "prepare_api_fixture.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return {"sample_faq.md": (ROOT / "sample_faq.md").read_text(encoding="utf-8"), **module.FIXTURE_DOCS}


def visibility(docs: dict[str, str]) -> dict[str, list[str]]:
    # Mirrors prepare_api_fixture.py grants: FAQ for every non-admin actor,
    # stress documents for employee/auditor, everything for the admin.
    return {
        "demo.employee": ["sample_faq.md", *STRESS_DOCS],
        "demo.auditor": ["sample_faq.md", *STRESS_DOCS],
        "demo.outsider": ["sample_faq.md"],
        "demo.admin": list(docs),
    }


def pack(text: str, size: int, overlap: int) -> list[str]:
    """Replay of TextChunker.splitSection for a single parsed section."""
    size = max(100, size)
    overlap = max(0, min(overlap, size // 2))
    result: list[str] = []
    current = ""
    for paragraph in re.split(r"\n\s*\n", text.strip()):
        trimmed = paragraph.strip()
        if not trimmed:
            continue
        if len(trimmed) > size:
            if current:
                result.append(current)
                current = ""
            start = 0
            while start < len(trimmed):
                end = min(start + size, len(trimmed))
                result.append(trimmed[start:end].strip())
                if end == len(trimmed):
                    break
                start = max(0, end - overlap)
            continue
        if current and len(current) + len(trimmed) + 2 > size:
            result.append(current)
            current = ""
        current = f"{current}\n\n{trimmed}" if current else trimmed
    if current:
        result.append(current)
    return result


def markdown_sections(text: str) -> list[tuple[list[str], str]]:
    """Split at headings; a heading with no body is carried into the next section."""
    sections: list[tuple[list[str], str]] = []
    lines: list[str] = []
    path: list[tuple[int, str]] = []
    current_path: list[str] = []
    for line in text.strip().split("\n"):
        match = HEADING.match(line)
        if match:
            if any(item.strip() and not HEADING.match(item) for item in lines):
                sections.append((list(current_path), "\n".join(lines).strip()))
                lines = []
            level = len(match.group(1))
            path = [item for item in path if item[0] < level] + [(level, match.group(2))]
            current_path = [item[1] for item in path]
        lines.append(line)
    if any(item.strip() for item in lines):
        sections.append((list(current_path), "\n".join(lines).strip()))
    return sections


def heading_chunks(text: str, size: int, overlap: int, with_path: bool) -> list[str]:
    chunks: list[str] = []
    for path, body in markdown_sections(text):
        pieces = pack(body, size, overlap) if len(body) > size else [body]
        for piece in pieces:
            if with_path and len(path) > 1:
                piece = " > ".join(path[:-1]) + "\n" + piece
            chunks.append(piece)
    return chunks


STRATEGIES: dict[str, Callable[[str], list[str]]] = {
    "S700": lambda text: pack(text, 700, 100),
    "S300": lambda text: pack(text, 300, 60),
    "H": lambda text: heading_chunks(text, 700, 100, False),
    "HP": lambda text: heading_chunks(text, 700, 100, True),
}


class Embedder:
    def __init__(self, base_url: str, model: str, prefix: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.prefix = prefix
        self.cache: dict[str, list[float]] = {}

    def embed(self, texts: list[str]) -> list[list[float]]:
        missing = [text for text in dict.fromkeys(texts) if text not in self.cache]
        for start in range(0, len(missing), 16):
            batch = missing[start:start + 16]
            body = json.dumps({"model": self.model, "input": [self.prefix + text for text in batch]}).encode("utf-8")
            request = urllib.request.Request(
                f"{self.base_url}/embeddings", body, {"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                data = json.load(response)["data"]
            for text, item in zip(batch, data):
                self.cache[text] = item["embedding"]
        return [self.cache[text] for text in texts]


def cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right))
    return dot / math.sqrt(sum(a * a for a in left) * sum(b * b for b in right))


def normalize(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def has_point(chunk: str, point: dict) -> bool:
    return any(normalize(term) in normalize(chunk) for term in point["match_any"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://127.0.0.1:1234/v1")
    parser.add_argument("--embedding-model", default="text-embedding-nomic-embed-text-v1.5")
    parser.add_argument("--prefix", action="store_true", help="add nomic search_query/search_document task prefixes")
    args = parser.parse_args()

    docs = load_fixture_docs()
    visible = visibility(docs)
    cases = [
        json.loads(line)
        for name in DATASETS
        for line in (ROOT / "evaluation" / "datasets" / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    answerable = [case for case in cases if case["expected_behavior"] == "ANSWER"]
    queries = Embedder(args.base_url, args.embedding_model, "search_query: " if args.prefix else "")
    documents = Embedder(args.base_url, args.embedding_model, "search_document: " if args.prefix else "")
    question_vectors = dict(zip([c["question"] for c in answerable], queries.embed([c["question"] for c in answerable])))

    results: dict[str, dict[str, tuple[float, int, int | None]]] = {}
    print("chunk counts")
    for name, strategy in STRATEGIES.items():
        chunks = [(doc, text) for doc, content in docs.items() for text in strategy(content)]
        chunk_vectors = dict(zip([text for _, text in chunks], documents.embed([text for _, text in chunks])))
        print(f"  {name}", {doc: sum(1 for item in chunks if item[0] == doc) for doc in docs})
        per_case: dict[str, tuple[float, int, int | None]] = {}
        for case in answerable:
            pool = [item for item in chunks if item[0] in visible[case["acting_user"]["id"]]]
            vector = question_vectors[case["question"]]
            ranked = sorted(pool, key=lambda item: -cosine(vector, chunk_vectors[item[1]]))
            expected = set(case["expected_source_documents"])
            points = case["expected_answer_points"]

            def is_evidence(item: tuple[str, str]) -> bool:
                return item[0] in expected and any(has_point(item[1], point) for point in points)

            top = ranked[:TOP_K]
            coverage = sum(
                1 for point in points if any(doc in expected and has_point(text, point) for doc, text in top)
            ) / len(points)
            non_evidence = sum(1 for item in top if not is_evidence(item))
            first = next((rank for rank, item in enumerate(ranked, 1) if is_evidence(item)), None)
            per_case[case["id"]] = (coverage, non_evidence, first)
        results[name] = per_case

    for prefix in ("QUALITY", "RAG", "STRESS"):
        ids = [case["id"] for case in answerable if case["id"].startswith(prefix)]
        print(f"== {prefix} ({len(ids)} answerable)")
        for name, per_case in results.items():
            full = sum(1 for case_id in ids if per_case[case_id][0] == 1)
            coverage = sum(per_case[case_id][0] for case_id in ids) / len(ids)
            noise = sum(per_case[case_id][1] for case_id in ids) / len(ids)
            print(f"  {name:5} full-evidence@{TOP_K} {full}/{len(ids)}  point-cov {coverage:.1%}  "
                  f"non-evidence chunks/Top{TOP_K} {noise:.2f}")
    print(f"== first evidence rank ({' '.join(results)}), cases where coverage differs")
    for case in answerable:
        coverages = [round(results[name][case["id"]][0], 2) for name in results]
        if len(set(coverages)) > 1:
            ranks = [results[name][case["id"]][2] for name in results]
            print(f"  {case['id']} rank {ranks} cov@{TOP_K} {coverages}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
