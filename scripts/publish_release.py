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
    python scripts/publish_release.py --debug-only  # debug APK only
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
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECTS_ROOT = os.path.dirname(ROOT)
H3C_RELAY_PATH = os.path.join(PROJECTS_ROOT, "home-datacenter", "tools")
if H3C_RELAY_PATH not in sys.path:
    sys.path.insert(0, H3C_RELAY_PATH)

try:
    from h3c_relay import resolve_best_endpoint, release_tunnel
except ImportError:
    resolve_best_endpoint = None
    release_tunnel = None

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


def build(debug_only=False):
    env = dict(os.environ)
    if "JAVA_HOME" not in env and os.path.isdir(DEFAULT_JAVA_HOME):
        env["JAVA_HOME"] = DEFAULT_JAVA_HOME
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    tasks = [":app:assembleDebug"] if debug_only else [":app:assembleRelease", ":app:assembleDebug"]
    cmd = [gradlew, *tasks, "--console=plain"]
    print("==> building:", " ".join(cmd))
    r = subprocess.run(cmd, cwd=ROOT, env=env)
    if r.returncode != 0:
        sys.exit(f"ERROR: gradle build failed (exit {r.returncode})")


def connect(force_h3c=False):
    import paramiko

    pw = os.environ.get("NAS_PASSWORD")
    key_file = os.path.expanduser("~/.ssh/id_ed25519")
    if not pw and not os.path.isfile(key_file):
        sys.exit("ERROR: set NAS_PASSWORD in the environment or configure ~/.ssh/id_ed25519")
    user = os.environ.get("NAS_USER", "momo")

    resolved_ep = None
    targets = []

    # Priority 1: H3C Oasis / LAN intelligent resolver
    skip_h3c = os.environ.get("SKIP_H3C", "").strip().lower() in ("1", "true", "yes")
    if resolve_best_endpoint and not os.environ.get("NAS_HOST"):
        try:
            print("==> detecting best connection path (LAN -> H3C Oasis Relay -> Tailscale)...")
            resolved_ep = resolve_best_endpoint(app_name="ssh", internal_port=22, prefer_h3c=(not skip_h3c), force_h3c=force_h3c)
            if resolved_ep:
                print(f"==> selected path: [{resolved_ep.tier.upper()}] {resolved_ep.host}:{resolved_ep.port}")
                targets.append((resolved_ep.host, resolved_ep.port))
        except Exception as e:
            print(f"    Dynamic endpoint resolution warning: {e}")

    # Fallback targets
    targets.extend([
        (os.environ.get("NAS_HOST", "100.90.67.71"), int(os.environ.get("NAS_PORT", "22"))),
        (os.environ.get("NAS_FALLBACK_HOST", "154.8.195.220"), int(os.environ.get("NAS_FALLBACK_PORT", "51351"))),
    ])

    # Deduplicate while preserving order
    seen = set()
    unique_targets = []
    for h, p in targets:
        if (h, p) not in seen:
            seen.add((h, p))
            unique_targets.append((h, p))

    last = None
    for host, port in unique_targets:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        # Try key auth first
        if os.path.isfile(key_file):
            try:
                ssh.connect(host, port=port, username=user, key_filename=key_file, timeout=12)
                print(f"==> connected to {host}:{port} via SSH key")
                return ssh, resolved_ep
            except Exception as e_key:
                print(f"    {host}:{port} key auth failed: {e_key}")
        # Try password auth if provided
        if pw:
            try:
                ssh.connect(host, port=port, username=user, password=pw, timeout=12)
                print(f"==> connected to {host}:{port} via password")
                return ssh, resolved_ep
            except Exception as e_pw:
                last = e_pw
                print(f"    {host}:{port} password auth failed: {e_pw}")
        else:
            last = "No password or valid key available"
    sys.exit(f"ERROR: cannot reach NAS: {last}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-build", action="store_true", help="skip gradle, upload existing APKs")
    ap.add_argument("--debug-only", action="store_true", help="build + upload only the debug APK")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and exit")
    ap.add_argument("--force-h3c", action="store_true", help="force H3C Oasis high-speed relay tunnel")
    ap.add_argument("--keep-tunnel", action="store_true", help="keep H3C tunnel open after publish")
    args = ap.parse_args()

    version, code = read_version()
    notes = os.path.join(ROOT, f"release-notes-v{version}.txt")
    print(f"==> version {version} (versionCode {code})")
    if not os.path.isfile(notes):
        sys.exit(f"ERROR: missing release notes: {notes}")

    # Only upload distinct local files over the wire to avoid duplicate 100MB transfers
    upload_map = [
        (DEBUG_APK, f"{REMOTE_DIR}/app-debug-v{version}.apk"),
        (notes, f"{REMOTE_DIR}/release-notes-v{version}.txt"),
    ]
    if not args.debug_only:
        upload_map = [
            (RELEASE_APK, f"{REMOTE_DIR}/app-release-v{version}.apk"),
            *upload_map,
        ]
    if args.dry_run:
        for lp, rp in upload_map:
            print(f"    {lp} -> {rp}")
        return

    if not args.no_build:
        build(debug_only=args.debug_only)
    for lp, _ in upload_map:
        if not os.path.isfile(lp):
            sys.exit(f"ERROR: missing build output: {lp}")

    ssh, ep = connect(force_h3c=args.force_h3c)
    try:
        sftp = ssh.open_sftp()
        for lp, rp in upload_map:
            sz = os.path.getsize(lp)
            print(f"==> upload {os.path.basename(lp)} ({sz / (1024*1024):.2f} MB) -> {rp}")
            if sz > 1024 * 1024:
                # Use pipelined streaming for large files to avoid WAN stop-and-wait ACK penalty
                with open(lp, "rb") as fl:
                    with sftp.file(rp, "wb") as fr:
                        fr.set_pipelined(True)
                        chunk_sz = 512 * 1024
                        done = 0
                        t_start = time.time()
                        last_print = t_start
                        while True:
                            b = fl.read(chunk_sz)
                            if not b:
                                break
                            fr.write(b)
                            done += len(b)
                            now = time.time()
                            if now - last_print >= 1.0 or done == sz:
                                speed = (done / (1024 * 1024)) / (now - t_start) if (now - t_start) > 0 else 0
                                pct = (done / sz) * 100
                                print(f"\r    [{pct:5.1f}%] {done / (1024*1024):5.1f}/{sz / (1024*1024):5.1f} MB ({speed:4.2f} MB/s)", end="", flush=True)
                                last_print = now
                        total_file_time = max(0.1, time.time() - t_start)
                        print(f"\r    [100.0%] {sz / (1024*1024):5.1f} MB uploaded in {total_file_time:.1f}s (avg {(sz / (1024*1024)) / total_file_time:.2f} MB/s)  ")
            else:
                sftp.put(lp, rp)
            sftp.chmod(rp, 0o644)
        sftp.close()

        # Create latest symlinks/copies directly on NAS to save bandwidth
        print("==> linking latest versions on NAS directly...")
        link_cmds = [
            f"cp -f {REMOTE_DIR}/app-debug-v{version}.apk {REMOTE_DIR}/app-debug-latest.apk",
        ]
        if not args.debug_only:
            link_cmds.append(f"cp -f {REMOTE_DIR}/app-release-v{version}.apk {REMOTE_DIR}/app-release-latest.apk")
        for cmd in link_cmds:
            ssh.exec_command(cmd)

        files = [
            (DEBUG_APK, f"{REMOTE_DIR}/app-debug-v{version}.apk"),
            (DEBUG_APK, f"{REMOTE_DIR}/app-debug-latest.apk"),
            (notes, f"{REMOTE_DIR}/release-notes-v{version}.txt"),
        ]
        if not args.debug_only:
            files = [
                (RELEASE_APK, f"{REMOTE_DIR}/app-release-v{version}.apk"),
                (RELEASE_APK, f"{REMOTE_DIR}/app-release-latest.apk"),
                *files,
            ]

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
        if ep and ep.created_new and ep.tunnel_id and not args.keep_tunnel and release_tunnel:
            try:
                print(f"==> cleaning up temporary H3C relay tunnel ({ep.tunnel_id})...")
                release_tunnel(ep.tunnel_id)
                print("==> H3C relay tunnel closed.")
            except Exception as e_clean:
                print(f"    Warning: cleanup H3C tunnel failed: {e_clean}")


if __name__ == "__main__":
    main()
