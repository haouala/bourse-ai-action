import argparse
import json
import os
from pathlib import Path
import time
from .ai import Budget, OpenAI
from .http import PublicFetcher, FetchError
from .worker import Backend, Cache, Worker

DEFAULT_HOSTS = "www.federalreserve.gov,www.ecb.europa.eu,www.bankofengland.co.uk,www.gov.uk,news.un.org,www.bbc.com,www.bbc.co.uk,www.lemonde.fr,www.aljazeera.com,www.france24.com,www.richmondfed.org,www.rbc.com,speakingoftheeconomy.libsyn.com,traffic.libsyn.com,episodes.captivate.fm"


def main(argv=None):
    parser = argparse.ArgumentParser(description="Information worker; paid calls require explicit opt-in")
    parser.add_argument("command", choices=["once", "run"])
    args = parser.parse_args(argv)
    try:
        if os.getenv("INFORMATION_OPENAI_ENABLED", "").lower() != "true": raise FetchError("OPENAI_DISABLED")
        state = Path(os.getenv("INFORMATION_STATE_DIR", "state"))
        budget = Budget(state / "budget.sqlite", int(os.getenv("INFORMATION_DAILY_MAX_CALLS", "0")), int(os.getenv("INFORMATION_DAILY_MAX_INPUT_BYTES", "0")))
        ai = OpenAI(os.getenv("OPENAI_API_KEY", ""), budget)
        worker = Worker(Backend(os.getenv("INFORMATION_BACKEND_URL", "http://127.0.0.1:8081"), os.getenv("INFORMATION_WORKER_TOKEN", "")), PublicFetcher(os.getenv("INFORMATION_ALLOWED_HOSTS", DEFAULT_HOSTS).split(",")), ai, Cache(state / "cache.sqlite"), os.getenv("INFORMATION_TRANSCRIBE_ENABLED", "").lower() == "true")
        while True:
            try: result = worker.once()
            except FetchError as e: result = {"status": "FAILED", "error": e.code}
            print(json.dumps(result), flush=True)
            if args.command == "once": return 0 if result["status"] != "FAILED" else 1
            time.sleep(10)  # Queue polling does not change the hourly publisher interval.
    except (FetchError, ValueError) as e:
        print(json.dumps({"status": "FAILED", "error": e.code if isinstance(e, FetchError) else "INVALID_CONFIGURATION"})); return 2
    except KeyboardInterrupt: return 0


if __name__ == "__main__": raise SystemExit(main())
