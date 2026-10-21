import unittest

import run_concurrency_baseline as rc


def row(latency, status=200, found=True, reason=None):
    return {"httpLatencyMs": latency, "statusCode": status, "found": found, "failureReason": reason}


class PercentileTest(unittest.TestCase):
    def test_empty_is_zero(self):
        self.assertEqual(rc.percentile([], 0.95), 0.0)

    def test_nearest_rank(self):
        values = [float(v) for v in range(1, 11)]
        self.assertEqual(rc.percentile(values, 0.50), 5.0)
        self.assertEqual(rc.percentile(values, 0.95), 10.0)
        self.assertEqual(rc.percentile(values, 0.10), 1.0)


class SummaryTest(unittest.TestCase):
    def test_counts_failures_and_found_rate(self):
        rows = [row(100), row(300), row(200, found=False, reason="RETRIEVAL_MISS"), row(50, status=429)]
        summary = rc.summarize_level(2, rows, 2.0)
        self.assertEqual(summary["requests"], 4)
        self.assertEqual(summary["httpStatusCounts"], {"200": 3, "429": 1})
        self.assertEqual(summary["failureReasons"], {"RETRIEVAL_MISS": 1})
        self.assertEqual(summary["foundRate"], round(2 / 3, 3))
        self.assertEqual(summary["latencyMsMax"], 300)
        self.assertEqual(summary["throughputPerMinute"], 120.0)

    def test_all_failed_does_not_divide_by_zero(self):
        summary = rc.summarize_level(1, [row(10, status=500)], 1.0)
        self.assertEqual(summary["foundRate"], 0.0)


if __name__ == "__main__":
    unittest.main()
