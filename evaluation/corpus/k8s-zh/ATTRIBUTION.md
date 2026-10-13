# Attribution

These 24 files (`pods/` 15, `controllers/` 9) are verbatim copies of Kubernetes documentation, used as a realistic
Chinese technical corpus for evaluation. The files in this directory are **not modified**.

| Item | Value |
|---|---|
| Source | https://github.com/kubernetes/website, paths `content/zh-cn/docs/concepts/workloads/pods/` and `content/zh-cn/docs/concepts/workloads/controllers/` |
| Copyright | © The Kubernetes Authors; documentation © The Linux Foundation |
| License | Creative Commons Attribution 4.0 International (CC BY 4.0) — https://creativecommons.org/licenses/by/4.0/ |
| License text | https://raw.githubusercontent.com/kubernetes/website/main/LICENSE |
| Retrieved | 2026-10-13 from branch `main`; `main` was at commit `6d730bb15e1650ccfa389389edd8236b606a4d0a` when queried right after the download |
| Changes to these files | None. The Hugo front matter, shortcodes, and English source comments are left as published |
| Changes in the evaluation fixture | **Yes.** When uploaded for evaluation, `evaluation/corpus_tools.py::clean_k8s_markdown` removes the front matter (keeping the title), HTML comments (the English originals), Hugo shortcodes, heading anchors, and link targets. No Chinese wording is changed. The cleaned text is a derived work under the same CC BY 4.0 license, indicated as modified |

The Linux Foundation has registered trademarks and uses trademarks; see https://www.linuxfoundation.org/legal/trademark-usage.
No endorsement by the original authors is implied.
