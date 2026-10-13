#!/usr/bin/env python3
"""Rebuild corpus/laws-zh from the official pages listed below.

Laws and regulations are outside Chinese copyright protection (Copyright Law, Art. 5), so the
extracted text is redistributable. The script keeps the statutory wording unchanged: it only
removes page chrome (navigation, print links, page numbers) and the table of contents, and writes
one Markdown file per law with its source recorded in the header.

    python3 evaluation/corpus/build_laws.py [--source-dir DIR]

With --source-dir the already downloaded pages are read from DIR instead of the network.
"""

from __future__ import annotations

import argparse
import html
import re
import subprocess
import sys
import urllib.request
from datetime import date
from pathlib import Path

OUT = Path(__file__).resolve().parent / "laws-zh"
ARTICLE = re.compile(r"^第[一二三四五六七八九十百零]+条")
CHAPTER = re.compile(r"^第[一二三四五六七八九十百零]+[章节]")
BOILERPLATE = ("您的浏览器不支持", "【打印】", "【下载】", "发布时间", "相关链接", "分享", "收藏", "责任编辑", "纠错或建议", "关联解读", "打印", "-->")

LAWS = [
    {
        "slug": "labor-contract-law",
        "title": "中华人民共和国劳动合同法",
        "articles": 98,
        "url": "https://fgk.chinatax.gov.cn/zcfgk/c100009/c5193025/content.html",
        "host": "国家税务总局政策法规库",
        "kind": "html",
        "file": "laodonghetong.html",
    },
    {
        "slug": "labor-law",
        "title": "中华人民共和国劳动法",
        "articles": 107,
        "url": "https://www.12371.cn/2020/06/18/ARTI1592415044828445.shtml",
        "host": "共产党员网（转载中国人大网）",
        "kind": "html",
        "file": "laodongfa.html",
    },
    {
        "slug": "personal-information-protection-law",
        "title": "中华人民共和国个人信息保护法",
        "articles": 74,
        "url": "https://www.stats.gov.cn/gk/tjfg/xgfxfg/202503/t20250310_1958923.html",
        "host": "国家统计局",
        "kind": "html",
        "file": "gerenxinxi.html",
    },
    {
        "slug": "data-security-law",
        "title": "中华人民共和国数据安全法",
        "articles": 55,
        "url": "https://fgw.sh.gov.cn/cmsres/7e/7e405f41fe814fe98ed4a0922f2866f6/31262692f1a9d65c7cdcbe566f0574bf.pdf",
        "host": "上海市发展和改革委员会（PDF）",
        "kind": "pdf",
        "file": "dsl-sh.pdf",
    },
]


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def html_lines(raw: bytes) -> list[str]:
    text = raw.decode("utf-8", "ignore")
    text = re.sub(r"<script.*?</script>|<style.*?</style>", "", text, flags=re.S)
    text = re.sub(r"</(p|div|li|h\d|tr)>|<br\s*/?>", "\n", text)
    text = html.unescape(re.sub(r"<[^>]+>", "", text))
    return [line.strip() for line in text.splitlines() if line.strip()]


def cn_number(n: int) -> str:
    digits = "零一二三四五六七八九"
    if n < 10:
        return digits[n]
    if n < 20:
        return "十" + (digits[n - 10] if n > 10 else "")
    if n < 100:
        tens, ones = divmod(n, 10)
        return digits[tens] + "十" + (digits[ones] if ones else "")
    hundreds, rest = divmod(n, 100)
    if rest == 0:
        return digits[hundreds] + "百"
    if rest < 10:
        return digits[hundreds] + "百零" + digits[rest]
    return digits[hundreds] + "百" + cn_number(rest)


def pdf_lines(raw: bytes) -> list[str]:
    """Reflow a layout-mode PDF text dump into paragraphs (whitespace-only normalisation)."""
    result = subprocess.run(["pdftotext", "-layout", "-", "-"], input=raw, capture_output=True, check=True)
    paragraphs: list[str] = []
    for raw_line in result.stdout.decode("utf-8", "ignore").splitlines():
        text = raw_line.strip()
        if not text or re.fullmatch(r"[—\-– ]*\d+[—\-– ]*", text):
            continue
        indent = len(raw_line) - len(raw_line.lstrip())
        open_note = bool(paragraphs) and paragraphs[-1].startswith("（") and not paragraphs[-1].endswith("）")
        if paragraphs and (open_note or indent < 3):
            paragraphs[-1] += text
        else:
            paragraphs.append(text)
    cleaned: list[str] = []
    for paragraph in paragraphs:
        # headings keep a full-width gap; other ASCII gaps between CJK/digits are layout artefacts
        paragraph = re.sub(r"(第[一二三四五六七八九十百零]+[条章节])[ \t]+", "\\1\u3000", paragraph)
        paragraph = re.sub(r"(?<=[\u4e00-\u9fff0-9）（])[ \t]+(?=[\u4e00-\u9fff（0-9])", "", paragraph)
        # page breaks can glue several articles into one paragraph; start a new one at each heading
        parts = re.split(r"(?=第[一二三四五六七八九十百零]+条\u3000)", paragraph)
        cleaned.extend(part for part in parts if part)
    return cleaned


def law_body(lines: list[str], last_article: int) -> tuple[str, list[str]]:
    first_article = next(i for i, line in enumerate(lines) if ARTICLE.match(line) and "第一条" in line)
    start = first_article
    while start > 0 and not CHAPTER.match(lines[start - 1]):
        start -= 1
    if start > 0 and CHAPTER.match(lines[start - 1]):
        start -= 1
    # the table of contents lists the chapter headings once more before the body
    note = ""
    for line in reversed(lines[:start]):
        if re.match(r"^（\d{4}年", line) and "通过" in line:
            note = line
            break
    last_heading = f"第{cn_number(last_article)}条"
    # Some pages repeat the whole law; stop at the first occurrence of the final article.
    end = next(i for i in range(start, len(lines)) if lines[i].startswith(last_heading))
    stop = len(lines)
    for i in range(end + 1, len(lines)):
        if lines[i].startswith(BOILERPLATE):
            stop = i
            break
    return note, lines[start:stop]


def render(law: dict, note: str, body: list[str]) -> str:
    header = [
        f"# {law['title']}",
        "",
        f"> 来源：{law['host']}，{law['url']}",
        f"> 取得日期：{date.today().isoformat()}。正文未改动；仅去除了页面导航、目录和页码。",
        "> 依据《中华人民共和国著作权法》第五条，法律法规不适用该法，可再分发。",
    ]
    if note:
        header += ["", note]
    return "\n".join(header + [""] + [f"{line}\n" for line in body]).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    for law in LAWS:
        raw = (args.source_dir / law["file"]).read_bytes() if args.source_dir else fetch(law["url"])
        lines = html_lines(raw) if law["kind"] == "html" else pdf_lines(raw)
        note, body = law_body(lines, law["articles"])
        articles = sum(1 for line in body if ARTICLE.match(line))
        if articles != law["articles"]:
            print(f"{law['slug']}: expected {law['articles']} articles, found {articles}", file=sys.stderr)
            return 1
        text = render(law, note, body)
        (OUT / f"{law['slug']}.md").write_text(text, encoding="utf-8")
        print(f"{law['slug']}.md: {articles} article lines, {len(text)} chars")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
