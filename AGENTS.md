# AGENTS.md — Workspace Guidelines for OpenCode

## Workspace Structure & Git Boundaries

`D:\Projects` is an umbrella directory containing two separate Git repositories. **Never run git commands directly in `D:\Projects`**.
- `Android/` (`home-datacenter-app`): Native Android client (Kotlin 2.3, Jetpack Compose, ExoPlayer, WebRTC).
- `home-datacenter/`: Backend, web console, and infrastructure services.
  - `services/api/`: Go 1.26 + Gin + GORM + SQLite backend (port 8080).
  - `web/`: React 18 + Vite 5 + Tailwind SPA (port 5173 in dev, 80 in prod).
  - `services/vision/`: Python 3.11 + OpenCV DNN AI service (port 8090).
  - `deploy/`: Mosquitto, Frigate NVR (bundled go2rtc on 1984), deployment configs.

---

## Android Client (`Android/`)

### Environment & Toolchain
- **JDK Requirement**: Gradle daemon requires Java 21. Set `JAVA_HOME` before running Gradle:
  ```powershell
  $env:JAVA_HOME = "D:\AndroidStudio\jbr"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  ```
- **Keystores**:
  - Debug: `app/keystore/home-debug.jks` (store/key password: `home123`, alias: `home-debug`) is checked in. Used for all debug builds to keep cross-machine signatures consistent.
  - Release: `keystore.properties` + `app/keystore/home-release.jks` (gitignored).

### Key Commands (run from `Android/`)
- **Run all unit tests**:
  ```powershell
  .\gradlew.bat testDebugUnitTest
  ```
- **Run a single test class**:
  ```powershell
  .\gradlew.bat testDebugUnitTest --tests com.homedatacenter.app.util.JwtUtilTest
  ```
- **Build APKs**:
  ```powershell
  .\gradlew.bat assembleDebug
  .\gradlew.bat assembleRelease
  ```
- **Publish Release to NAS**:
  ```powershell
  $env:NAS_PASSWORD = '<password>'; python scripts/publish_release.py
  ```
  Reads `versionName`/`versionCode` from `app/build.gradle.kts`, builds debug+release APKs, uploads to NAS releases dir, and verifies sha256.

### Code & Framework Gotchas
- **100% Jetpack Compose Architecture**:
  - The Android client is strictly 100% Jetpack Compose.
  - **Never** add new XML layout files, `ViewBinding`, or `findViewById`. All screens, dialogs, bottom sheets, and widgets must be built with Jetpack Compose using the Liquid Glass design tokens (`GlassTheme.tokens`).
- **Standard Development & Multi-Agent Task Workflow**:
  1. **Requirement Analysis**: Deeply inspect visual feedback, logs, and layout hierarchy; identify root causes.
  2. **Consortium Planning**: Organize `system-architect` (architecture/decoupling), `security-critic` (risk analysis/boundary guards), and `test-strategist` (acceptance criteria/test matrix) for critical modifications.
  3. **Implementation & Unit Tests**: Write clean Compose code, keep 100% unit tests passing (`.\gradlew.bat testDebugUnitTest`).
  4. **Review Gate**: Mandatory invocation of `code-reviewer` to audit lifecycle, memory, EGL, and concurrency risks before delivery.
  5. **Build & NAS Deployment**: Bump `versionCode`/`versionName`, assemble release/debug APKs, upload to NAS via Tailscale (`scripts/publish_release.py`), verify SHA-256, and stage clean git commits.
- **ExoPlayer Surface Order**: In `CameraDetailActivity` and player dialogs, `binding.playerView.player = player` **must be set before** calling `player.prepare()`. Calling `prepare()` first causes `MediaCodec: setOutputSurface BAD_INDEX`.
- **AudioAttributes Import**: Must use `com.google.android.exoplayer2.audio.AudioAttributes`, **not** `android.media.AudioAttributes`.
- **OkHttpClient Lifecycle**: `OkHttpClient` is **not** `Closeable` (do not use `.use {}`); `Response` and `ResponseBody` **are** `Closeable`.
- **Version Bumping**: When cutting a release, increment `versionCode` and `versionName` in `app/build.gradle.kts`.

---

## Backend API (`home-datacenter/services/api/`)

### Key Commands (run from `home-datacenter/services/api/`)
- **Build**:
  ```powershell
  go build ./internal/... ./cmd/...
  ```
  *(Do not run `go build ./...` across root; scripts in `scripts/` have individual `main()` declarations).*
- **Run tests**:
  ```powershell
  go test ./internal/...
  ```
- **Run single test**:
  ```powershell
  go test -v -run TestTriggerMatches ./internal/automation
  ```

### Database & Framework Quirks
- **SQLite Driver**: Must use `github.com/glebarez/sqlite` (pure Go, CGO-free). Never import `gorm.io/driver/sqlite` (requires CGO).
- **Nullable Datetime Fields**: Must use custom `utils.NullTime` (in `internal/utils/nulltime.go`) for nullable datetime columns. Pure-Go SQLite returns timestamps as strings; `*time.Time` will fail to scan.
- **JWT Secret**: Minimum 32 characters required. Application refuses to start with empty or placeholder secrets. Use `configs/config.local.yaml` for local development overrides (gitignored).
- **Go2rtc & Frigate**: Go2rtc is bundled inside Frigate (port 1984), not a standalone container. Camera config is pushed to Frigate via `PUT /api/config/set`.

---

## Web Frontend (`home-datacenter/web/`)

### Key Commands (run from `home-datacenter/web/`)
- **Typecheck & Production Build**:
  ```bash
  npm run build
  ```
  Runs `tsc -b && vite build`. Output in `dist/`.
- **Dev Server**:
  ```bash
  npm run dev
  ```
  Runs at `http://localhost:5173`, proxies `/api` and `/api/v1/ws` to `:8080`.
- **Typecheck only**:
  ```bash
  npx tsc --noEmit
  ```

---

## Deployment to NAS (`home-datacenter/`)

- Deploy backend & web together via SSH/tarball without local build:
  ```powershell
  .\deploy-nas.ps1 -Password '<NAS_PASSWORD>'
  ```
  Packages repo (excluding `data`, `.git`, `node_modules`, `.env`), pushes tarball to NAS `/vol1/docker/home-datacenter`, extracts, and runs `docker compose up -d --build`.
- Dry-run deploy:
  ```powershell
  .\deploy-nas.ps1 -DryRun
  ```
- Orchestrate Android build + NAS deploy in one go:
  ```powershell
  .\build-all.ps1 -Flavor release -PushApk -Password '<NAS_PASSWORD>'
  ```

---

## Operational Gotchas & Conventions

- **Device Materials & Credentials Directory**:
  All network architecture diagrams, SSH credentials, router/switch models, and operations guides are located in:
  `C:\Users\Administrator\Desktop\设备资料`
  Key documents include:
  - `NAS服务运维指南.md`: Comprehensive guide for NAS endpoints, Docker services, JWT/Mihomo/MQTT secrets, and troubleshooting.
  - `网络设备运维管理手册.md`: Specifications, admin credentials, PoE budgeting for UR7208-P-E, S1324G-PWR, APs, and SK-D840N modem.
  - `运维交接与上线操作手册.md`: Post-recovery checklist and security reinforcement instructions.
  - `安全防御中心改进方案.md`: Security architecture and roadmap.
- **SSH Access Shortcuts**:
  Host keys and shortcuts are preconfigured in `~/.ssh/config`:
  - `ssh nas`: Connects via Tailscale overlay IP (`100.90.67.71:22`, user: `momo`, key: `~/.ssh/id_ed25519`).
  - `ssh nas-lan`: Connects via local LAN static IP (`192.168.31.234:22`, user: `momo`, key: `~/.ssh/id_ed25519`).
  - H3C Oasis external tunnel: `154.8.195.220:<dynamic_port>` (query `/vol1/docker/home-datacenter/data/h3c/ssh_url.txt` on NAS).
- **WebRTC Connection Architecture & Gotchas**:
  - **H3C Oasis TCP-only Tunnel**: The public WebRTC tunnel mapped by H3C (`154.8.195.220:<port>`) is **TCP-only**. Inbound UDP packets to this IP/port are silently dropped.
  - **ICE Gathering & Priority Trap**: If go2rtc advertises both UDP and TCP candidates for `154.8.195.220`, WebRTC clients (Chrome & Android) prioritize UDP first according to RFC 8445. The resulting UDP timeouts cause client watchdogs (6s-8s) to trigger premature fallback to HLS/MP4 before ICE-TCP can connect.
  - **Candidate Rewriting**: `RewriteH3CCandidates` in `services/api/internal/camera/sdp.go` handles dynamic port rewriting on the SDP answer path. Only TCP candidates should be advertised for the H3C tunnel address to prevent clients from hanging on dead UDP probes.
  - **Codec Compatibility**: Camera feeds with H.265/HEVC or PCMA audio produce `codecs not matched` (HTTP 502) on browser WebRTC. Use VAAPI transcode (`#video=h264#hardware=vaapi`) or sub-stream, and mute audio (`#audio=0`) when browser playback is required.
- **Git Push & Proxies**:
  Direct push to GitHub may fail (exit 128 / connection reset) if a local Clash proxy (`127.0.0.1:7890`) is configured in Git but Clash is not running. Bypass proxy with:
  ```powershell
  git -c http.proxy= -c https.proxy= push
  ```
  Or use `home-datacenter\commit.ps1 -Message "..."` which handles this retry automatically.
- **Git Staging**:
  Never run `git add -A` blindly. Both repos often accumulate untracked test logs (`*.txt`), probe databases (`temp_*.db`), or APK artifacts. Check `git status --short` before staging.
- **Commit Messages**:
  Use English commit messages (`feat(...)`, `fix(...)`, `chore(...)`). PowerShell output encoding can mangle Chinese commit messages when committed through CLI bridges.
- **Line Endings**:
  Repository canonical line ending is LF. Avoid saving Go/Kotlin files with mixed CRLF/LF.
