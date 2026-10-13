import unittest

import run_repeated_eval as rr

USER = {"id": "u", "department": "d", "role": "EMPLOYEE", "knowledge_base": "kb"}


def answer_case(cid, term="十个"):
    return {
        "dataset_version": "t-v1", "id": cid, "question": "q", "answerable": True,
        "expected_answer_points": [{"id": "p", "description": "d", "match_any": [term]}],
        "expected_source_documents": ["a.md"], "acting_user": USER, "forbidden_documents": [],
        "expected_behavior": "ANSWER", "refusal_match_terms": [],
        "quality_rules": {"min_point_coverage": 1.0, "require_grounded": True},
    }


def refuse_case(cid):
    return {
        "dataset_version": "t-v1", "id": cid, "question": "q", "answerable": False,
        "expected_answer_points": [], "expected_source_documents": [], "acting_user": USER,
        "forbidden_documents": [], "expected_behavior": "REFUSE", "refusal_match_terms": ["转人工"],
        "quality_rules": {"require_refusal_contract": True},
    }


def good_answer(cid, text="默认保留十个"):
    return {"id": cid, "answer": text, "found": True, "grounded": True,
            "sources": [{"filename": "a.md", "locator": "chunk#1"}], "tokenUsage": 100, "failureReason": None}


def good_refusal(cid):
    return {"id": cid, "answer": "未找到相关信息，建议转人工。", "found": False, "grounded": False,
            "sources": [], "tokenUsage": 0, "failureReason": "INSUFFICIENT_CONTEXT"}


def summary(label, answerable_per_run, runs_total=None, case_rates=None):
    runs = [{"answerable_ok": n, "answerable_total": 45, "refusals_ok": 2, "refusals_total": 2,
             "all_ok": n + 2, "quality_gate_ok": n, "quality_gate_total": 47,
             "acl_leakage": 0, "schema_failures": 0, "mean_tokens": 100.0,
             "per_case": {"c1": True, "c2": n > 5}} for n in answerable_per_run]
    agg = rr.aggregate(runs)
    if case_rates:
        agg["case_pass_rate"] = case_rates
    return {"label": label, "dataset_version": "t-v1", "shuffle": False, "seed": 1,
            "restart_before_each_run": False, "runs": runs, "aggregate": agg}


class ScoreRunTests(unittest.TestCase):
    def test_counts_answerable_and_refusal_cases_separately(self):
        cases = [answer_case("a1"), answer_case("a2"), refuse_case("r1")]
        responses = [good_answer("a1"), good_answer("a2", "没有这个数字"), good_refusal("r1")]

        scored = rr.score_run(cases, responses)

        self.assertEqual((1, 2), (scored["answerable_ok"], scored["answerable_total"]))
        self.assertEqual((1, 1), (scored["refusals_ok"], scored["refusals_total"]))
        self.assertEqual({"a1": True, "a2": False, "r1": True}, scored["per_case"])
        self.assertEqual(0, scored["acl_leakage"])
        self.assertEqual({"INSUFFICIENT_CONTEXT": 1}, scored["failure_reasons"])

    def test_a_capture_that_does_not_match_the_dataset_is_rejected(self):
        with self.assertRaises(ValueError):
            rr.score_run([answer_case("a1")], [good_answer("a1"), good_answer("zzz")])


class AggregateTests(unittest.TestCase):
    def test_reports_spread_and_flaky_cases(self):
        runs = [
            {"answerable_ok": 44, "refusals_ok": 12, "all_ok": 56, "quality_gate_ok": 56, "acl_leakage": 0,
             "schema_failures": 0, "mean_tokens": 2400.0, "per_case": {"x": True, "y": True, "z": False}},
            {"answerable_ok": 41, "refusals_ok": 12, "all_ok": 53, "quality_gate_ok": 53, "acl_leakage": 0,
             "schema_failures": 0, "mean_tokens": 2410.0, "per_case": {"x": True, "y": False, "z": False}},
            {"answerable_ok": 44, "refusals_ok": 12, "all_ok": 56, "quality_gate_ok": 56, "acl_leakage": 1,
             "schema_failures": 0, "mean_tokens": 2405.0, "per_case": {"x": True, "y": True, "z": False}},
        ]

        agg = rr.aggregate(runs)

        self.assertEqual(41, agg["answerable_ok"]["min"])
        self.assertEqual(44, agg["answerable_ok"]["max"])
        self.assertAlmostEqual(43.0, agg["answerable_ok"]["mean"])
        self.assertEqual(["y"], agg["flaky_cases"])
        self.assertEqual(["z"], agg["always_failing_cases"])
        self.assertEqual(1, agg["acl_leakage_max"])


class CompareTests(unittest.TestCase):
    def test_overlapping_ranges_are_within_variation_even_when_the_means_differ(self):
        verdict = rr.compare(summary("base", [44, 43, 41]), summary("cand", [45, 44, 43]))

        self.assertEqual("WITHIN VARIATION", verdict["verdict"])
        self.assertGreater(verdict["mean_difference_b_minus_a"], 0)

    def test_non_overlapping_ranges_are_distinguishable(self):
        verdict = rr.compare(summary("nomic", [25, 25, 24]), summary("bge", [44, 44, 41]))

        self.assertEqual("DISTINGUISHABLE", verdict["verdict"])
        self.assertIn("B is higher", verdict["reason"])

    def test_the_lower_side_can_be_the_distinguishable_one(self):
        verdict = rr.compare(summary("rrf", [18, 18, 19]), summary("vector", [25, 25, 24]))

        self.assertEqual("DISTINGUISHABLE", verdict["verdict"])
        verdict_swapped = rr.compare(summary("vector", [25, 25, 24]), summary("rrf", [18, 18, 19]))
        self.assertIn("A is higher", verdict_swapped["reason"])

    def test_fewer_than_three_runs_never_gives_a_verdict(self):
        verdict = rr.compare(summary("a", [10, 10]), summary("b", [30, 30]))

        self.assertEqual("INSUFFICIENT RUNS", verdict["verdict"])

    def test_lists_cases_that_are_clearly_better_on_one_side(self):
        a = summary("a", [10, 10, 10], case_rates={"c1": 1.0, "c2": 0.0, "c3": 0.5})
        b = summary("b", [10, 10, 10], case_rates={"c1": 1.0, "c2": 1.0, "c3": 0.5})

        verdict = rr.compare(a, b)

        self.assertEqual(["c2"], verdict["cases_clearly_better_in_b"])
        self.assertEqual([], verdict["cases_clearly_better_in_a"])


class OrderTests(unittest.TestCase):
    IDS = [f"c{i}" for i in range(20)]

    def test_fixed_order_is_the_dataset_order(self):
        self.assertEqual(self.IDS, rr.question_order(self.IDS, False, 1, 3))

    def test_shuffled_order_is_reproducible_and_varies_between_runs(self):
        first = rr.question_order(self.IDS, True, 7, 1)

        self.assertEqual(first, rr.question_order(self.IDS, True, 7, 1))
        self.assertNotEqual(first, rr.question_order(self.IDS, True, 7, 2))
        self.assertCountEqual(self.IDS, first)

    def test_input_list_is_not_mutated(self):
        ids = list(self.IDS)
        rr.question_order(ids, True, 1, 1)
        self.assertEqual(self.IDS, ids)


class RenderTests(unittest.TestCase):
    def test_markdown_states_the_range(self):
        text = rr.render_markdown(summary("baseline", [44, 41, 44]))
        self.assertIn("baseline", text)
        self.assertIn("range 41–44", text)


if __name__ == "__main__":
    unittest.main()
