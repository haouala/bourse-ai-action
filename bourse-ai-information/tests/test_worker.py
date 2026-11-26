import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from bourse_ai_information.worker import Worker, Cache, Backend
from bourse_ai_information.http import FetchError
from bourse_ai_information.cli import main
from test_ai import analysis, TEXT, ASSETS


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.cache = Cache(Path(self.tmp.name) / "cache.sqlite")
        self.job = {"status": "RUNNING", "jobId": 1, "leaseToken": "demo", "model": "DEMO", "promptVersion": "information-v1", "item": {"title": "Demo", "summary": TEXT, "original_url": "https://example.org/a"}, "content": None, "assets": ASSETS}
        self.backend = Mock(); self.calls = []
        def call(path, value=None, **kwargs):
            self.calls.append((path, value, kwargs))
            if path == "/claim": return copy.deepcopy(self.job)
            if path.endswith("/content"): return {"id": 1, "content_hash": "demo-hash"}
            if path.endswith("/complete"): return {"analysisId": 1}
            return {}
        self.backend.call.side_effect = call
        self.fetcher = Mock(); self.fetcher.get.side_effect = FetchError("HTTP_403")
        self.ai = Mock(); self.ai.analyze.return_value = analysis()

    def worker(self): return Worker(self.backend, self.fetcher, self.ai, self.cache)

    def test_cycle_collects_due_sources_before_bounded_analysis(self):
        worker = self.worker()
        with patch.object(worker, "once", side_effect=[{"status":"SUCCEEDED"}, {"status":"IDLE"}]) as once:
            self.backend.call.side_effect = [
                {"sources":["NEWS","VIDEO"]},
                {"source":"NEWS","status":"SUCCEEDED"},
                FetchError("BACKEND_HTTP_429"),
            ]
            result = worker.cycle(3)
        self.assertEqual(result["status"], "PARTIAL")
        self.assertEqual(result["analyses"], [{"status":"SUCCEEDED"}])
        self.assertEqual([item.args[0] for item in self.backend.call.call_args_list],
                         ["/sources/due", "/sources/NEWS/sync", "/sources/VIDEO/sync"])
        self.assertEqual(once.call_count, 2)

    def test_summary_and_structured_evidence_reach_backend(self):
        self.assertEqual(self.worker().once()["status"], "SUCCEEDED")
        content = next(c[1] for c in self.calls if c[0].endswith("/content"))
        self.assertEqual(content["scope"], "SUMMARY_ONLY"); self.assertEqual(content["error"], "HTTP_403")
        complete = next(c[1] for c in self.calls if c[0].endswith("/complete"))
        self.assertEqual(complete["analysis"]["summary"], analysis()["summary"])
        self.assertEqual(complete["analysis"]["events"][0]["quote"], TEXT)

    def test_retry_uses_persisted_content_and_cached_ai(self):
        self.worker().once(); self.job["content"] = {"body_text": "Demo\n" + TEXT, "content_hash": "demo-hash"}
        self.worker().once(); self.assertEqual(self.ai.analyze.call_count, 1); self.assertEqual(self.fetcher.get.call_count, 1)

    def test_idle_does_not_call_sources_or_ai(self):
        self.job = {"status": "IDLE"}; self.assertEqual(self.worker().once(), {"status": "IDLE"})
        self.ai.analyze.assert_not_called(); self.fetcher.get.assert_not_called()

    def test_budget_exhaustion_defers_without_invented_score(self):
        self.ai.analyze.side_effect = FetchError("DAILY_BUDGET_EXHAUSTED")
        self.assertEqual(self.worker().once()["status"], "FAILED")
        self.assertTrue(next(c[1] for c in self.calls if c[0].endswith("/failure"))["retryable"])
        self.assertFalse(any(c[0].endswith("/complete") for c in self.calls))

    def test_unknown_prompt_is_rejected_before_ai(self):
        self.job["promptVersion"] = "future"; self.assertEqual(self.worker().once()["error"], "PROMPT_VERSION_MISMATCH")
        self.ai.analyze.assert_not_called()

    def test_backend_credentials_only_over_tls_or_loopback(self):
        for url in ["http://example.org", "https://user:pass@example.org", "https://example.org?secret=x"]:
            with self.assertRaises(FetchError): Backend(url, "x" * 32)
        Backend("http://127.0.0.1:8081", "x" * 32)

    def test_disabled_by_default(self):
        with patch.dict("os.environ", {}, clear=True), patch("builtins.print") as output:
            self.assertEqual(main(["once"]), 2); self.assertIn("OPENAI_DISABLED", output.call_args.args[0])

    def test_unicode_coverage_uses_java_utf16_units(self):
        text = "😀\n" + TEXT
        self.job["content"] = {"body_text": text, "content_hash": "demo-hash"}
        self.assertEqual(self.worker().once()["status"], "SUCCEEDED")
        complete = next(c[1] for c in self.calls if c[0].endswith("/complete"))
        self.assertEqual(complete["analyzedCharacters"], len(text) + 1)


if __name__ == "__main__": unittest.main()
