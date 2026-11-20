"""Run the complete Android validation against a disposable local V8 backend."""
import argparse
from pathlib import Path
import os
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--jar", required=True)
args = parser.parse_args()
jar = Path(args.jar).resolve()
assert jar.is_file(), jar
output = Path("app/build/outputs")
output.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env.update(MARKET_SCHEDULER_ENABLED="false", INFORMATION_SCHEDULER_ENABLED="false",
           SOCIAL_X_ENABLED="false", TWELVE_DATA_API_KEY="", EODHD_API_KEY="", FMP_API_KEY="",
           SOCIAL_X_BEARER_TOKEN="", SPRING_PROFILES_ACTIVE="")
with (output / "backend-integration.log").open("w") as log:
    process = subprocess.Popen([
        "java", "-Xmx384m", "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=18081",
        "--spring.datasource.url=jdbc:h2:mem:mobilecontract;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
        "--market.scheduler.enabled=false", "--information.scheduler.enabled=false", "--social.x.enabled=false"
    ], env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 90
        while True:
            if process.poll() is not None:
                raise RuntimeError("Backend exited; inspect app/build/outputs/backend-integration.log")
            try:
                with urllib.request.urlopen("http://127.0.0.1:18081/actuator/health", timeout=2) as response:
                    if response.status == 200:
                        break
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Backend startup timeout")
            time.sleep(0.5)
        subprocess.run(["bash", "./gradlew", ":app:assembleDebug", ":app:assembleRelease",
                        ":app:testDebugUnitTest", ":app:lintDebug",
                        "-PbackendUrl=http://127.0.0.1:18081", "--no-daemon", "--max-workers=2"], check=True)
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
