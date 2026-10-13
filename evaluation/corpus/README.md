# Evaluation corpus

A realistic Chinese corpus for `datasets/corpus_v1.jsonl`. It replaces the 7-document synthetic fixture for
retrieval work, because on that fixture every retrieval variant was inside run-to-run noise.

| Directory | Documents | Group (ACL) | License |
|---|---|---|---|
| `k8s-zh/pods/` | 15 Kubernetes Pod concept pages | `it-ops` | CC BY 4.0 — see `k8s-zh/ATTRIBUTION.md` |
| `k8s-zh/controllers/` | 9 Kubernetes workload-controller pages | `it-ops` | CC BY 4.0 — see `k8s-zh/ATTRIBUTION.md` |
| `laws-zh/` | 劳动合同法、劳动法 | `hr` | outside copyright (Copyright Law, Art. 5) — see `laws-zh/ATTRIBUTION.md` |
| `laws-zh/` | 个人信息保护法、数据安全法 | `legal` | same |

28 documents, about 245k characters after cleaning, about 419 chunks at 700/100.

## Access groups used by the fixture

| Actor | Sees |
|---|---|
| `corp.sre` | it-ops |
| `corp.hr` | hr |
| `corp.legal` | legal |
| `corp.manager` | all three |
| `corp.outsider` | nothing |
| `corp.admin` | SYSTEM_ADMIN, only for provisioning and protected diagnostics |

## Rebuild and provision

```bash
python3 evaluation/corpus/build_laws.py          # re-extract the statutes (needs network and pdftotext)
python3 evaluation/prepare_corpus_fixture.py \
  --admin-credentials /private/tmp/rag-eval-admin.json \
  --credentials /private/tmp/rag-corp-actors.json \
  --manifest /private/tmp/rag-corpus-manifest.json
python3 evaluation/run_api_eval.py --dataset evaluation/datasets/corpus_v1.jsonl \
  --credentials /private/tmp/rag-corp-actors.json --output /private/tmp/corpus-v1-responses.jsonl
```

Use a disposable database. The Kubernetes pages are cleaned at upload time by `corpus_tools.py`; the
ground truth in `corpus_v1.jsonl` was written against that cleaned text, and every expected answer point
is checked to occur literally in its source document.
