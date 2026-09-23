import json
import tempfile
import unittest
from pathlib import Path

from run_chunk_evidence_diagnostic import (
    matched_point_ids,
    score_context_budgets,
    select_adjacent_preserving_documents,
    select_adjacent_chunk_window,
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

    def test_adjacent_window_uses_only_rank_and_locator_and_preserves_budget(self):
        hits = [
            {"rank": 1, "chunkId": "a1", "filename": "faq.md", "locator": "chunk#2"},
            {"rank": 2, "chunkId": "b1", "filename": "manual.md", "locator": "chunk#1"},
            {"rank": 3, "chunkId": "c1", "filename": "sla.md", "locator": "chunk#1"},
            {"rank": 4, "chunkId": "d1", "filename": "release.md", "locator": "chunk#1"},
            {"rank": 5, "chunkId": "e1", "filename": "ops.md", "locator": "chunk#1"},
            {"rank": 6, "chunkId": "a2", "filename": "faq.md", "locator": "chunk#1"},
        ]

        selected = select_adjacent_chunk_window(hits, context_k=5)

        self.assertEqual([1, 2, 3, 4, 6], [hit["rank"] for hit in selected])
        self.assertEqual(5, len(selected))
        self.assertEqual("a2", selected[-1]["chunkId"])

    def test_adjacent_window_does_not_expand_past_rank_window_or_non_chunk_locator(self):
        hits = [
            {"rank": 1, "chunkId": "a1", "filename": "faq.md", "locator": "Section A"},
            {"rank": 2, "chunkId": "b1", "filename": "manual.md", "locator": "chunk#1"},
            {"rank": 3, "chunkId": "c1", "filename": "sla.md", "locator": "chunk#1"},
            {"rank": 4, "chunkId": "d1", "filename": "release.md", "locator": "chunk#1"},
            {"rank": 5, "chunkId": "e1", "filename": "ops.md", "locator": "chunk#1"},
            {"rank": 8, "chunkId": "a2", "filename": "faq.md", "locator": "chunk#2"},
        ]

        self.assertEqual(hits[:5], select_adjacent_chunk_window(hits, context_k=5))

    def test_adjacent_preserving_selector_replaces_only_redundant_same_document_hit(self):
        hits = [
            {"rank": 1, "chunkId": "faq-2", "documentId": "faq-doc", "filename": "faq.md", "locator": "chunk#2"},
            {"rank": 2, "chunkId": "faq-4", "documentId": "faq-doc", "filename": "faq.md", "locator": "chunk#4"},
            {"rank": 3, "chunkId": "manual", "documentId": "manual-doc", "filename": "manual.md", "locator": "chunk#1"},
            {"rank": 4, "chunkId": "sla", "documentId": "sla-doc", "filename": "sla.md", "locator": "chunk#1"},
            {"rank": 5, "chunkId": "release", "documentId": "release-doc", "filename": "release.md", "locator": "chunk#1"},
            {"rank": 6, "chunkId": "faq-3", "documentId": "faq-doc", "filename": "faq.md", "locator": "chunk#3"},
        ]

        selected = select_adjacent_preserving_documents(hits, context_k=5)

        self.assertEqual([1, 3, 4, 5, 6], [hit["rank"] for hit in selected])
        self.assertEqual({hit["documentId"] for hit in hits[:5]}, {hit["documentId"] for hit in selected})
        self.assertEqual(5, len(selected))

    def test_adjacent_preserving_selector_does_not_evict_a_unique_document(self):
        hits = [
            {"rank": 1, "chunkId": "faq-2", "documentId": "faq-doc", "filename": "faq.md", "locator": "chunk#2"},
            {"rank": 2, "chunkId": "manual", "documentId": "manual-doc", "filename": "manual.md", "locator": "chunk#1"},
            {"rank": 3, "chunkId": "sla", "documentId": "sla-doc", "filename": "sla.md", "locator": "chunk#1"},
            {"rank": 4, "chunkId": "release", "documentId": "release-doc", "filename": "release.md", "locator": "chunk#1"},
            {"rank": 5, "chunkId": "ops", "documentId": "ops-doc", "filename": "ops.md", "locator": "chunk#1"},
            {"rank": 6, "chunkId": "faq-1", "documentId": "faq-doc", "filename": "faq.md", "locator": "chunk#1"},
        ]

        selected = select_adjacent_preserving_documents(hits, context_k=5)

        self.assertEqual(hits[:5], selected)


if __name__ == "__main__":
    unittest.main()
