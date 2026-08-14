# backup-keystore.ps1 — Backup the official Android release keystore.
#
# The release keystore (app/keystore/home-release.jks) is the ONLY way to
# sign future updates of the published APK. Losing it (or its passwords)
# permanently bricks the ability to upgrade existing installs. This script
# copies the keystore + credentials to a destination OUTSIDE the git repo
# and writes a restore guide alongside.
#
# NEVER point this at a directory inside a git repo — the keystore and
# keystore.properties are gitignored for a reason. Best practice is to also
# copy the backup to an offline / second-machine location (USB drive, NAS
# cold storage, password manager for the passwords).
#
# Usage:
#   .\backup-keystore.ps1                                # default destination
#   .\backup-keystore.ps1 -Destination "D:\Backups"      # custom destination
#   .\backup-keystore.ps1 -Open                          # open destination after
#   .\backup-keystore.ps1 -Encrypt -Passphrase "..."     # AES-encrypt the keystore
#
# NOTE: -Encrypt needs openssl on PATH (Windows 11 ships it at
# C:\Windows\System32\openssl.exe via the OpenSSH/openssl feature).

[CmdletBinding()]
param(
    # Destination folder (created if missing). Defaults to a sibling of the
    # Android project, clearly outside the git repo.
    [string]$Destination = "D:\Projects\Android-release-backup",
    [switch]$Open,
    [switch]$Encrypt,
    [string]$Passphrase
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$StoreFile = "app\keystore\home-release.jks"
$PropsFile = "keystore.properties"

if (-not (Test-Path (Join-Path $ProjectRoot $StoreFile))) {
    Write-Error "ERROR: release keystore not found at $StoreFile — run the release signing setup first."
    exit 1
}
if (-not (Test-Path (Join-Path $ProjectRoot $PropsFile))) {
    Write-Error "ERROR: keystore.properties not found — cannot back up credentials."
    exit 1
}

New-Item -ItemType Directory -Path $Destination -Force | Out-Null

# Timestamped subfolder so running this repeatedly keeps a restore point.
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = Join-Path $Destination "release-keystore-$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null

Write-Host "==> Backing up release keystore to $backupDir" -ForegroundColor Cyan

if ($Encrypt) {
    if (-not $Passphrase) {
        Write-Error "ERROR: -Encrypt requires -Passphrase."
        exit 1
    }
    $openssl = "C:\Windows\System32\openssl.exe"
    if (-not (Test-Path $openssl)) { $openssl = "openssl" }
    Write-Host "    Encrypting keystore with AES-256-CBC..."
    & $openssl enc -aes-256-cbc -salt -pbkdf2 `
        -in (Join-Path $ProjectRoot $StoreFile) `
        -out (Join-Path $backupDir "home-release.jks.enc") `
        -pass pass:"$Passphrase" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "ERROR: openssl encrypt failed."; exit 1 }
    # Credentials still backed up as plain props (they are the restore key
    # alongside the passphrase; store them separately from the passphrase).
    Copy-Item (Join-Path $ProjectRoot $PropsFile) (Join-Path $backupDir $PropsFile)
    Write-Host "    Encrypted file: home-release.jks.enc (restore with: openssl enc -d -aes-256-cbc -pbkdf2 -in ... )"
} else {
    Copy-Item (Join-Path $ProjectRoot $StoreFile) (Join-Path $backupDir "home-release.jks")
    Copy-Item (Join-Path $ProjectRoot $PropsFile) (Join-Path $backupDir $PropsFile)
    Write-Host "    Copied home-release.jks + keystore.properties"
}

# Write a restore guide into the backup folder.
$guide = @"
RELEASE KEYSTORE BACKUP — RESTORE GUIDE
=======================================
Backup created: $(Get-Date)
Originating project: $ProjectRoot

CONTENTS
  home-release.jks        The official release keystore (private key + cert).
  keystore.properties     Store/key passwords + alias (RELEASE_* entries).
$(if ($Encrypt) { "  home-release.jks.enc  AES-256-CBC encrypted keystore (raw .jks NOT included)." })

WHY THIS MATTERS
  Every published home-datacenter APK is signed with this keystore. Android
  only allows upgrading an installed app when the new APK is signed with the
  SAME key. If this keystore is lost or the passwords are forgotten, the app
  can never receive a signed update again (users would have to uninstall).

SECURITY
  - This folder is OUTSIDE any git repository. Never commit it.
  - Keep it off the machine ideally: copy to a USB drive / separate NAS /
    cloud vault, and store the passwords + passphrase in a password manager.
  - Anyone with this keystore can publish updates under your app identity.

RESTORE (on a new machine / after loss)
  1. Copy home-release.jks back to:  <project>\app\keystore\home-release.jks
  2. Copy keystore.properties back to: <project>\keystore.properties
     (RELEASE_STORE_FILE=keystore/home-release.jks,
      RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD, RELEASE_KEY_PASSWORD)
  3. Verify:  keytool -list -v -keystore app\keystore\home-release.jks \\
               -storepass <RELEASE_STORE_PASSWORD> -alias <RELEASE_KEY_ALIAS>
     The SHA1/SHA256 fingerprints must match the fingerprint of any APK you
     publish (check with: keytool -printcert -jarfile app-release.apk).
  4. Rebuild: ./gradlew assembleRelease
$(if ($Encrypt) { "  To decrypt: openssl enc -d -aes-256-cbc -pbkdf2 -in home-release.jks.enc -out home-release.jks -pass pass:<PASSPHRASE>" })
"@
Set-Content -Path (Join-Path $backupDir "RESTORE-GUIDE.txt") -Value $guide -Encoding UTF8

Write-Host ""
Write-Host "==> Backup complete." -ForegroundColor Green
Write-Host "    Location: $backupDir"
$backedFiles = (Get-ChildItem $backupDir | ForEach-Object { $_.Name }) -join ", "
Write-Host "    Files:    $backedFiles"
Write-Host ""
Write-Host "    IMPORTANT: also copy this backup to an OFFLINE / second machine," -ForegroundColor Yellow
Write-Host "    and store the passwords + passphrase in a password manager." -ForegroundColor Yellow

if ($Open) { Start-Process explorer.exe $backupDir }
exit 0