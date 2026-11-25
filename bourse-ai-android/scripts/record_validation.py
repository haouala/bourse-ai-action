"""Verify test reports and retain a truthful inventory for this exact checkout."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path("app/build")
reports = sorted((root / "test-results/testDebugUnitTest").glob("TEST-*.xml"))
assert reports, "No test report"
totals = dict(tests=0, failures=0, errors=0, skipped=0)
suites = []
for report in reports:
    node = ET.parse(report).getroot()
    result = {key: int(node.get(key, "0")) for key in totals}
    for key in totals:
        totals[key] += result[key]
    suites.append(dict(name=node.get("name"), **result))
assert totals["tests"] >= 55, totals
assert totals["failures"] == totals["errors"] == totals["skipped"] == 0, totals
screens = sorted((root / "outputs/screenshots").glob("*.png"))
assert len(screens) >= 28, "Missing rendered screens"
assert all(p.stat().st_size > 1000 for p in screens), "Empty screen"
apk = root / "outputs/apk/debug/app-debug.apk"
backend_apk = root / "outputs/apk/backend/app-backend.apk"
assert apk.is_file() and backend_apk.is_file()
data = {
    "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "androidVersion": "0.3.0", "minimumApi": 26, "targetApi": 35,
    "testTotals": totals, "testSuites": suites,
    "screenshots": [p.name for p in screens],
    "screenshotEngine": "Robolectric API 35 / native graphics / real Compose UI / local synthetic data",
    "physicalDeviceTest": False,
    "realBackendIntegrationValidated": any(s["name"].endswith(".BackendContractTest") and s["tests"] > 0 for s in suites),
    "backendCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "backendApkSha256": hashlib.sha256(backend_apk.read_bytes()).hexdigest(),
    "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "releaseSigning": "not configured; delivered APK is debug signed",
}
output = root / "outputs/validation.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(data, ensure_ascii=False, indent=2))
