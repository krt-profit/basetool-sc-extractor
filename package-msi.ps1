# Builds the Windows MSI installer with a modern WiX toolset (v4+) under JDK 25.
#
# Why this wrapper exists:
#   jpackage supports WiX 4+ since JDK 24 (JDK-8319457): it runs the first `wix.exe`
#   it finds on PATH and needs the WixToolset.Util.wixext + WixToolset.UI.wixext
#   extensions in the global extension cache. Three pitfalls this script handles —
#   without changing anything on the system (PATH edits are process-scoped only):
#
#   1. Mixed WiX majors. jpackage passes the extensions UNVERSIONED
#      (`-ext WixToolset.Util.wixext`), and wix.exe resolves that to the HIGHEST
#      version in the cache. If extensions of a newer major are cached (e.g. v7
#      next to a v6 toolset), an older wix.exe picks them, can't load them, and
#      dies with error WIX0144 / exit code 144 — long misread as a jpackage bug
#      (JDK-8356592, closed as "Not an Issue"). => the build is PINNED to WiX 7
#      (newest installed 7.x), which goes first on PATH.
#   2. OSMF EULA. WiX v7+ refuses every real command (error WIX7015) until
#      `wix eula accept wix<major>` was run once for the current user (creates
#      ~\.wix\wix<major>-osmf-eula.txt). On a dev machine this script never accepts
#      it silently — it tells you what to run. On CI ($env:CI = 'true') it accepts
#      automatically (project decision, see issue krt-profit/basetool-sc-extractor#1).
#   3. Bare machines / CI runners. If no WiX 7 is installed, WiX is bootstrapped
#      as a LOCAL dotnet tool under tools\wix (gitignored; nothing system-wide),
#      pinned to an exact version whose NuGet package must match a pinned SHA-512
#      before wix.exe is ever run, and missing Util/UI extensions are added to the
#      user's extension cache. The GitHub runner image carries no WiX 7, so every
#      release build takes this path - the pin is what verifies the CI download too.
#
# Usage:  .\package-msi.ps1            # builds dist\...-<version>.msi
#         .\package-msi.ps1 --info     # extra args are forwarded to Gradle
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# The build is pinned to WiX 7 (issue #1); bump deliberately (version AND hash together),
# then re-verify the build.
$wixRequiredMajor = 7
$wixBootstrapVersion = "7.0.0"   # installed as a local dotnet tool when no WiX 7 is found
# SHA-512 (base64) of wix.7.0.0.nupkg - the nuget.org catalog's `packageHash`, checked
# 2026-09-23 against the package dotnet actually downloaded. Not the `.nupkg.sha512` file
# NuGet writes beside it: that is NuGet's content hash, which leaves the repository
# signature out and differs from the file's own hash.
$wixBootstrapSha512 = "4GbdoDWjm0QmBLyJ8PPiknXomjI5vHat4H27AB6NNGzFpbEhN9Xw0kiPQokZ3zH8F1RcjvDSn8yGDXwKxlW8Tg=="
$wixToolDir = Join-Path $PSScriptRoot 'tools\wix'

function Get-WixInfo([string]$exe) {
    # `wix --version` is not gated behind the OSMF EULA, so this works pre-acceptance.
    try { $raw = (& $exe --version 2>$null | Select-Object -First 1) } catch { return $null }
    if ($raw -match '^(\d+\.\d+\.\d+)') {
        [pscustomobject]@{ Exe = $exe; Dir = (Split-Path $exe); Version = [version]$Matches[1] }
    }
}

function Get-FileSha512Base64([string]$path) {
    # .NET directly rather than Get-FileHash: a Windows PowerShell 5.1 that inherits a
    # PowerShell 7 PSModulePath cannot load Get-FileHash's module (#59).
    $sha = [System.Security.Cryptography.SHA512]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { [Convert]::ToBase64String($sha.ComputeHash($stream)) } finally { $stream.Dispose(); $sha.Dispose() }
}

function Test-WixBootstrap {
    # True only when tools\wix holds exactly the pinned version and its package matches the pin.
    $nupkg = Join-Path $wixToolDir ".store\wix\$wixBootstrapVersion\wix\$wixBootstrapVersion\wix.$wixBootstrapVersion.nupkg"
    if (-not (Test-Path $nupkg)) { return $false }
    $actual = Get-FileSha512Base64 $nupkg
    if ($actual -ne $wixBootstrapSha512) {
        Write-Warning "tools\wix: wix.$wixBootstrapVersion.nupkg has SHA-512 $actual, expected $wixBootstrapSha512."
        return $false
    }
    return $true
}

# --- 1. Pick WiX 7: the newest installed 7.x on PATH, else the verified local bootstrap -
# A tools\wix bootstrap no longer competes with an installed WiX: it is only used - and
# only after its package matched the pin - when no WiX 7 is on PATH.
$wix = @($env:PATH -split ';' | Where-Object { $_ } | ForEach-Object { Join-Path $_ 'wix.exe' }) |
    Where-Object { Test-Path $_ } | ForEach-Object { Get-WixInfo $_ } |
    Where-Object { $_ -and $_.Version.Major -eq $wixRequiredMajor } |
    Sort-Object Version -Descending | Select-Object -First 1

if (-not $wix) {
    $localExe = Join-Path $wixToolDir 'wix.exe'
    if ((Test-Path $localExe) -and -not (Test-WixBootstrap)) {
        Write-Host "tools\wix is not the pinned, verified WiX $wixBootstrapVersion - removing it and bootstrapping afresh."
        Remove-Item $wixToolDir -Recurse -Force
    }
    if (-not (Test-Path $localExe)) {
        if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
            throw ("No WiX $wixRequiredMajor found and no .NET SDK to bootstrap one. Install WiX v$wixRequiredMajor " +
                "(https://wixtoolset.org) or the .NET SDK, then re-run.")
        }
        Write-Host "No WiX $wixRequiredMajor found - installing WiX $wixBootstrapVersion as a local dotnet tool (tools\wix)..."
        dotnet tool install wix --tool-path $wixToolDir --version $wixBootstrapVersion | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "dotnet tool install wix --version $wixBootstrapVersion failed." }
        if (-not (Test-WixBootstrap)) {
            Remove-Item $wixToolDir -Recurse -Force -ErrorAction SilentlyContinue
            throw ("The downloaded wix.$wixBootstrapVersion.nupkg does not match the pinned SHA-512 - refusing to run it " +
                "(tools\wix removed). Do not just update the pin: find out why the package differs first.")
        }
    }
    $wix = Get-WixInfo $localExe
    if (-not $wix -or $wix.Version.Major -ne $wixRequiredMajor) {
        throw "Bootstrapped WiX in tools\wix did not report a usable $wixRequiredMajor.x version."
    }
}
Write-Host "Using WiX $($wix.Version) ($($wix.Exe))"

# --- 2. OSMF EULA gate (WiX v7+) ------------------------------------------------------
# `wix extension list` is a cheap gated command: exit 1 + WIX7015 means "not accepted".
$gateOut = & $wix.Exe extension list --global 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -and $gateOut -match 'WIX7015') {
    $eulaId = "wix$($wix.Version.Major)"
    if ($env:CI -eq 'true') {
        Write-Host "CI: accepting the WiX OSMF EULA ($eulaId)."
        & $wix.Exe eula accept $eulaId | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "wix eula accept $eulaId failed." }
        $gateOut = & $wix.Exe extension list --global 2>&1 | Out-String
    } else {
        throw ("WiX v$($wix.Version.Major) requires a one-time OSMF EULA acceptance for your user account. " +
            "Run:  & '$($wix.Exe)' eula accept $eulaId   (fee applies only above ~`$10k annual revenue; " +
            "see https://docs.firegiant.com/wix/osmf/ and issue #1), then re-run this script.")
    }
}

# --- 3. Ensure the two extensions jpackage needs exist for THIS major ------------------
# Healthy cache entries are listed as e.g. "WixToolset.Util.wixext 7.0.0"; incompatible
# ones carry "(damaged)". The versioned add below pins the extension to the toolset.
foreach ($ext in 'WixToolset.Util.wixext', 'WixToolset.UI.wixext') {
    $healthy = $gateOut -split "`r?`n" |
        Where-Object { $_ -match "^\s*$([regex]::Escape($ext))\s+$($wix.Version.Major)\.[\d.]+\s*$" }
    if (-not $healthy) {
        Write-Host "Adding missing extension $ext/$($wix.Version) to the user's extension cache..."
        & $wix.Exe extension add --global "$ext/$($wix.Version)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "wix extension add --global $ext/$($wix.Version) failed." }
    }
}

# --- 4. Preflight: build a minimal MSI exactly like jpackage would ---------------------
# Catches extension-resolution problems (WIX0144/144) in ~1s with a readable error
# instead of deep inside the Gradle/jpackage output.
$probeDir = Join-Path ([IO.Path]::GetTempPath()) "wix-preflight-$PID"
New-Item -ItemType Directory -Force $probeDir | Out-Null
try {
    Set-Content "$probeDir\probe.wxs" ('<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">' +
        '<Package Name="Preflight" Version="1.0.0" Manufacturer="Preflight" ' +
        'UpgradeCode="6d68f64a-9a1c-4b07-8de3-04f7eb5ee2a1"/></Wix>')
    $probeOut = & $wix.Exe build -nologo -ext WixToolset.Util.wixext -ext WixToolset.UI.wixext `
        -out "$probeDir\probe.msi" "$probeDir\probe.wxs" 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host $probeOut
        if ($probeOut -match 'WIX0144') {
            Write-Host ("Hint: wix resolves unversioned -ext references to the HIGHEST version in the " +
                "extension caches (user ~\.wix and machine C:\Program Files\Common Files\WixToolset). " +
                "Extensions of a newer major than WiX $($wix.Version) break this build - install that " +
                "newer toolset or remove its cached extensions.")
        }
        throw "WiX preflight build failed (exit $LASTEXITCODE) - jpackage would fail the same way."
    }
} finally {
    Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue
}

# --- 5. Build: jpackage uses the first wix.exe on PATH -> ours ------------------------
$env:PATH = "$($wix.Dir);$env:PATH"

& "$PSScriptRoot\gradlew.bat" packageMsi @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$msi = Get-ChildItem "build\compose\binaries\main\msi\*.msi" | Select-Object -First 1
if ($msi) {
    New-Item -ItemType Directory -Force "dist" | Out-Null
    Copy-Item $msi.FullName "dist\" -Force
    Write-Host "MSI -> dist\$($msi.Name)" -ForegroundColor Green
}
