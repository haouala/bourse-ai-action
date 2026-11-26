"""Independent process; the backend owns the persistent PostgreSQL work queue."""
import hashlib
import json
import re
import sqlite3
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler
from urllib.error import HTTPError, URLError
from .ai import PROMPT_VERSION, chunks, validate
from .content import acquire
from .http import FetchError


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None


class Backend:
    def __init__(self, url, token):
        u = urlsplit(url)
        local = u.scheme == "http" and u.hostname in {"127.0.0.1", "localhost", "::1"}
        if u.username or u.password or u.query or u.fragment or u.path not in ("", "/") or not u.hostname or not (u.scheme == "https" or local):
            raise FetchError("INVALID_BACKEND_URL")
        if len(token) < 32: raise FetchError("WORKER_TOKEN_MISSING")
        self.url = url.rstrip("/"); self.token = token
        self.opener = build_opener(NoRedirect(), ProxyHandler({}))

    def call(self, path, value=None, method="POST", lease=None):
        headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
        if lease: headers["X-Lease-Token"] = lease
        request = Request(self.url + "/api/internal/information" + path, data=json.dumps(value or {}).encode(), headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read(4_000_001)
                if len(raw) > 4_000_000: raise FetchError("BACKEND_RESPONSE_TOO_LARGE")
                return json.loads(raw) if raw else {}
        except HTTPError as e: raise FetchError("BACKEND_HTTP_" + str(e.code)) from None
        except (URLError, OSError, ValueError): raise FetchError("BACKEND_UNAVAILABLE") from None


class Cache:
    def __init__(self, path):
        self.path = str(path); Path(path).parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path) as db:
            db.execute("CREATE TABLE IF NOT EXISTS chunks (key TEXT PRIMARY KEY,result TEXT NOT NULL)")

    def get(self, key):
        with sqlite3.connect(self.path) as db: row = db.execute("SELECT result FROM chunks WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else None

    def put(self, key, result):
        with sqlite3.connect(self.path) as db: db.execute("INSERT OR IGNORE INTO chunks VALUES (?,?)", (key, json.dumps(result, ensure_ascii=False)))


def lower_keys(row): return {str(k).lower(): v for k, v in row.items()}


class Worker:
    def __init__(self, backend, fetcher, ai, cache, transcribe=False):
        self.backend = backend; self.fetcher = fetcher; self.ai = ai; self.cache = cache; self.transcribe = transcribe

    def cycle(self, max_items):
        sources = self.backend.call("/sources/due").get("sources", [])
        if not isinstance(sources, list) or len(sources) > 40 or any(
            not isinstance(code, str) or not re.fullmatch(r"[A-Z0-9_]{2,60}", code) for code in sources
        ):
            raise FetchError("INVALID_SOURCE_LIST")
        collection = []
        for code in sources:
            try:
                collection.append(self.backend.call(f"/sources/{code}/sync"))
            except FetchError as error:
                collection.append({"source": code, "status": "FAILED", "error": error.code})
        analyses = []
        for _ in range(max_items):
            result = self.once()
            if result["status"] == "IDLE":
                break
            analyses.append(result)
            if result.get("error") in {"DAILY_BUDGET_EXHAUSTED", "AI_HTTP_429"}:
                break
        failures = sum(row["status"] == "FAILED" for row in collection + analyses)
        return {"status": "PARTIAL" if failures else "SUCCEEDED", "collection": collection, "analyses": analyses}

    def once(self):
        self.backend.call("/enqueue")
        job = self.backend.call("/claim")
        if job["status"] == "IDLE": return {"status": "IDLE"}
        job_id = job["jobId"]; lease = job["leaseToken"]; prefix = f"/jobs/{job_id}"
        try:
            if job["promptVersion"] != PROMPT_VERSION: raise FetchError("PROMPT_VERSION_MISMATCH")
            if job.get("content"):
                content = lower_keys(job["content"]); text = content["body_text"]; digest = content["content_hash"]
            else:
                content = acquire(lower_keys(job["item"]), self.fetcher, self.ai.transcribe if self.transcribe else None)
                identity = lower_keys(self.backend.call(prefix + "/content", content, method="PUT", lease=lease))
                text = content["text"]; digest = identity["content_hash"]
            summaries = []; uncertainties = []; events = {}; assets = [lower_keys(a) for a in job["assets"]]
            for part in chunks(text):
                self.backend.call(prefix + "/heartbeat", lease=lease)
                key = hashlib.sha256(json.dumps([job["model"], PROMPT_VERSION, assets, part], ensure_ascii=False, sort_keys=True).encode()).hexdigest()
                result = self.cache.get(key)
                if result is None:
                    result = self.ai.analyze(part, assets, job["model"]); self.cache.put(key, result)
                validate(result, part, {a["symbol"] for a in assets})
                summaries.append(result["summary"]); uncertainties.extend(result["uncertainties"])
                for event in result["events"]:
                    group = hashlib.sha256((event["topic"] + "|" + event["kind"] + "|" + event["quote"]).encode()).hexdigest()
                    if group not in events:
                        event["eventKey"] = "event-" + group; events[group] = event
                    else:
                        existing = {(i["symbol"], i["horizon"]): i for i in events[group]["impacts"]}
                        for impact in event["impacts"]:
                            k = impact["symbol"], impact["horizon"]
                            if k in existing and existing[k]["direction"] != impact["direction"]: existing[k]["direction"] = "UNKNOWN"
                            else: existing.setdefault(k, impact)
                        events[group]["impacts"] = list(existing.values())
            summary = "\n".join(summaries)
            if len(summaries) > 1: uncertainties.append("Résumé assemblé de plusieurs passages ; citations attachées à chaque événement.")
            if len(summary) > 4000:
                summary = summary[:3980] + "…"; uncertainties.append("Résumé affiché raccourci ; tous les passages conservés ont été analysés.")
            result = {"summary": summary, "uncertainties": list(dict.fromkeys(uncertainties))[:100], "events": list(events.values())}
            if len(result["events"]) > 300: raise FetchError("TOO_MANY_EVENTS")
            completion = {"contentHash": digest, "analyzedCharacters": len(text.encode("utf-16-le")) // 2, "analysis": result}
            response = self.backend.call(prefix + "/complete", completion, lease=lease)
            return {"status": "SUCCEEDED", "jobId": job_id, **response}
        except Exception as error:
            code = error.code if isinstance(error, FetchError) else "PROCESSING_ERROR"
            retry = code in {"DAILY_BUDGET_EXHAUSTED", "AI_HTTP_429", "AI_HTTP_500", "AI_HTTP_502", "AI_HTTP_503", "AI_CONNECTION_OR_FORMAT_ERROR", "BACKEND_UNAVAILABLE"}
            try: self.backend.call(prefix + "/failure", {"code": code, "retryable": retry}, lease=lease)
            except FetchError: pass  # A lost connection is recovered by lease expiry.
            return {"status": "FAILED", "jobId": job_id, "error": code}
