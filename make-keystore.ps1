# Generate a release keystore for HyperRedial and write keystore.properties
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\make-keystore.ps1
#   powershell -ExecutionPolicy Bypass -File .\make-keystore.ps1 -Force
#
# What it does:
#   1. Creates release.jks (a real signing key, NOT the throwaway debug key)
#   2. Writes keystore.properties next to it
#   3. Both are .gitignore'd, so they will never be committed
#
# IMPORTANT:
#   - Back up release.jks and its password. If you lose them you can never
#     publish an update that installs over an existing installation.
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
$prevEAP = $ErrorActionPreference

$root = $PSScriptRoot
$ksFile = Join-Path $root 'release.jks'
$propsFile = Join-Path $root 'keystore.properties'

function Find-Keytool {
    $c = Get-Command keytool -ErrorAction SilentlyContinue
    if ($c -and $c.Source) { return $c.Source }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
    $candidates += @(
        (Join-Path $env:USERPROFILE '.jdks\jbr-21.0.11\bin\keytool.exe'),
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
        'C:\Program Files\Java\jdk-24\bin\keytool.exe'
    )
    foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
    return $null
}

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Host "[X] keytool not found. Set JAVA_HOME to a JDK, or install Android Studio." -ForegroundColor Red
    exit 1
}
Write-Host ("[*] keytool = " + $keytool) -ForegroundColor Gray

# ---------------------------------------------------------------- overwrite guard
if (Test-Path $ksFile) {
    if (-not $Force) {
        Write-Host ("[!] " + $ksFile + " already exists.") -ForegroundColor Yellow
        Write-Host "    Overwriting it DESTROYS your ability to ship updates that install" -ForegroundColor Yellow
        Write-Host "    over existing installs. If you really mean it, re-run with -Force." -ForegroundColor Yellow
        exit 1
    }
    # keytool would try to OPEN an existing file with the NEW password and fail with
    # "keystore password was incorrect / BadPaddingException". Delete it first.
    Write-Host "[!] -Force: removing the existing keystore first" -ForegroundColor Yellow
    Remove-Item $ksFile -Force
    if (Test-Path $ksFile) {
        Write-Host ("[X] could not delete " + $ksFile + " (is it in use?)") -ForegroundColor Red
        exit 1
    }
}

# ---------------------------------------------------------------- password
if (-not $StorePassword) {
    $sec = Read-Host "Enter a password for the keystore" -AsSecureString
    $StorePassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
if (-not $KeyPassword) { $KeyPassword = $StorePassword }
if (-not $StorePassword) {
    Write-Host "[X] empty password" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- generate
Write-Host "[*] generating keystore..." -ForegroundColor Cyan
# keytool writes progress to stderr; with ErrorActionPreference=Stop that would abort
# the script, so relax it temporarily and judge by the exit code instead.
$ErrorActionPreference = 'Continue'
& $keytool -genkeypair -v `
    -keystore $ksFile `
    -storetype PKCS12 `
    -storepass $StorePassword `
    -keypass $KeyPassword `
    -alias $KeyAlias `
    -keyalg RSA -keysize 4096 `
    -validity $ValidityDays `
    -dname $DistinguishedName *>&1 | ForEach-Object { "    " + $_ }
$keytoolExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP

if ($keytoolExit -ne 0 -or -not (Test-Path $ksFile)) {
    Write-Host ("[X] keystore generation failed (keytool exit " + $keytoolExit + ")") -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- properties
$propsLines = @(
    '# Local signing config for HyperRedial. DO NOT COMMIT THIS FILE.'
    '# It is listed in .gitignore. For CI, use repository Secrets instead.'
    ('storeFile=' + $ksFile)
    ('storePassword=' + $StorePassword)
    ('keyAlias=' + $KeyAlias)
    ('keyPassword=' + $KeyPassword)
)
Set-Content -Path $propsFile -Value $propsLines -Encoding ASCII

# ---------------------------------------------------------------- verify
Write-Host "[*] verifying..." -ForegroundColor Cyan
$ErrorActionPreference = 'Continue'
& $keytool -list -keystore $ksFile -storepass $StorePassword *>&1 |
    Select-String -Pattern $KeyAlias | ForEach-Object { "    " + $_ }
$verifyExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP

if ($verifyExit -ne 0) {
    Write-Host "[X] verification failed - the keystore may be unusable" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- report
Write-Host ""
Write-Host "[OK] created:" -ForegroundColor Green
Write-Host ("     " + $ksFile)
Write-Host ("     " + $propsFile)
Write-Host ""
Write-Host "[!] BACK THESE UP. Losing the keystore means you can never ship an" -ForegroundColor Yellow
Write-Host "    update that installs over an existing install." -ForegroundColor Yellow
Write-Host ""
Write-Host "Next step:" -ForegroundColor Cyan
Write-Host "  .\gradlew.bat :app:assembleRelease '-PversionName=1.0.0' '-PversionCode=1'" -ForegroundColor Cyan
