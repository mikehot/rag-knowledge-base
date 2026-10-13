"""Corpus helpers shared by the corpus fixture builder and the dataset validator.

The Kubernetes zh-cn sources are Hugo files: each translated paragraph is accompanied by its English
original inside an HTML comment, and the pages use front matter and shortcodes. A reader never sees
those, so ingesting them verbatim would double the corpus with English noise. ``clean_k8s_markdown``
produces the text a reader sees. It removes markup only; no Chinese wording is changed.

The cleaning is applied at upload time. The raw files stay untouched in ``corpus/k8s-zh`` so the
attribution (unmodified copies, CC BY 4.0) remains true, and the cleaned text is the ground truth for
``datasets/corpus_v1.jsonl``.
"""

from __future__ import annotations

import re
from pathlib import Path

CORPUS_ROOT = Path(__file__).resolve().parent / "corpus"

_FRONT_MATTER = re.compile(r"\A---\n(.*?)\n---\n", re.S)
_COMMENT = re.compile(r"<!--.*?-->", re.S)
_ANCHOR = re.compile(r"\s*\{#[^}]*\}")
_GLOSSARY = re.compile(r'\{\{<\s*glossary_tooltip[^>]*?text="([^"]*)"[^>]*>\}\}')
_SHORTCODE = re.compile(r"\{\{[<%].*?[>%]\}\}", re.S)
_LINK = re.compile(r"\[([^\]]+)\]\((?:[^()]|\([^)]*\))*\)")
_BLANKS = re.compile(r"\n{3,}")


def clean_k8s_markdown(raw: str) -> str:
    title = ""
    match = _FRONT_MATTER.match(raw)
    if match:
        title_line = re.search(r'^title:\s*(.+)$', match.group(1), re.M)
        if title_line:
            title = title_line.group(1).strip().strip('"\'')
        raw = raw[match.end():]
    text = _COMMENT.sub("", raw)
    text = _GLOSSARY.sub(r"\1", text)
    text = _SHORTCODE.sub("", text)
    text = _ANCHOR.sub("", text)
    text = _LINK.sub(r"\1", text)
    text = "\n".join(line.rstrip() for line in text.splitlines())
    text = _BLANKS.sub("\n\n", text).strip()
    return (f"# {title}\n\n" if title else "") + text + "\n"


def corpus_documents() -> list[dict[str, str]]:
    """Every corpus document as {name, group, text}, cleaned where a cleaner applies."""
    documents: list[dict[str, str]] = []
    for sub in ("pods", "controllers"):
        for path in sorted((CORPUS_ROOT / "k8s-zh" / sub).glob("*.md")):
            documents.append({
                "name": f"k8s-{sub}-{path.stem.lstrip('_')}.md",
                "group": "it-ops",
                "text": clean_k8s_markdown(path.read_text(encoding="utf-8")),
            })
    law_groups = {
        "labor-contract-law.md": "hr",
        "labor-law.md": "hr",
        "personal-information-protection-law.md": "legal",
        "data-security-law.md": "legal",
    }
    for path in sorted((CORPUS_ROOT / "laws-zh").glob("*.md")):
        if path.name in law_groups:
            documents.append({"name": path.name, "group": law_groups[path.name], "text": path.read_text(encoding="utf-8")})
    return documents
