import json
import tempfile
import unittest
from pathlib import Path

from run_chunk_evidence_diagnostic import (
    matched_point_ids,
    score_context_budgets,
    validate_vector_diagnostics,
)


class ChunkEvidenceDiagnosticTests(unittest.TestCase):
    def test_matches_normalized_alternatives_without_returning_text(self):
        case = {
            "expected_answer_points": [
                {"id": "battery", "match_any": ["4 节 5 号", "4节5号"]},
                {"id": "alert", "match_any": ["App 推送提醒"]},
            ]
        }

        matched = matched_point_ids(case, "采用４节５号电池，低电量由 APP 推送提醒。")

        self.assertEqual({"battery", "alert"}, matched)

    def test_scores_bounded_context_budgets(self):
        case = {
            "expected_answer_points": [
                {"id": "battery", "match_any": ["4节5号"]},
                {"id": "alert", "match_any": ["App推送"]},
            ]
        }
        hits = [
            {"rank": 1, "chunkId": "a", "filename": "faq.md"},
            {"rank": 2, "chunkId": "b", "filename": "manual.md"},
            {"rank": 3, "chunkId": "c", "filename": "faq.md"},
        ]

        result = score_context_budgets(
            case,
            hits,
            {"a": {"battery"}, "b": set(), "c": {"alert"}},
            {"b": {"alert"}},
            (1, 2, 3),
        )

        self.assertEqual([1, 2, 3], [row["contextK"] for row in result["contextBudgets"]])
        # One point in Top-1, both points only when the third chunk is added.
        self.assertEqual(0.5, result["contextBudgets"][0]["pointCoverage"])
        self.assertFalse(result["contextBudgets"][1]["allExpectedPointsCovered"])
        self.assertTrue(result["contextBudgets"][2]["allExpectedPointsCovered"])
        self.assertEqual(1, result["contextBudgets"][2]["nonEvidenceChunkCount"])
        self.assertEqual(["alert"], result["contextBudgets"][1]["offSourceMatchedPointIds"])
        self.assertEqual({"battery": [1], "alert": [3]}, result["pointRanks"])

    def test_rejects_missing_or_duplicate_chunk_ids(self):
        for hits in (
            [{"rank": 1, "filename": "faq.md"}],
            [
                {"rank": 1, "chunkId": "same", "filename": "faq.md"},
                {"rank": 2, "chunkId": "same", "filename": "faq.md"},
            ],
        ):
            with self.subTest(hits=hits), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "diagnostic.json"
                path.write_text(json.dumps({"results": [{"id": "case", "hits": hits}]}), encoding="utf-8")
                with self.assertRaises(ValueError):
                    validate_vector_diagnostics(path)

    def test_rejects_rank_order_gaps(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "diagnostic.json"
            path.write_text(
                json.dumps({"results": [{"id": "case", "hits": [{"rank": 2, "chunkId": "a", "filename": "faq.md"}]}]}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "contiguous"):
                validate_vector_diagnostics(path)


if __name__ == "__main__":
    unittest.main()
