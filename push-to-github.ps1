# Push HyperRedial to GitHub
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\push-to-github.ps1
#
# Optional:
#   -Token <PAT>     GitHub Personal Access Token (repo scope).
#                    If omitted, git will prompt for credentials.
#   -Proxy <url>     HTTP proxy, e.g. http://127.0.0.1:8080
#   -RemoteUrl <url> Override the target repository URL.
#
# Why this script exists:
#   On some Windows setups git's default TLS backend (schannel) fails with
#     "schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS"
#   Using the OpenSSL backend avoids that. The script sets it automatically.

param(
    [string]$Token,
    [string]$Proxy,
    [string]$RemoteUrl = 'https://github.com/WiN5551111/HyperRedial.git',
    [string]$Git = ''
)

$ErrorActionPreference = 'Continue'

function Find-Git {
    param([string]$Hint)
    # 1) explicit hint
    if ($Hint -and (Test-Path $Hint)) { return $Hint }
    # 2) $env:GIT
    if ($env:GIT -and (Test-Path $env:GIT)) { return $env:GIT }
    # 3) PATH
    $c = Get-Command git -ErrorAction SilentlyContinue
    if ($c -and $c.Source) { return $c.Source }
    # 4) standard installer locations
    foreach ($p in @(
        (Join-Path $env:ProgramFiles 'Git\cmd\git.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\cmd\git.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\cmd\git.exe')
    )) { if ($p -and (Test-Path $p)) { return $p } }
    # 5) portable Git (PortableGit* folder 1-2 levels below a drive root or a tools dir).
    #    This is generic - it does not hardcode any machine-specific path.
    $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)
    foreach ($d in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
        if ($d.Root) { $roots += $d.Root.TrimEnd('\') }
    }
    foreach ($root in ($roots | Where-Object { $_ } | Select-Object -Unique)) {
        if (-not (Test-Path $root)) { continue }
        # depth 1: <root>\PortableGit\cmd\git.exe
        foreach ($dir in (Get-ChildItem -Path $root -Filter 'PortableGit*' -Directory -ErrorAction SilentlyContinue)) {
            $p = Join-Path $dir.FullName 'cmd\git.exe'
            if (Test-Path $p) { return $p }
        }
        # depth 2: <root>\<appdir>\PortableGit\cmd\git.exe  (e.g. D:\Tools\PortableGit)
        foreach ($sub in (Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue)) {
            if ($sub.Name -match '^(Windows|Program Files.*|Users|System Volume.*|Recovery|PerfLogs)$') { continue }
            foreach ($dir in (Get-ChildItem -Path $sub.FullName -Filter 'PortableGit*' -Directory -ErrorAction SilentlyContinue)) {
                $p = Join-Path $dir.FullName 'cmd\git.exe'
                if (Test-Path $p) { return $p }
            }
        }
    }
    return $null
}

# Work from the repository root.
# Why: PowerShell's `cd` only changes the *PowerShell* location, not the process
# working directory, so native git.exe can end up running in the wrong folder and
# fail with "fatal: not a git repository".
Set-Location -LiteralPath $PSScriptRoot

$git = Find-Git -Hint $Git
if (-not $git) {
    Write-Host "[X] git not found. Install Git for Windows, or pass -Git <path to git.exe>" -ForegroundColor Red
    exit 1
}
Write-Host ("[*] git = " + $git) -ForegroundColor Gray

$root = $PSScriptRoot
if (-not (Test-Path (Join-Path $root '.git'))) {
    Write-Host "[X] Not a git repository: $root" -ForegroundColor Red
    Write-Host "    (the .git folder is missing)" -ForegroundColor Yellow
    exit 1
}

# ------------------------------------------------------------------ settings
# OpenSSL TLS backend: avoids schannel SEC_E_NO_CREDENTIALS on some machines.
$gitArgs = @('-c', 'http.sslBackend=openssl')
if ($Proxy) {
    $gitArgs += @('-c', "http.proxy=$Proxy")
    Write-Host ("[*] using proxy: " + $Proxy) -ForegroundColor Gray
}

function Run-Git {
    param([string[]]$Extra)
    $all = $gitArgs + $Extra
    & $git @all 2>&1
}

# ------------------------------------------------------------------ identity check
$name = (Run-Git @('config', 'user.name')) -join ''
$mail = (Run-Git @('config', 'user.email')) -join ''
if (-not $name.Trim()) {
    Write-Host "[!] git user.name is not set; setting a local one." -ForegroundColor Yellow
    Run-Git @('config', 'user.name', 'HyperRedial') | Out-Null
}
if (-not $mail.Trim()) {
    Write-Host "[!] git user.email is not set; setting a local one." -ForegroundColor Yellow
    Run-Git @('config', 'user.email', 'noreply@example.com') | Out-Null
}

# ------------------------------------------------------------------ remote
$remotes = (Run-Git @('remote')) -join "`n"
$hasOrigin = ($remotes -split "`n") -contains 'origin'
if ($hasOrigin) {
    $existing = ((Run-Git @('remote', 'get-url', 'origin')) -join '').Trim()
    Write-Host ("[*] origin already set to: " + $existing) -ForegroundColor Gray
    if ($existing -ne $RemoteUrl) {
        Write-Host ("[*] updating origin -> " + $RemoteUrl) -ForegroundColor Gray
        Run-Git @('remote', 'set-url', 'origin', $RemoteUrl) | Out-Null
    }
} else {
    Write-Host ("[*] adding origin -> " + $RemoteUrl) -ForegroundColor Gray
    Run-Git @('remote', 'add', 'origin', $RemoteUrl) | Out-Null
}

# ------------------------------------------------------------------ review before pushing
Write-Host ""
Write-Host "=== Working tree status ===" -ForegroundColor Cyan
Run-Git @('status', '--short', '--branch') | ForEach-Object { "    $_" }

Write-Host ""
Write-Host "=== Commits about to be pushed ===" -ForegroundColor Cyan
Run-Git @('log', '--oneline', '-n', '5') | ForEach-Object { "    $_" }

Write-Host ""
Write-Host "=== Safety check: nothing sensitive staged? ===" -ForegroundColor Cyan
$tracked = (Run-Git @('ls-files')) -join "`n"
$bad = @()
foreach ($pat in @('local\.properties', '\.jks$', '\.keystore$', '\.apk$', '^\.kotlin/', '^\.idea/', '^\.gradle/')) {
    $hit = ($tracked -split "`n") | Where-Object { $_ -match $pat }
    if ($hit) { $bad += $hit }
}
if ($bad.Count -gt 0) {
    Write-Host "[X] These files are tracked and should NOT be published:" -ForegroundColor Red
    $bad | ForEach-Object { "      $_" }
    Write-Host "    Remove them:  git rm --cached <file>" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] no sensitive files are tracked" -ForegroundColor Green

# ------------------------------------------------------------------ push
Write-Host ""
if ($Token) {
    # Embed the token only for this one command; it is not written to .git/config.
    $uri = [Uri]$RemoteUrl
    $authUrl = "https://$Token@$($uri.Host)$($uri.AbsolutePath)"
    Write-Host "[*] pushing to $RemoteUrl (with token)" -ForegroundColor Cyan
    Run-Git @('push', $authUrl, 'HEAD:refs/heads/main') | ForEach-Object { "    $_" }
    Write-Host ""
    Write-Host "[!] Remember to revoke/rotate that token when you are done." -ForegroundColor Yellow
} else {
    Write-Host "[*] pushing to origin main (git will ask for credentials)" -ForegroundColor Cyan
    Write-Host "    username: your GitHub username" -ForegroundColor Gray
    Write-Host "    password: a Personal Access Token (NOT your account password)" -ForegroundColor Gray
    Write-Host "              create one at https://github.com/settings/tokens  (scope: repo)" -ForegroundColor Gray
    Run-Git @('push', '-u', 'origin', 'main') | ForEach-Object { "    $_" }
}

Write-Host ""
Write-Host "=== Done. Verify at: $($RemoteUrl -replace '\.git$','') ===" -ForegroundColor Green
