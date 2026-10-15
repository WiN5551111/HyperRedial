# Generate a release keystore for HyperRedial and write keystore.properties
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\make-keystore.ps1
#
# What it does:
#   1. Creates release.jks (a real signing key, NOT the throwaway debug key)
#   2. Writes keystore.properties next to it
#   3. Both are already .gitignore'd, so they will never be committed
#
# IMPORTANT:
#   - Back up release.jks and its passwords. If you lose them you can never
#     publish an update that installs over an existing installation; users
#     would have to uninstall first.
#   - Do NOT commit release.jks or keystore.properties to a public repo.
#     For GitHub Actions, put the values into repository Secrets instead
#     (see docs/RELEASE.md).

param(
    [string]$StorePassword,
    [string]$KeyAlias = 'hyperredial',
    [string]$KeyPassword,
    [string]$DistinguishedName = 'CN=HyperRedial, OU=OpenSource, O=HyperRedial, C=CN',
    [int]$ValidityDays = 10950,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$ksFile = Join-Path $root 'release.jks'
$propsFile = Join-Path $root 'keystore.properties'

function Find-Keytool {
    $c = Get-Command keytool -ErrorAction SilentlyContinue
    if ($c -and $c.Source) { return $c.Source }
    foreach ($p in @(
        "$env:JAVA_HOME\bin\keytool.exe",
        "$env:USERPROFILE\.jdks\jbr-21.0.11\bin\keytool.exe",
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
        'C:\Program Files\Java\jdk-24\bin\keytool.exe'
    )) { if (Test-Path $p) { return $p } }
    return $null
}

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Host "[X] keytool not found. Set JAVA_HOME to a JDK, or install Android Studio." -ForegroundColor Red
    exit 1
}
Write-Host ("[*] keytool = " + $keytool) -ForegroundColor Gray

if ((Test-Path $ksFile) -and -not $Force) {
    Write-Host ("[!] $ksFile already exists. Use -Force to overwrite (DANGEROUS: you would lose the ability to update).") -ForegroundColor Yellow
    exit 1
}

if (-not $StorePassword) {
    $sec = Read-Host "Enter a password for the keystore" -AsSecureString
    $StorePassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
if (-not $KeyPassword) { $KeyPassword = $StorePassword }
if (-not $StorePassword) { Write-Host "[X] empty password" -ForegroundColor Red; exit 1 }

Write-Host "[*] generating keystore..." -ForegroundColor Cyan
# keytool 把进度信息写到 stderr，直接用 & 会被 PowerShell 当成错误中断脚本，
# 所以这里临时放宽错误策略，并按退出码判断成败。
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $keytool -genkeypair -v `
    -keystore $ksFile `
    -storetype PKCS12 `
    -storepass $StorePassword `
    -keypass $KeyPassword `
    -alias $KeyAlias `
    -keyalg RSA -keysize 4096 `
    -validity $ValidityDays `
    -dname $DistinguishedName *>&1 | ForEach-Object { "    $_" }
$keytoolExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP

if ($keytoolExit -ne 0 -or -not (Test-Path $ksFile)) {
    Write-Host "[X] keystore generation failed (keytool exit $keytoolExit)" -ForegroundColor Red
    exit 1
}

@"
# Local signing config for HyperRedial. DO NOT COMMIT THIS FILE.
# It is listed in .gitignore. For CI, use repository Secrets instead.
storeFile=release.jks
storePassword=$StorePassword
keyAlias=$KeyAlias
keyPassword=$KeyPassword
"@ | Set-Content $propsFile -Encoding ASCII

Write-Host ""
Write-Host "[OK] created:" -ForegroundColor Green
Write-Host ("     " + $ksFile)
Write-Host ("     " + $propsFile)
Write-Host ""
Write-Host "[!] BACK THESE UP. Losing the keystore means you can never ship an" -ForegroundColor Yellow
Write-Host "    update that installs over an existing install." -ForegroundColor Yellow
Write-Host ""
Write-Host "Next:  .\gradlew.bat :app:assembleRelease" -ForegroundColor Cyan
