$askpass = [System.IO.Path]::GetTempFileName() + "-askpass.bat"
"@echo @Fnos324" | Set-Content $askpass -Encoding ASCII
$env:SSH_ASKPASS = $askpass
$env:SSH_ASKPASS_REQUIRE = "force"
$env:DISPLAY = "1"

# Write a remote test script to /tmp/test-release.sh on NAS
# Then execute it. This avoids quoting hell passing JSON through
# PowerShell -> ssh -> bash.
$testScript = @'
#!/bin/bash
set -e
echo "=== Bind ==="
RESP=$(curl -s -X POST http://localhost:8080/api/v1/auth/bind \
    -H 'Content-Type: application/json' \
    -d '{"user_id":1,"access_key":"ebc94f7fe99b497a9bdec7bd45add929360e4386be3cad96a8b3922d1d680a05"}')
echo "Bind response: $RESP"
TOKEN=$(echo "$RESP" | grep -oE '"token":"[^"]+"' | sed 's/"token":"//;s/"$//')
echo "Token prefix: ${TOKEN:0:40}..."
echo ""
echo "=== GET /api/v1/release/latest ==="
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/release/latest
echo ""
echo ""
echo "=== HEAD on /api/v1/release/latest/apk (expect 200 + Content-Length) ==="
curl -s -I -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/release/latest/apk | head -5
'@

$tmpLocal = [System.IO.Path]::GetTempFileName() + ".sh"
$testScript | Set-Content $tmpLocal -Encoding ASCII

# scp the script to NAS
$askpass2 = [System.IO.Path]::GetTempFileName() + "-askpass2.bat"
"@echo @Fnos324" | Set-Content $askpass2 -Encoding ASCII
$env:SSH_ASKPASS = $askpass2
$env:SSH_ASKPASS_REQUIRE = "force"
& scp -P 22 -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL `
    -o PreferredAuthentications=password -o PubkeyAuthentication=no `
    -o NumberOfPasswordPrompts=1 `
    $tmpLocal fnos-momo@192.168.31.234:/tmp/test-release.sh 2>&1 | Out-Null
Remove-Item $askpass2 -ErrorAction SilentlyContinue
Remove-Item $tmpLocal -ErrorAction SilentlyContinue

# Execute
$askpass3 = [System.IO.Path]::GetTempFileName() + "-askpass3.bat"
"@echo @Fnos324" | Set-Content $askpass3 -Encoding ASCII
$env:SSH_ASKPASS = $askpass3
$env:SSH_ASKPASS_REQUIRE = "force"
& ssh -p 22 -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL `
    -o PreferredAuthentications=password -o PubkeyAuthentication=no `
    -o NumberOfPasswordPrompts=1 `
    fnos-momo@192.168.31.234 `
    "bash /tmp/test-release.sh ; rm /tmp/test-release.sh" 2>&1 | Out-Host
Remove-Item $askpass3 -ErrorAction SilentlyContinue
Remove-Item $askpass -ErrorAction SilentlyContinue
