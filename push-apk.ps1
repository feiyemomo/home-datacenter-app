$askpass = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
"@echo @Fnos324" | Set-Content $askpass -Encoding ASCII
$env:SSH_ASKPASS = $askpass
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"

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
Write-Host "Detected version: $version"

$apkPath = "d:\Projects\Android\app\build\outputs\apk\debug\app-debug.apk"
# v1.6.11: target the data/releases/ directory. The api container
# has this bind-mounted at /data/releases (read-only) and scans it
# for APK files. Naming convention MUST be "app-debug-vX.Y.Z.apk"
# so the release_handler.go can parse the version.
# v1.6.12: also push release-notes-vX.Y.Z.txt alongside the APK —
# the backend reads this file and returns its contents as
# release_notes in the /api/v1/release/latest response.
$remoteApkPath = "fnos-momo@192.168.31.234:/vol1/docker/home-datacenter/data/releases/app-debug-v$version.apk"
$localNotesPath = "d:\Projects\Android\release-notes-v$version.txt"
$remoteNotesPath = "fnos-momo@192.168.31.234:/vol1/docker/home-datacenter/data/releases/release-notes-v$version.txt"

Write-Host "Pushing APK to NAS releases directory..."
# Ensure the remote directory exists (mkdir -p is idempotent)
$askpass2 = [System.IO.Path]::GetTempFileName() + "-askpass2.bat"
"@echo @Fnos324" | Set-Content $askpass2 -Encoding ASCII
$env:SSH_ASKPASS = $askpass2
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"
& ssh -p 22 `
    -o StrictHostKeyChecking=no `
    -o UserKnownHostsFile=NUL `
    -o ConnectTimeout=10 `
    -o PreferredAuthentications=password `
    -o PubkeyAuthentication=no `
    -o NumberOfPasswordPrompts=1 `
    fnos-momo@192.168.31.234 `
    "mkdir -p /vol1/docker/home-datacenter/data/releases" 2>&1 | Out-Host
Remove-Item $askpass2 -ErrorAction SilentlyContinue

# Re-setup askpass for scp (was cleaned above)
$askpass = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
"@echo @Fnos324" | Set-Content $askpass -Encoding ASCII
$env:SSH_ASKPASS = $askpass
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"

& scp -P 22 `
    -o StrictHostKeyChecking=no `
    -o UserKnownHostsFile=NUL `
    -o ConnectTimeout=10 `
    -o PreferredAuthentications=password `
    -o PubkeyAuthentication=no `
    -o NumberOfPasswordPrompts=1 `
    $apkPath $remoteApkPath 2>&1 | Out-Host

$code = $LASTEXITCODE
Remove-Item $askpass -ErrorAction SilentlyContinue

if ($code -eq 0) {
    Write-Host "APK push successful."

    # v1.6.12: push release notes if the file exists
    if (Test-Path $localNotesPath) {
        Write-Host "Pushing release notes..."
        $askpassNotes = [System.IO.Path]::GetTempFileName() + "-askpass-notes.bat"
        "@echo @Fnos324" | Set-Content $askpassNotes -Encoding ASCII
        $env:SSH_ASKPASS = $askpassNotes
        $env:SSH_ASKPASS_REQUIRE = "force"
        $env:DISPLAY = "1"
        & scp -P 22 `
            -o StrictHostKeyChecking=no `
            -o UserKnownHostsFile=NUL `
            -o ConnectTimeout=10 `
            -o PreferredAuthentications=password `
            -o PubkeyAuthentication=no `
            -o NumberOfPasswordPrompts=1 `
            $localNotesPath $remoteNotesPath 2>&1 | Out-Host
        Remove-Item $askpassNotes -ErrorAction SilentlyContinue
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Release notes push successful."
        } else {
            Write-Host "Release notes push failed (non-fatal)."
        }
    } else {
        Write-Host "No release-notes-v$version.txt found — skipping notes upload."
    }

    Write-Host "Verifying files on NAS..."
    $askpass3 = [System.IO.Path]::GetTempFileName() + "-askpass3.bat"
    "@echo @Fnos324" | Set-Content $askpass3 -Encoding ASCII
    $env:SSH_ASKPASS = $askpass3
    $env:SSH_ASKPASS_REQUIRE = "force"
    $env:DISPLAY = "1"
    & ssh -p 22 `
        -o StrictHostKeyChecking=no `
        -o UserKnownHostsFile=NUL `
        -o ConnectTimeout=10 `
        -o PreferredAuthentications=password `
        -o PubkeyAuthentication=no `
        -o NumberOfPasswordPrompts=1 `
        fnos-momo@192.168.31.234 `
        "ls -la /vol1/docker/home-datacenter/data/releases/ ; echo '---' ; curl -s http://localhost:8080/health" 2>&1 | Out-Host
    Remove-Item $askpass3 -ErrorAction SilentlyContinue
} else {
    Write-Host "APK push failed with exit code $code"
}
exit $code
