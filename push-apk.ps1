$askpass = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
"@echo @Fnos324" | Set-Content $askpass -Encoding ASCII
$env:SSH_ASKPASS = $askpass
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"

$apkPath = "d:\Projects\Android\app\build\outputs\apk\debug\app-debug.apk"
# v1.6.11: target the data/releases/ directory. The api container
# has this bind-mounted at /data/releases (read-only) and scans it
# for APK files. Naming convention MUST be "app-debug-vX.Y.Z.apk"
# so the release_handler.go can parse the version.
$remotePath = "fnos-momo@192.168.31.234:/vol1/docker/home-datacenter/data/releases/app-debug-v1.6.11.apk"

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
    $apkPath $remotePath 2>&1 | Out-Host

$code = $LASTEXITCODE
Remove-Item $askpass -ErrorAction SilentlyContinue
if ($code -eq 0) {
    Write-Host "APK push successful."
    Write-Host "Verifying file on NAS..."
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
