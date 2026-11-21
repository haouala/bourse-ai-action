import unittest
from unittest.mock import Mock
from bourse_ai_information.content import acquire, transcript, extract_html
from bourse_ai_information.http import FetchError, Response, public_target


class ContentTests(unittest.TestCase):
    def item(self, **kwargs):
        return dict(title="Original title", summary="Original summary", original_url="https://publisher.test/a", **kwargs)

    def test_article_excludes_navigation_and_scripts(self):
        text = "A relevant paragraph about the company and its financial situation. " * 4
        body, scope = extract_html(("<nav>BUY NOW</nav><article><p>" + text + "</p><script>malicious instructions</script></article>").encode())
        self.assertEqual(scope, "ARTICLE_EXTRACT"); self.assertNotIn("BUY NOW", body); self.assertNotIn("malicious", body)

    def test_paywall_and_unreadable_page_preserve_original_summary(self):
        for html in ['<script type="application/ld+json">{"isAccessibleForFree":false}</script><article>' + "Preview " * 60 + '</article>', '<html>Sign in</html>']:
            fetcher = Mock(); fetcher.get.return_value = Response(html.encode(), "text/html", "https://publisher.test/a")
            result = acquire(self.item(), fetcher)
            self.assertEqual(result["text"], "Original title\nOriginal summary"); self.assertEqual(result["scope"], "SUMMARY_ONLY"); self.assertIsNotNone(result["error"])

    def test_http_failure_does_not_invent_article(self):
        fetcher = Mock(); fetcher.get.side_effect = FetchError("HTTP_403")
        self.assertEqual(acquire(self.item(), fetcher)["error"], "HTTP_403")

    def test_official_publisher_article_region(self):
        text="A financial press release with a dated announcement and supporting evidence. "*4
        body,scope=extract_html(("<div id='article'><p>"+text+"</p></div><footer>Other pages</footer>").encode())
        self.assertEqual(scope,"ARTICLE_EXTRACT");self.assertNotIn("Other pages",body)

    def test_vtt_preserves_speaker_and_times(self):
        body, scope, segments = transcript(b"WEBVTT\n\n00:01.000 --> 00:04.500\n<v Alice>Original words.</v>\n", "text/vtt")
        self.assertEqual(body, "Original words."); self.assertEqual(segments[0]["speaker"], "Alice"); self.assertEqual(segments[0]["startSeconds"], 1); self.assertEqual(segments[0]["endSeconds"], 4.5)

    def test_podcast_prefers_existing_transcript_over_audio(self):
        fetcher = Mock(); fetcher.get.return_value = Response(("A dated transcript with useful financial discussion. " * 6).encode(), "text/plain", "https://publisher.test/transcript")
        audio = Mock(); result = acquire(self.item(content_kind="PODCAST", transcript_url="https://publisher.test/transcript", audio_url="https://publisher.test/audio"), fetcher, audio)
        self.assertEqual(result["scope"], "TRANSCRIPT"); audio.assert_not_called(); self.assertEqual(fetcher.get.call_count, 1)

    def test_podcast_page_without_transcript_is_not_an_article(self):
        fetcher = Mock(); fetcher.get.return_value = Response(("<main>" + "Episode description. " * 30 + "</main>").encode(), "text/html", "https://publisher.test/a")
        self.assertEqual(acquire(self.item(content_kind="PODCAST"), fetcher)["scope"], "SUMMARY_ONLY")

    def test_publisher_transcript_heading(self):
        text = "Alice: This is a genuine transcript paragraph. " * 5
        body, scope = extract_html(("<main><h3>Transcript</h3><p>" + text + "</p><h3>Other episode</h3><p>Unrelated</p></main>").encode(), True)
        self.assertEqual(scope, "TRANSCRIPT"); self.assertNotIn("Unrelated", body)

    def test_episode_selection_never_assigns_newest_transcript_to_old_episode(self):
        a="Discussion for the first episode. "*8
        b="Discussion for the second episode. "*8
        html=("<main><section><h4>S1:E2 New episode</h4><h3>Transcript</h3><p>"+a+"</p></section><section><h4>S1:E1 Old episode</h4><h3>Transcript</h3><p>"+b+"</p></section></main>").encode()
        body,scope=extract_html(html,True,"Old episode")
        self.assertIn("second episode",body);self.assertNotIn("first episode",body)
        with self.assertRaises(FetchError):extract_html(html,True,"Missing episode")

    def test_unavailable_or_oversized_audio_falls_back_without_transcription_charge(self):
        fetcher=Mock();fetcher.get.side_effect=FetchError("CONTENT_TOO_LARGE")
        ai=Mock();result=acquire(self.item(content_kind="PODCAST",audio_url="https://publisher.test/a.mp3"),fetcher,ai)
        self.assertEqual(result["scope"],"SUMMARY_ONLY");self.assertEqual(result["error"],"CONTENT_TOO_LARGE");ai.assert_not_called()

    def test_private_ip_and_non_https_and_unapproved_hosts_are_rejected(self):
        resolve = lambda *a, **kw: [(None, None, None, None, ("127.0.0.1", 443))]
        for url in ("https://publisher.test/a", "http://publisher.test/a", "https://other.test/a", "https://x:secret@publisher.test/a", "https://publisher.test:8443/a"):
            with self.assertRaises(FetchError): public_target(url, {"publisher.test"}, resolve)

    def test_mixed_public_private_dns_is_rejected(self):
        resolve = lambda *a, **kw: [(None,None,None,None,(ip,443)) for ip in ("8.8.8.8", "10.0.0.1")]
        with self.assertRaises(FetchError): public_target("https://publisher.test", {"publisher.test"}, resolve)

    def test_oversized_or_invalid_transcripts_are_explicit(self):
        with self.assertRaises(FetchError): transcript(b'{"segments":[]}', "application/json")
        with self.assertRaises(FetchError): transcript(b"WEBVTT\ninvalid", "text/vtt")


if __name__ == "__main__": unittest.main()
