# release.ps1 — one-click release flow: build debug APK + push to NAS.
#
# Pipeline:
#   1. Read versionName from app\build.gradle.kts
#   2. Run .\gradlew assembleDebug (debug APK, matches push-apk.ps1)
#   3. Invoke the existing push-apk.ps1 in a child process
#   4. Verify the uploaded file exists on the NAS via SSH (ls -la)
#
# User-facing progress is printed in Chinese; code comments are in
# English to match the rest of the repo.

[CmdletBinding()]
param(
    [ValidateSet("debug", "release")]
    [string]$Flavor = "release",
    [string]$Password = $env:NAS_PASSWORD,
    [string]$Host = $env:NAS_HOST,
    [switch]$WhatIf
)

# Working directory = this script's directory (so gradlew / push-apk.ps1
# resolve regardless of where release.ps1 is invoked from).
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# Build needs JDK 21, which ships with the Android Studio bundled JBR.
$env:JAVA_HOME = "D:\AndroidStudio\jbr"
if (Test-Path $env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
} else {
    Write-Host "[警告] 未找到 JAVA_HOME=$env:JAVA_HOME，构建可能失败。" -ForegroundColor Yellow
}

# Helpers -----------------------------------------------------------------
function Write-Step([string]$Msg) {
    Write-Host ""
    Write-Host "==== $Msg ====" -ForegroundColor Cyan
}
function Write-Ok([string]$Msg)   { Write-Host "[OK]    $Msg" -ForegroundColor Green }
function Write-Warn([string]$Msg) { Write-Host "[警告]  $Msg" -ForegroundColor Yellow }
function Write-Err([string]$Msg)  { Write-Host "[错误]  $Msg" -ForegroundColor Red }

# NAS connection constants (must match push-apk.ps1) --------------------
$nasHost   = if ($Host) { $Host } else { "fnos-momo@<NAS_IP>" }
$nasPort   = 22
$nasPass   = if ($Password) { $Password } else { "" }
$releasesDir = "/vol1/docker/home-datacenter/data/releases"

# ---------- Step 1: read versionName from build.gradle.kts -------------
Write-Step "步骤 1/4：读取版本号"

$buildGradlePath = Join-Path $scriptDir "app\build.gradle.kts"
if (-not (Test-Path $buildGradlePath)) {
    Write-Err "未找到 build.gradle.kts：$buildGradlePath"
    exit 1
}

$buildGradle = Get-Content $buildGradlePath -Raw
$versionMatch = [regex]::Match($buildGradle, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) {
    Write-Err "无法从 build.gradle.kts 解析 versionName"
    exit 1
}
$version = $versionMatch.Groups[1].Value
Write-Ok "检测到版本号：$version"

# ---------- Step 2: build APK ------------------------------------------
$taskName = "assemble" + ($Flavor.Substring(0,1).ToUpper() + $Flavor.Substring(1).ToLower())
Write-Step "步骤 2/4：构建 $Flavor APK（$taskName）"

$gradlew = Join-Path $scriptDir "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Err "未找到 gradlew.bat：$gradlew"
    exit 1
}

if ($WhatIf) {
    Write-Warn "WhatIf 模式：跳过实际构建。"
    Write-Host "        将执行：& $gradlew $taskName"
} else {
    Write-Host "正在执行 gradlew $taskName（可能需要数分钟，请耐心等待）..."
    & $gradlew $taskName 2>&1 | Out-Host
    $buildExit = $LASTEXITCODE
    if ($null -eq $buildExit) { $buildExit = 0 }
    if ($buildExit -ne 0) {
        Write-Err "构建失败（退出码 $buildExit），不执行推送。"
        exit $buildExit
    }
    Write-Ok "构建成功"
}

# Confirm the build artifact exists before attempting to push.
$apkPath = Join-Path $scriptDir "app\build\outputs\apk\$Flavor\app-$Flavor.apk"
if (-not (Test-Path $apkPath)) {
    Write-Err "未找到构建产物：$apkPath"
    exit 1
}

# ---------- Step 3: push APK via existing push-apk.ps1 -----------------
Write-Step "步骤 3/4：推送到 NAS"

$pushScript = Join-Path $scriptDir "push-apk.ps1"
if (-not (Test-Path $pushScript)) {
    Write-Err "未找到推送脚本：$pushScript"
    exit 1
}

if ($WhatIf) {
    Write-Warn "WhatIf 模式：跳过实际推送。"
    Write-Host "        将调用：powershell -ExecutionPolicy Bypass -File $pushScript -Flavor $Flavor"
} else {
    # Run push-apk.ps1 in a CHILD powershell process. push-apk.ps1 ends
    # with `exit <code>`, which would otherwise terminate this script too
    # if invoked in-process. A child process isolates the exit and lets
    # us capture $LASTEXITCODE here.
    $pushArgs = @("-Flavor", $Flavor)
    if ($nasPass) { $pushArgs += @("-Password", $nasPass) }
    & powershell -ExecutionPolicy Bypass -File $pushScript @pushArgs 2>&1 | Out-Host
    $pushExit = $LASTEXITCODE
    if ($null -eq $pushExit) { $pushExit = 0 }
    if ($pushExit -ne 0) {
        Write-Err "推送失败（退出码 $pushExit）。APK 已构建，不执行 rollback。"
        Write-Warn "可手动重跑 push-apk.ps1，无需重新构建。"
        exit $pushExit
    }
    Write-Ok "推送完成"
}

# ---------- Step 4: verify the file landed on the NAS ------------------
Write-Step "步骤 4/4：验证 NAS 上文件"

$remoteFile = "$releasesDir/app-debug-v$version.apk"

if ($WhatIf) {
    Write-Warn "WhatIf 模式：跳过 SSH 验证。"
    Write-Host "        将检查：$remoteFile"
} else {
    # SSH_ASKPASS mechanism (same pattern as push-apk.ps1) so ssh can
    # fetch the password non-interactively if password is provided.
    $askpass = $null
    if ($nasPass) {
        $askpass = [System.IO.Path]::GetTempFileName() + "-release-askpass.bat"
        "@echo $nasPass" | Set-Content $askpass -Encoding ASCII
        $env:SSH_ASKPASS = $askpass
        $env:SSH_ASKPASS_REQUIRE = "force"
        $env:DISPLAY = "1"
    }

    Write-Host "正在通过 SSH 检查：$remoteFile"
    # ssh propagates the remote command's exit status, so $LASTEXITCODE
    # reflects whether `ls` succeeded (file present) — no need to parse
    # a marker string.
    $verifyOutput = & ssh -p $nasPort `
        -o StrictHostKeyChecking=no `
        -o UserKnownHostsFile=NUL `
        -o ConnectTimeout=10 `
        -o PreferredAuthentications=password `
        -o PubkeyAuthentication=no `
        -o NumberOfPasswordPrompts=1 `
        $nasHost `
        "ls -la $remoteFile" 2>&1
    $sshExit = $LASTEXITCODE
    if ($null -eq $sshExit) { $sshExit = 0 }

    Remove-Item $askpass -ErrorAction SilentlyContinue
    $verifyOutput | Out-Host

    if ($sshExit -eq 0) {
        Write-Ok "NAS 上已确认文件存在：$remoteFile"
    } else {
        Write-Err "NAS 上未找到文件：$remoteFile（ssh 退出码 $sshExit）"
        Write-Warn "推送脚本可能已上传成功，但验证未通过——请手动检查。"
        Write-Warn "不执行 rollback（APK 仍保留在 NAS 上）。"
        exit $sshExit
    }
}

# ---------- Summary ----------------------------------------------------
Write-Step "发布完成"
Write-Ok "版本号：$version"
Write-Ok "本地 APK：$apkPath"
Write-Ok "NAS 路径：${nasHost}:$remoteFile"
exit 0
