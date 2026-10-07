"""One-command Android release: build -> upload to NAS -> verify.

Replaces the hand-edited upload_release.py / release.ps1 / push-apk.ps1 flow.

  * Version is read from app/build.gradle.kts (versionName / versionCode).
  * NAS password comes from the NAS_PASSWORD environment variable (never
    hardcoded). Optional overrides: NAS_HOST, NAS_PORT, NAS_USER,
    NAS_FALLBACK_HOST, NAS_FALLBACK_PORT.
  * Uploads app-release-vX.apk, app-release-latest.apk, app-debug-vX.apk,
    app-debug-latest.apk and release-notes-vX.txt to the releases dir, then
    verifies every file by sha256 on the NAS.

Usage (from the repo root):
    $env:NAS_PASSWORD = '...'
    python scripts/publish_release.py            # build + upload + verify
    python scripts/publish_release.py --no-build # upload existing APKs
    python scripts/publish_release.py --dry-run  # show plan only

Requires: paramiko (pip install paramiko).
"""

import argparse
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE_FILE = os.path.join(ROOT, "app", "build.gradle.kts")
RELEASE_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
DEBUG_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
REMOTE_DIR = "/vol1/docker/home-datacenter/data/releases"
DEFAULT_JAVA_HOME = r"D:\AndroidStudio\jbr"


def read_version():
    src = open(GRADLE_FILE, encoding="utf-8").read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    code = re.search(r"versionCode\s*=\s*(\d+)", src)
    if not name or not code:
        sys.exit("ERROR: cannot parse versionName/versionCode from build.gradle.kts")
    return name.group(1), int(code.group(1))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build():
    env = dict(os.environ)
    if "JAVA_HOME" not in env and os.path.isdir(DEFAULT_JAVA_HOME):
        env["JAVA_HOME"] = DEFAULT_JAVA_HOME
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [gradlew, ":app:assembleRelease", ":app:assembleDebug", "--console=plain"]
    print("==> building:", " ".join(cmd))
    r = subprocess.run(cmd, cwd=ROOT, env=env)
    if r.returncode != 0:
        sys.exit(f"ERROR: gradle build failed (exit {r.returncode})")


def connect():
    import paramiko

    pw = os.environ.get("NAS_PASSWORD")
    if not pw:
        sys.exit("ERROR: set NAS_PASSWORD in the environment")
    user = os.environ.get("NAS_USER", "momo")
    targets = [
        (os.environ.get("NAS_HOST", "100.90.67.71"), int(os.environ.get("NAS_PORT", "22"))),
        (os.environ.get("NAS_FALLBACK_HOST", "154.8.195.220"), int(os.environ.get("NAS_FALLBACK_PORT", "51351"))),
    ]
    last = None
    for host, port in targets:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            ssh.connect(host, port=port, username=user, password=pw, timeout=10)
            print(f"==> connected to {host}:{port}")
            return ssh
        except Exception as e:  # try next path
            last = e
            print(f"    {host}:{port} failed: {e}")
    sys.exit(f"ERROR: cannot reach NAS: {last}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-build", action="store_true", help="skip gradle, upload existing APKs")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and exit")
    args = ap.parse_args()

    version, code = read_version()
    notes = os.path.join(ROOT, f"release-notes-v{version}.txt")
    print(f"==> version {version} (versionCode {code})")
    if not os.path.isfile(notes):
        sys.exit(f"ERROR: missing release notes: {notes}")

    files = [
        (RELEASE_APK, f"{REMOTE_DIR}/app-release-v{version}.apk"),
        (RELEASE_APK, f"{REMOTE_DIR}/app-release-latest.apk"),
        (DEBUG_APK, f"{REMOTE_DIR}/app-debug-v{version}.apk"),
        (DEBUG_APK, f"{REMOTE_DIR}/app-debug-latest.apk"),
        (notes, f"{REMOTE_DIR}/release-notes-v{version}.txt"),
    ]
    if args.dry_run:
        for lp, rp in files:
            print(f"    {lp} -> {rp}")
        return

    if not args.no_build:
        build()
    for lp, _ in files:
        if not os.path.isfile(lp):
            sys.exit(f"ERROR: missing build output: {lp}")

    ssh = connect()
    try:
        sftp = ssh.open_sftp()
        for lp, rp in files:
            print(f"==> upload {os.path.basename(lp)} -> {rp}")
            sftp.put(lp, rp)
            sftp.chmod(rp, 0o644)
        sftp.close()

        remote = " ".join(rp for _, rp in files)
        _, out, _ = ssh.exec_command(f"sha256sum {remote}")
        remote_sums = {}
        for line in out.read().decode("utf-8", "replace").splitlines():
            parts = line.split()
            if len(parts) == 2:
                remote_sums[parts[1]] = parts[0]
        ok = True
        for lp, rp in files:
            match = remote_sums.get(rp) == sha256(lp)
            ok &= match
            print(f"    {'OK      ' if match else 'MISMATCH'} {rp}")
        if not ok:
            sys.exit("ERROR: checksum mismatch on NAS")
        print(f"==> published v{version} ({code})")
    finally:
        ssh.close()


if __name__ == "__main__":
    main()
