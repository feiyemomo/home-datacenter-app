$askpass = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
"@echo @Fnos324" | Set-Content $askpass -Encoding ASCII
$env:SSH_ASKPASS = $askpass
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"

Write-Host "Testing /api/v1/release/latest endpoint..."
Write-Host "(should return 401 without JWT, which proves the route exists and is auth-protected)"

& ssh -p 22 `
    -o StrictHostKeyChecking=no `
    -o UserKnownHostsFile=NUL `
    -o ConnectTimeout=10 `
    -o PreferredAuthentications=password `
    -o PubkeyAuthentication=no `
    -o NumberOfPasswordPrompts=1 `
    fnos-momo@192.168.31.234 `
    "echo '--- /api/v1/release/latest (no auth, expect 401) ---' ; curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8080/api/v1/release/latest ; echo '--- /health (expect ok) ---' ; curl -s http://localhost:8080/health ; echo '' ; echo '--- api container logs (last 20 lines) ---' ; docker logs home-api --tail 20 2>&1 | tail -20" 2>&1 | Out-Host

Remove-Item $askpass -ErrorAction SilentlyContinue
