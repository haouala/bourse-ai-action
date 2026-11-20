"""Conservative extraction. A readable extract is never claimed to be a full article."""
from dataclasses import dataclass, field, asdict
from html.parser import HTMLParser
import json
import re
from urllib.parse import urljoin
from .http import FetchError


@dataclass
class Node:
    tag: str
    attrs: dict = field(default_factory=dict)
    children: list = field(default_factory=list)

    def walk(self):
        yield self
        for child in self.children:
            if isinstance(child, Node):
                yield from child.walk()

    def text(self):
        if self.tag in {"script", "style", "nav", "footer", "header", "aside", "form", "noscript"} or self.attrs.get("aria-hidden") == "true":
            return ""
        return " ".join(c.text() if isinstance(c, Node) else c for c in self.children).strip()


class Page(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.root = Node("root")
        self.stack = [self.root]
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        node = Node(tag, dict(attrs)); self.stack[-1].children.append(node)
        if tag not in {"br", "hr", "img", "meta", "link", "input", "source", "embed", "wbr", "area", "base", "col", "param", "track"}:
            if len(self.stack) >= 150:
                raise FetchError("HTML_TOO_DEEP")
            self.stack.append(node)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                self.stack = self.stack[:i]; break

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def clean(text):
    return re.sub(r"\s+", " ", text).strip()


def extract_html(data, podcast=False):
    page = Page(data.decode("utf-8", errors="replace")); nodes = list(page.root.walk())
    # Respect explicit paywall metadata; do not turn previews into complete text.
    for node in nodes:
        if node.tag == "script" and node.attrs.get("type") == "application/ld+json":
            raw = "".join(c for c in node.children if isinstance(c, str))
            if re.search(r'"isAccessibleForFree"\s*:\s*(false|"false")', raw, re.I):
                raise FetchError("PAYWALL_OR_PREVIEW")
    if podcast:
        regions = [n for n in nodes if "transcript" in (n.attrs.get("id", "") + " " + n.attrs.get("class", "")).lower() and len(clean(n.text())) >= 120]
        if regions:
            return clean(regions[0].text()), "TRANSCRIPT"
        # Publisher pages often use a heading followed by transcript paragraphs.
        for parent in nodes:
            for i, n in enumerate(parent.children):
                if isinstance(n, Node) and n.tag in {"h1", "h2", "h3", "h4", "h5", "h6", "p"} and clean(n.text()).lower() in {"transcript", "full transcript", "transcription"}:
                    parts = []
                    for sibling in parent.children[i + 1:]:
                        if isinstance(sibling, Node) and sibling.tag in {"h1", "h2", "h3", "h4"}:
                            break
                        parts.append(sibling.text() if isinstance(sibling, Node) else sibling)
                    text = clean(" ".join(parts))
                    if len(text) >= 120:
                        return text, "TRANSCRIPT"
        raise FetchError("TRANSCRIPT_NOT_FOUND")
    for tag in ("article", "main"):
        candidates = [clean(n.text()) for n in nodes if n.tag == tag]
        if candidates and len(max(candidates, key=len)) >= 120:
            return max(candidates, key=len), "ARTICLE_EXTRACT"
    for n in nodes:
        if n.tag == "script" and n.attrs.get("type") == "application/ld+json":
            try:
                value = json.loads("".join(c for c in n.children if isinstance(c, str)))
                values = value if isinstance(value, list) else [value]
                for obj in values:
                    if isinstance(obj, dict) and isinstance(obj.get("articleBody"), str) and len(obj["articleBody"]) >= 120:
                        return clean(obj["articleBody"]), "ARTICLE_EXTRACT"
            except (ValueError, TypeError):
                pass
    raise FetchError("ARTICLE_NOT_FOUND")


def transcript(data, content_type):
    text = data.decode("utf-8-sig", errors="replace")
    if "html" in content_type or text.lstrip().lower().startswith(("<html", "<!doctype")):
        return (*extract_html(data, podcast=True), [])
    if "json" in content_type:
        try:
            result = json.loads(text); segments = result["segments"]
            if not isinstance(segments, list) or not 0 < len(segments) <= 2000:
                raise ValueError()
            texts = [str(s["text"]).strip() for s in segments]
            body = "\n".join(texts)
            result_segments = [{"speaker": s.get("speaker"), "startSeconds": s.get("start"), "endSeconds": s.get("end"), "text": t} for s, t in zip(segments, texts)]
            return body, "TRANSCRIPT", result_segments
        except (ValueError, KeyError, TypeError):
            raise FetchError("INVALID_TRANSCRIPT") from None
    if "vtt" in content_type or text.startswith("WEBVTT") or "-->" in text:
        segments = []
        def seconds(value):
            parts = value.replace(",", ".").split(":")
            return sum(float(p) * 60 ** i for i, p in enumerate(reversed(parts)))
        for block in re.split(r"\r?\n\s*\r?\n", text):
            match = re.search(r"(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3})[^\n]*\n(.+)", block, re.S)
            if not match:
                continue
            speaker = re.search(r"<v\s+([^>]+)>", match[3])
            words = clean(re.sub(r"<[^>]*>", "", match[3]))
            segments.append({"speaker": speaker[1] if speaker else None, "startSeconds": seconds(match[1]), "endSeconds": seconds(match[2]), "text": words})
        if not segments:
            raise FetchError("INVALID_TRANSCRIPT")
        return "\n".join(s["text"] for s in segments), "TRANSCRIPT", segments
    if "text/plain" not in content_type:
        raise FetchError("UNSUPPORTED_TRANSCRIPT_TYPE")
    return text.strip(), "TRANSCRIPT", []


def acquire(item, fetcher, transcriber=None):
    fallback = {"text": item["title"] + "\n" + item["summary"], "scope": "SUMMARY_ONLY", "url": item["original_url"], "method": "RSS_SUMMARY", "error": None, "segments": []}
    podcast = item.get("content_kind") == "PODCAST" or bool(item.get("audio_url"))
    errors = []
    try:
        if podcast and item.get("transcript_url"):
            try:
                response = fetcher.get(item["transcript_url"])
                body, scope, segments = transcript(response.body, response.content_type)
                return bounded(body, scope, response.url, "PUBLISHER_TRANSCRIPT", segments)
            except FetchError as e:
                errors.append(e.code)
        response = fetcher.get(item["original_url"])
        if "html" not in response.content_type:
            raise FetchError("UNSUPPORTED_PAGE_TYPE")
        try:
            body, scope = extract_html(response.body, podcast)
            return bounded(body, scope, response.url, "PUBLISHER_HTML", [])
        except FetchError:
            if not podcast:
                raise
        page = Page(response.body.decode("utf-8", errors="replace"))
        for node in page.root.walk():
            if node.tag == "a" and clean(node.text()).lower() in {"full transcript", "transcript", "transcription"}:
                target = urljoin(response.url, node.attrs.get("href", ""))
                result = fetcher.get(target)
                body, scope, segments = transcript(result.body, result.content_type)
                return bounded(body, scope, result.url, "PUBLISHER_TRANSCRIPT", segments)
        raise FetchError("TRANSCRIPT_NOT_FOUND")
    except FetchError as e:
        errors.append(e.code)
    if podcast and item.get("audio_url") and transcriber:
        response = fetcher.get(item["audio_url"], max_bytes=25_000_000)
        body = transcriber(response.body, response.content_type)
        return bounded(body, "TRANSCRIPT", item["audio_url"], "OPENAI_TRANSCRIPTION", [])
    fallback["error"] = errors[-1] if errors else "CONTENT_UNAVAILABLE"
    return fallback


def bounded(text, scope, url, method, segments):
    if len(text.strip()) < 120:
        raise FetchError("CONTENT_TOO_SHORT")
    if len(text) > 200000:
        # Never silently drop the end of a podcast and label it complete.
        raise FetchError("CONTENT_TOO_LARGE")
    return {"text": text, "scope": scope, "url": url, "method": method, "error": None, "segments": segments}
