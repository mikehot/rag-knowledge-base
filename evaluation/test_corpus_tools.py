import json
import unittest
from pathlib import Path

from corpus_tools import clean_k8s_markdown, corpus_documents

DATASETS = [Path("evaluation/datasets/corpus_v1.jsonl"), Path("evaluation/datasets/corpus_v2.jsonl")]


def norm(text: str) -> str:
    return "".join(text.split())


class CleanK8sMarkdownTests(unittest.TestCase):
    def test_removes_markup_but_keeps_chinese_wording(self):
        raw = (
            '---\ntitle: Pod 的生命周期\ncontent_type: concept\n---\n'
            '<!-- overview -->\n'
            '<!--\nThis page describes the lifecycle of a Pod.\n-->\n'
            '本页面讲述 Pod 的生命周期。\n\n'
            '{{< note >}}\n注意事项\n{{< /note >}}\n\n'
            '## 阶段 {#pod-phase}\n\n'
            '参见[探针](/zh-cn/docs/concepts/probes/)与{{< glossary_tooltip text="kubelet" term_id="kubelet" >}}。\n'
        )

        cleaned = clean_k8s_markdown(raw)

        self.assertTrue(cleaned.startswith("# Pod 的生命周期\n"))
        self.assertIn("本页面讲述 Pod 的生命周期。", cleaned)
        self.assertIn("## 阶段\n", cleaned)
        self.assertIn("参见探针与kubelet。", cleaned)
        for leftover in ("<!--", "This page describes", "{{", "{#", "](/zh-cn"):
            self.assertNotIn(leftover, cleaned)


class CorpusDatasetGroundTruthTests(unittest.TestCase):
    def test_every_expected_answer_point_is_literally_supported_by_its_sources(self):
        documents = {d["name"]: norm(d["text"]) for d in corpus_documents()}
        lines = [line for dataset in DATASETS for line in dataset.read_text(encoding="utf-8").splitlines()]
        for line in lines:
            case = json.loads(line)
            for name in case["expected_source_documents"] + case["forbidden_documents"]:
                self.assertIn(name, documents, f"{case['id']} references unknown document {name}")
            if case["expected_behavior"] != "ANSWER":
                continue
            text = "".join(documents[name] for name in case["expected_source_documents"])
            for point in case["expected_answer_points"]:
                self.assertTrue(
                    any(norm(term) in text for term in point["match_any"]),
                    f"{case['id']}:{point['id']} has no literal support in its sources",
                )

    def test_corpus_has_expected_shape(self):
        documents = corpus_documents()
        self.assertEqual(28, len(documents))
        self.assertEqual({"it-ops", "hr", "legal"}, {d["group"] for d in documents})
        self.assertGreater(sum(len(d["text"]) for d in documents), 200_000)


class CorpusV2ParaphraseTests(unittest.TestCase):
    def test_v2_only_changes_the_wording_of_each_v1_question(self):
        v1 = {json.loads(l)["id"]: json.loads(l) for l in DATASETS[0].read_text(encoding="utf-8").splitlines()}
        v2 = [json.loads(l) for l in DATASETS[1].read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(v1), len(v2))
        for case in v2:
            original = v1[case["source_case"]]
            self.assertNotEqual(original["question"], case["question"], case["id"])
            for field in ("expected_answer_points", "expected_source_documents", "forbidden_documents",
                          "acting_user", "expected_behavior", "quality_rules", "refusal_match_terms"):
                self.assertEqual(original[field], case[field], f"{case['id']}.{field}")
            self.assertEqual("corpus-v2", case["dataset_version"])
            self.assertIn(case["style"], {"colloquial", "scenario", "keywords", "abbreviation"})


if __name__ == "__main__":
    unittest.main()
