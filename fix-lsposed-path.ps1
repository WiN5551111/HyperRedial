# Fix LSPosed module path after updating the HyperRedial APK
#
# Why this is needed:
#   Every time an APK is (re)installed, Android places it in a NEW directory
#   under /data/app/~~xxxx==/com.hyperredial-yyyy==/.  LSPosed stores the
#   module's apk path in its database, so after an update that path is stale
#   and the module silently fails to load (no [HyperRedial] logs at all).
#
# What it does:
#   1. Reads the current apk path from the device (pm path)
#   2. Pulls LSPosed's modules_config.db
#   3. Re-points the modules row (and makes sure enabled=1 + scope is set)
#   4. Pushes it back and optionally reboots
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\fix-lsposed-path.ps1
#   powershell -ExecutionPolicy Bypass -File .\fix-lsposed-path.ps1 -NoReboot
#
# Requires: adb on PATH (or $env:ADB), device connected, root (su) available.
#
# NOTE: a simpler manual alternative that also works:
#   open LSPosed manager -> Modules -> toggle HyperRedial off and on again,
#   then force-stop the Phone app. That makes LSPosed re-read the apk path.

param(
    [string]$Adb = '',
    [switch]$NoReboot,
    [string]$Python = ''
)

$ErrorActionPreference = 'Continue'
$prevEAP = $ErrorActionPreference

function Find-Adb {
    param([string]$Hint)
    if ($Hint -and (Test-Path $Hint)) { return $Hint }
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c -and $c.Source) { return $c.Source }
    foreach ($p in @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    )) { if (Test-Path $p) { return $p } }
    return $null
}

function Find-Python {
    param([string]$Hint)
    if ($Hint -and (Test-Path $Hint)) { return $Hint }
    $c = Get-Command python -ErrorAction SilentlyContinue
    if ($c -and $c.Source) { return $c.Source }
    return $null
}

$adb = Find-Adb -Hint $Adb
if (-not $adb) {
    Write-Host "[X] adb not found. Install platform-tools or set `$env:ADB." -ForegroundColor Red
    exit 1
}
Write-Host ("[*] adb = " + $adb) -ForegroundColor Gray

$py = Find-Python -Hint $Python
if (-not $py) {
    Write-Host "[X] python not found. Install Python (needs only the stdlib sqlite3)." -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- device
$devOut = & $adb devices
if (-not ($devOut | Select-String -Pattern "`tdevice$")) {
    Write-Host "[X] No authorised device." -ForegroundColor Red
    $devOut | ForEach-Object { "    " + $_ }
    exit 1
}

# ---------------------------------------------------------------- apk path
$apkPath = ((& $adb shell "pm path com.hyperredial") -join '').Replace('package:', '').Trim()
if (-not $apkPath) {
    Write-Host "[X] com.hyperredial is not installed on the device." -ForegroundColor Red
    exit 1
}
Write-Host ("[*] current apk path: " + $apkPath) -ForegroundColor Cyan

$ver = ((& $adb shell "dumpsys package com.hyperredial | grep versionName") -join '').Trim()
Write-Host ("[*] " + $ver) -ForegroundColor Gray

# ---------------------------------------------------------------- pull db
$tmpDb = Join-Path $env:TEMP 'hyperredial_modules_config.db'
if (Test-Path $tmpDb) { Remove-Item $tmpDb -Force }

& $adb shell "su -c 'cp /data/adb/lspd/config/modules_config.db /data/local/tmp/mc_fix.db; chmod 666 /data/local/tmp/mc_fix.db'" 2>&1 | Out-Null
$pull = & $adb pull /data/local/tmp/mc_fix.db $tmpDb 2>&1
if (-not (Test-Path $tmpDb)) {
    Write-Host "[X] failed to pull LSPosed database. Is LSPosed installed and is su working?" -ForegroundColor Red
    $pull | ForEach-Object { "    " + $_ }
    exit 1
}
Write-Host ("[*] pulled database -> " + $tmpDb) -ForegroundColor Gray

# ---------------------------------------------------------------- patch db
$pyScript = @'
import sqlite3, sys

db = sys.argv[1]
pkg = sys.argv[2]
apk = sys.argv[3]
target = 'com.android.contacts'
uid = 0

con = sqlite3.connect(db)
cur = con.cursor()

before = cur.execute('SELECT apk_path FROM modules WHERE module_pkg_name=?', (pkg,)).fetchone()
print('  before apk_path:', before[0] if before else None)
print('  wanted apk_path:', apk)

cur.execute('INSERT OR REPLACE INTO modules (module_pkg_name, apk_path) VALUES (?, ?)', (pkg, apk))
cur.execute('INSERT OR REPLACE INTO modules_state '
            '(module_pkg_name, user_id, enabled, scope_request_blocked) VALUES (?, ?, 1, 0)',
            (pkg, uid))
cur.execute('INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) '
            'VALUES (?, ?, ?)', (pkg, target, uid))
con.commit()

after = cur.execute('SELECT apk_path FROM modules WHERE module_pkg_name=?', (pkg,)).fetchone()
print('  after  apk_path:', after[0] if after else None)
print('  integrity_check:', cur.execute('PRAGMA integrity_check').fetchone()[0])
con.close()

changed = (before[0] if before else None) != apk
sys.exit(0 if changed else 10)
'@

$pyFile = Join-Path $env:TEMP 'fix_lsposed_path.py'
Set-Content -Path $pyFile -Value $pyScript -Encoding UTF8

Write-Host "[*] patching database..." -ForegroundColor Cyan
& $py $pyFile $tmpDb 'com.hyperredial' $apkPath
$patchExit = $LASTEXITCODE

if ($patchExit -eq 10) {
    Write-Host "[OK] path was already correct - nothing to change." -ForegroundColor Green
} elseif ($patchExit -ne 0) {
    Write-Host ("[X] patch failed (exit " + $patchExit + ")") -ForegroundColor Red
    exit 1
} else {
    Write-Host "[OK] database patched" -ForegroundColor Green
}

# ---------------------------------------------------------------- push db back
Write-Host "[*] pushing database back..." -ForegroundColor Cyan
& $adb push $tmpDb /data/local/tmp/mc_fix_new.db 2>&1 | Out-Null
& $adb shell "su -c 'cp /data/local/tmp/mc_fix_new.db /data/adb/lspd/config/modules_config.db; chown root:root /data/adb/lspd/config/modules_config.db; chmod 666 /data/adb/lspd/config/modules_config.db; rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm'" 2>&1 | Out-Null
Write-Host "[OK] pushed" -ForegroundColor Green

# ---------------------------------------------------------------- reboot
if ($NoReboot) {
    Write-Host ""
    Write-Host "[!] -NoReboot given: LSPosed will NOT pick up the change until you reboot," -ForegroundColor Yellow
    Write-Host "    or toggle the module off/on in the LSPosed manager." -ForegroundColor Yellow
    exit 0
}

Write-Host ""
Write-Host "[*] rebooting so LSPosed reloads the module (about 60-90s)..." -ForegroundColor Cyan
& $adb reboot 2>&1 | Out-Null

Start-Sleep -Seconds 50
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    if ((((& $adb get-state 2>&1) -join '') -match 'device')) {
        if ((((& $adb shell "getprop sys.boot_completed") -join '').Trim()) -eq '1') { break }
    }
    Start-Sleep -Seconds 5
}
Write-Host "[OK] device is back" -ForegroundColor Green

Start-Sleep -Seconds 8
& $adb shell am force-stop com.android.contacts 2>&1 | Out-Null
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am start -a android.intent.action.DIAL -n com.android.contacts/.activities.TwelveKeyDialer 2>&1 | Out-Null
Start-Sleep -Seconds 6

$log = & $adb logcat -d 2>&1 | Select-String -Pattern 'HyperRedial' | ForEach-Object { $_.Line }
Write-Host ""
if ($log) {
    Write-Host "=== module loaded ===" -ForegroundColor Green
    $log | ForEach-Object { "    " + (($_ -split 'HyperRedial\] ')[-1]) }
} else {
    Write-Host "[!] No [HyperRedial] log lines - module still not loaded." -ForegroundColor Yellow
    Write-Host "    Check: is the module enabled in LSPosed? is the scope set to Phone?" -ForegroundColor Yellow
}
