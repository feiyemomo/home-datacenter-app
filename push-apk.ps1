$askpass = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
"@echo @Fnos324" | Set-Content $askpass -Encoding ASCII
$env:SSH_ASKPASS = $askpass
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"

$apkPath = "d:\Projects\Android\app\build\outputs\apk\debug\app-debug.apk"
$remotePath = "fnos-momo@192.168.31.234:/vol1/docker/home-datacenter/app-debug-v1.6.10.apk"

Write-Host "Pushing APK to NAS..."
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
    & ssh -p 22 `
        -o StrictHostKeyChecking=no `
        -o UserKnownHostsFile=NUL `
        -o ConnectTimeout=10 `
        -o PreferredAuthentications=password `
        -o PubkeyAuthentication=no `
        -o NumberOfPasswordPrompts=1 `
        fnos-momo@192.168.31.234 `
        "ls -la /vol1/docker/home-datacenter/app-debug-v1.6.10.apk" 2>&1 | Out-Host
} else {
    Write-Host "APK push failed with exit code $code"
}
exit $code
