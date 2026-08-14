# v1.8.44: support -Flavor (debug|release) and -Password/-DryRun. The APK
# filename and remote naming follow the flavor so the backend's
# release_handler.go can parse the version from either prefix.
param(
    [ValidateSet("debug", "release")]
    [string]$Flavor = "debug",
    # Release notes text written to release-notes-vX.Y.Z.txt on the NAS.
    [string]$Notes,
    # NAS password (SSH_ASKPASS). Omit to use SSH key / interactive prompt.
    [string]$Password = "<NAS_PASSWORD>",
    # Preview the push without touching the NAS.
    [switch]$DryRun
)

$NAS_USER = "fnos-momo"
$NAS_HOST = "192.168.31.235"
$NAS_PORT = 22
$REMOTE_RELEASES = "/vol1/docker/home-datacenter/data/releases"

function New-Askpass {
    param([string]$Pass)
    $f = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
    "@echo $Pass" | Set-Content $f -Encoding ASCII
    $env:SSH_ASKPASS = $f
    $env:SSH_ASKPASS_REQUIRE = "force"
    $env:DISPLAY = "1"
    return $f
}

function Invoke-NasSSH {
    param([Parameter(Mandatory)][string]$RemoteCmd)
    $opts = @("-p", "$NAS_PORT", "-o", "StrictHostKeyChecking=no",
              "-o", "UserKnownHostsFile=NUL", "-o", "ConnectTimeout=10",
              "-o", "PreferredAuthentications=password",
              "-o", "PubkeyAuthentication=no",
              "-o", "NumberOfPasswordPrompts=1")
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & ssh @opts "$NAS_USER@$NAS_HOST" $RemoteCmd 2>&1 | Out-Host; return $LASTEXITCODE }
    finally { $ErrorActionPreference = $prev }
}

function Invoke-NasSCP {
    param([Parameter(Mandatory)][string]$Local,
          [Parameter(Mandatory)][string]$Remote)
    $opts = @("-P", "$NAS_PORT", "-o", "StrictHostKeyChecking=no",
              "-o", "UserKnownHostsFile=NUL", "-o", "ConnectTimeout=10",
              "-o", "PreferredAuthentications=password",
              "-o", "PubkeyAuthentication=no",
              "-o", "NumberOfPasswordPrompts=1")
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & scp @opts $Local $Remote 2>&1 | Out-Host; return $LASTEXITCODE }
    finally { $ErrorActionPreference = $prev }
}

# v1.6.12: read version from build.gradle.kts so we don't have to
# maintain the version string in two places. Parses the versionName
# line and converts "1.6.12" → "1.6.12" for both the APK filename
# and the release-notes filename.
$buildGradle = Get-Content "d:\Projects\Android\app\build.gradle.kts" -Raw
$versionMatch = [regex]::Match($buildGradle, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) {
    Write-Host "ERROR: could not parse versionName from build.gradle.kts"
    exit 1
}
$version = $versionMatch.Groups[1].Value
Write-Host "Detected version: $version (flavor=$Flavor)"

# v1.8.44: pick the APK path by flavor. The debug/release build types
# output to different directories and carry different signatures.
$apkPath = "d:\Projects\Android\app\build\outputs\apk\$Flavor\app-$Flavor.apk"
if (-not (Test-Path $apkPath)) {
    Write-Host "ERROR: APK not found at $apkPath — run the gradle build first (or build-all.ps1)."
    exit 1
}
# v1.6.11: target the data/releases/ directory. The api container
# has this bind-mounted at /data/releases (read-only) and scans it
# for APK files. Naming convention MUST be "app-{flavor}-vX.Y.Z.apk"
# so the release_handler.go can parse the version.
# v1.6.12: also push release-notes-vX.Y.Z.txt alongside the APK —
# the backend reads this file and returns its contents as
# release_notes in the /api/v1/release/latest response.
$remoteApkPath = "$NAS_USER@$NAS_HOST`:$REMOTE_RELEASES/app-$Flavor-v$version.apk"
$localNotesPath = "d:\Projects\Android\release-notes-v$version.txt"
$remoteNotesPath = "$NAS_USER@$NAS_HOST`:$REMOTE_RELEASES/release-notes-v$version.txt"

if ($DryRun) {
    Write-Host "==> (DRY RUN) push-apk.ps1 preview" -ForegroundColor Yellow
    Write-Host "    Local APK : $apkPath"
    Write-Host "    Remote APK: $remoteApkPath"
    Write-Host "    Notes     : $(if ($Notes) {"$localNotesPath (provided)"} else {"not provided"})"
    Write-Host "    Target    : $NAS_USER@$NAS_HOST`:$REMOTE_RELEASES"
    Write-Host "==> Dry run complete. Re-run without -DryRun to push." -ForegroundColor Green
    exit 0
}

if (-not $Password) {
    Write-Host "ERROR: -Password is required for non-interactive push."
    exit 1
}

# Ensure the remote releases directory exists (mkdir -p is idempotent).
$ap = New-Askpass -Pass $Password
$code = Invoke-NasSSH -RemoteCmd "mkdir -p $REMOTE_RELEASES"
Remove-Item $ap -ErrorAction SilentlyContinue
if ($code -ne 0) {
    Write-Host "ERROR: cannot mkdir on NAS via SSH. Check host/user/password/network."
    exit 1
}

Write-Host "Pushing APK to NAS releases directory..."
$ap = New-Askpass -Pass $Password
$code = Invoke-NasSCP -Local $apkPath -Remote $remoteApkPath
Remove-Item $ap -ErrorAction SilentlyContinue
if ($code -ne 0) {
    Write-Host "APK push failed with exit code $code"
    exit $code
}
Write-Host "APK push successful."

# v1.6.12 / v1.8.44: push release notes. Prefer the -Notes param; fall
# back to an existing release-notes-vX.Y.Z.txt on disk. The backend reads
# this file and returns its contents as release_notes in /api/v1/release/latest.
$notesSent = $false
if ($Notes) {
    $Notes | Set-Content $localNotesPath -Encoding UTF8
    Write-Host "Writing release notes to $localNotesPath"
}
if (Test-Path $localNotesPath) {
    Write-Host "Pushing release notes..."
    $ap = New-Askpass -Pass $Password
    $nc = Invoke-NasSCP -Local $localNotesPath -Remote $remoteNotesPath
    Remove-Item $ap -ErrorAction SilentlyContinue
    if ($nc -eq 0) {
        Write-Host "Release notes push successful."
        $notesSent = $true
    } else {
        Write-Host "Release notes push failed (non-fatal)."
    }
} else {
    Write-Host "No release notes provided/found for v$version — skipping notes upload."
}

# Verify the pushed files on the NAS and that the API is healthy.
Write-Host "Verifying files on NAS..."
$ap = New-Askpass -Pass $Password
Invoke-NasSSH -RemoteCmd "ls -la $REMOTE_RELEASES ; echo '---' ; curl -s http://localhost:8080/health"
Remove-Item $ap -ErrorAction SilentlyContinue

exit 0
