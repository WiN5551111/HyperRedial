# HyperRedial one-click install + verify
#
# Purpose: after Android Studio produces the APK, run this script to
#          install -> restart the Phone app -> capture logs -> decide pass/fail.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\deploy-and-verify.ps1
#
# Notes:
#   * adb must be on PATH, or set $env:ADB to adb.exe
#   * the phone must be connected over USB and unlocked
#   * output is intentionally ASCII-only so it renders in any console codepage

param(
    [string]$Apk,
    [switch]$NoInstall,
    [switch]$SkipWait
)

$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot

function Find-Adb {
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c -and $c.Source) { return $c.Source }
    foreach ($p in '<adb 路径>', "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") {
        if (Test-Path $p) { return $p }
    }
    return $null
}

$adb = Find-Adb
if (-not $adb) {
    Write-Host "[X] adb not found. Install Android SDK platform-tools or set `$env:ADB." -ForegroundColor Red
    exit 1
}
Write-Host ("[*] adb = " + $adb) -ForegroundColor Gray

# ---------------------------------------------------------------- locate APK
if (-not $Apk) {
    $cands = Get-ChildItem $Root -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
             Where-Object { $_.FullName -notmatch '\\in\\' } |
             Sort-Object LastWriteTime -Descending
    if (-not $cands -or $cands.Count -eq 0) {
        Write-Host "[X] No APK found under the project directory." -ForegroundColor Red
        Write-Host "    Build it first: Android Studio -> Build -> Build Bundle(s)/APK(s) -> Build APK(s)" -ForegroundColor Yellow
        Write-Host "    Or pass it explicitly:  -Apk <full path to app-debug.apk>" -ForegroundColor Yellow
        exit 1
    }
    $Apk = $cands[0].FullName
}
if (-not (Test-Path $Apk)) { Write-Host ("[X] APK not found: " + $Apk) -ForegroundColor Red; exit 1 }
$apkSize = [math]::Round((Get-Item $Apk).Length / 1KB, 1)
Write-Host ("[*] APK = " + $Apk + "  (" + $apkSize + " KB)") -ForegroundColor Gray

# ---------------------------------------------------------------- device checks
$devOut = & $adb devices
$hasDevice = ($devOut | Select-String -Pattern "`tdevice$") -ne $null
if (-not $hasDevice) {
    Write-Host "[X] No authorised device. Connect USB, unlock the phone, allow USB debugging." -ForegroundColor Red
    $devOut | ForEach-Object { "    " + $_ }
    exit 1
}
$screen = (& $adb shell "dumpsys deviceidle | grep -i mScreenLocked" 2>&1) -join ' '
if ($screen -match 'mScreenLocked=true') {
    Write-Host "[!] Phone appears locked; log capture may be incomplete. Unlock it if you can." -ForegroundColor Yellow
}

# ---------------------------------------------------------------- install
if (-not $NoInstall) {
    Write-Host "[*] Installing..." -ForegroundColor Cyan
    $out = & $adb install -r $Apk 2>&1
    $out | ForEach-Object { "    " + $_ }
    $outText = $out -join "`n"
    if ($outText -match 'Failure|error') {
        Write-Host "[X] Install failed." -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Installed" -ForegroundColor Green
}

# ---------------------------------------------------------------- LSPosed enable reminder
Write-Host ""
Write-Host "=== LSPosed module must be enabled (check on the phone) ===" -ForegroundColor Cyan
$dbHit = (& $adb shell "su -c 'ls -l /data/adb/lspd/config/modules_config.db'" 2>&1) -join ' '
if ($dbHit -match 'No such file') {
    Write-Host "[!] LSPosed config db not found - is LSPosed installed correctly?" -ForegroundColor Yellow
}
Write-Host "    1) Open LSPosed manager -> Modules -> enable HyperRedial" -ForegroundColor Yellow
Write-Host "    2) Tap HyperRedial -> Scope -> tick ONLY 'Phone' (com.android.contacts)" -ForegroundColor Yellow
Write-Host ""
if (-not $SkipWait -and -not $NoInstall) {
    Read-Host "    Press Enter when done (Ctrl+C to abort)"
}

# ---------------------------------------------------------------- restart + capture
Write-Host "[*] Clearing logcat and restarting the Phone app..." -ForegroundColor Cyan
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am force-stop com.android.contacts 2>&1 | Out-Null
Start-Sleep -Milliseconds 800
& $adb shell am start -a android.intent.action.DIAL -n com.android.contacts/.activities.TwelveKeyDialer 2>&1 | Out-Null
Start-Sleep -Seconds 4

Write-Host "[*] Capturing HyperRedial logs..." -ForegroundColor Cyan
$log = & $adb logcat -d -v brief 2>&1 |
       Select-String -Pattern 'HyperRedial|FATAL EXCEPTION|AndroidRuntime.*com\.android\.contacts' |
       ForEach-Object { $_.Line } |
       Where-Object { $_ -notmatch 'AppsFilter|Finsky|PermissionUtil|AppOpsAdapter|PackageUtil' }
if (-not $log -or $log.Count -eq 0) {
    Write-Host "    (no HyperRedial log lines captured)" -ForegroundColor Yellow
    $log = @()
} else {
    $log | ForEach-Object { "    " + $_ }
}

# ---------------------------------------------------------------- verdict
Write-Host ""
Write-Host "=== Verdict ===" -ForegroundColor Cyan
$joined = ($log -join "`n")
$pass = $true

if ($joined -match 'selfTest:\s*(\d+)/(\d+)\s+targets OK') {
    $n = [int]$Matches[1]; $t = [int]$Matches[2]
    if ($n -eq $t) {
        Write-Host ("[OK] self test passed (" + $n + "/" + $t + ")") -ForegroundColor Green
    } else {
        Write-Host ("[!] self test " + $n + "/" + $t + " - some targets missing (see MISSING lines)") -ForegroundColor Yellow
        $pass = $false
    }
} else {
    Write-Host "[!] No selfTest line - the module was most likely NOT loaded by LSPosed." -ForegroundColor Yellow
    Write-Host "    Check: module enabled? scope includes Phone? Phone app restarted?" -ForegroundColor Yellow
    $pass = $false
}

if ($joined -match 'hooked BaseVH\.onClick') {
    Write-Host "[OK] row-click hook installed (BaseVH.onClick)" -ForegroundColor Green
} elseif ($joined -match 'hooked DialerCallVH\.onClick') {
    Write-Host "[OK] row-click hook installed (legacy DialerCallVH.onClick)" -ForegroundColor Green
} else {
    Write-Host "[!] row-click hook NOT installed" -ForegroundColor Yellow
    $pass = $false
}
if ($joined -match 'selfTest: MISSING') { $pass = $false }

Write-Host ""
if ($pass) {
    Write-Host "Next: tap the BODY of a call-log row on the phone." -ForegroundColor Green
    Write-Host "It should open the detail page instead of dialling." -ForegroundColor Green
    Write-Host "Confirm with these log lines:" -ForegroundColor Green
    Write-Host "    [HyperRedial] row click intercepted -> open detail" -ForegroundColor Green
    Write-Host "    [HyperRedial] openDetail via detail button performClick" -ForegroundColor Green
    Write-Host "And check it did NOT dial:" -ForegroundColor Green
    Write-Host "    adb shell dumpsys window | findstr mCurrentFocus   (expect ...SubActivity)" -ForegroundColor Green
} else {
    Write-Host "Paste all the log lines above back to the AI to get it fixed." -ForegroundColor Yellow
}
