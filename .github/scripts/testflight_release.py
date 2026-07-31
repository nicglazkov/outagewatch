"""Post-upload TestFlight steps, run by the testflight workflow after
xcodebuild uploads a build: wait for App Store Connect to finish processing the
newest build, attach it to the Public external group, and submit it for beta
review (a formality after the first build; later submissions auto-clear).

Auth comes from env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH. Requires only
App Manager permissions; the Admin key the build steps use also works.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time

APP_ID = "6796215203"                                   # OutageWatch iOS
GROUP_ID = "ede108c9-195c-4cd7-8de8-b23ac36669d6"       # external group "Public"


def jwt() -> str:
    b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=")
    now = int(time.time())
    signing = (
        b64(json.dumps({"alg": "ES256", "kid": os.environ["ASC_KEY_ID"], "typ": "JWT"}).encode())
        + b"."
        + b64(json.dumps({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 900,
                          "aud": "appstoreconnect-v1"}).encode())
    )
    with tempfile.NamedTemporaryFile(delete=False) as f:
        f.write(signing)
        payload = f.name
    der = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", os.environ["ASC_KEY_PATH"], payload],
        capture_output=True, check=True).stdout
    os.unlink(payload)

    def ints(d):
        assert d[0] == 0x30
        i, out = 2, []
        for _ in range(2):
            assert d[i] == 0x02
            ln = d[i + 1]
            out.append(d[i + 2:i + 2 + ln])
            i += 2 + ln
        return [x.lstrip(b"\x00").rjust(32, b"\x00") for x in out]

    r, s = ints(der)
    return (signing + b"." + b64(r + s)).decode()


def call(method: str, path: str, body=None):
    cmd = ["curl", "-sg", "-X", method, f"https://api.appstoreconnect.apple.com{path}",
           "-H", f"Authorization: Bearer {jwt()}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    return json.loads(out) if out.strip() else {}


def main() -> int:
    # Only accept a build uploaded by THIS run: right after xcodebuild returns,
    # the ASC API can still be showing the previous build as newest, and acting
    # on that attaches the wrong build. RUN_CUTOFF is stamped by the workflow
    # before the archive starts.
    cutoff = os.environ["RUN_CUTOFF"]
    build = None
    for attempt in range(60):  # up to 30 minutes
        resp = call("GET", f"/v1/builds?filter[app]={APP_ID}"
                           "&sort=-uploadedDate&limit=1"
                           "&fields[builds]=version,processingState,expirationDate,uploadedDate")
        data = resp.get("data", [])
        if data:
            candidate = data[0]
            a = candidate["attributes"]
            if a.get("uploadedDate", "") < cutoff:
                print(f"newest build {a['version']} predates this run, waiting")
            else:
                print(f"this run's build {a['version']}: {a['processingState']}")
                if a["processingState"] == "VALID":
                    build = candidate
                    break
                if a["processingState"] in ("FAILED", "INVALID"):
                    print("build processing failed", file=sys.stderr)
                    return 1
        time.sleep(30)
    if build is None:
        print("timed out waiting for this run's build", file=sys.stderr)
        return 1

    bid = build["id"]
    resp = call("POST", f"/v1/betaGroups/{GROUP_ID}/relationships/builds",
                {"data": [{"type": "builds", "id": bid}]})
    if resp.get("errors"):
        print("attach failed:", resp["errors"], file=sys.stderr)
        return 1
    print("attached to Public group")

    resp = call("POST", "/v1/betaAppReviewSubmissions",
                {"data": {"type": "betaAppReviewSubmissions",
                          "relationships": {"build": {"data": {"type": "builds", "id": bid}}}}})
    if "data" in resp:
        print("beta review state:", resp["data"]["attributes"].get("betaReviewState"))
    else:
        # Already submitted automatically, or already approved; both are fine.
        print("review submission response:", [e.get("detail") for e in resp.get("errors", [])])

    exp = build["attributes"].get("expirationDate", "?")
    print(f"done; this build expires {exp[:10]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
