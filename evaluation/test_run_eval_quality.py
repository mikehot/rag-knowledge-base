import unittest
from pathlib import Path

from run_eval import load_jsonl, score_response


class AnswerQualityRubricTests(unittest.TestCase):
    def test_quality_001_accepts_grounded_app_push_paraphrase(self):
        case = next(
            row for row in load_jsonl(Path("evaluation/datasets/answer_quality_v1.jsonl"))
            if row["id"] == "QUALITY-001"
        )
        response = {
            "answer": "1. 使用 4 节 5 号干电池。2. 续航约 8-10 个月。3. 低电量时通过 App 推送。",
            "found": True,
            "grounded": True,
            "sources": [{"filename": "sample_faq.md"}],
        }

        result = score_response(case, response)

        self.assertTrue(result["passed"], result["errors"])
        self.assertEqual(1.0, result["point_coverage"])
        self.assertTrue(result["quality_gate_pass"])


if __name__ == "__main__":
    unittest.main()
