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
#      automatically (project decision, see issue krt-iri/basetool-bp-extractor#1).
#   3. Bare machines / CI runners. If no WiX 7 is installed, WiX is bootstrapped
#      as a LOCAL dotnet tool under tools\wix (gitignored; nothing system-wide),
#      and missing Util/UI extensions are added to the user's extension cache.
#
# Usage:  .\package-msi.ps1            # builds dist\...-<version>.msi
#         .\package-msi.ps1 --info     # extra args are forwarded to Gradle
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# The build is pinned to WiX 7 (issue #1); bump deliberately, then re-verify the build.
$wixRequiredMajor = 7
$wixBootstrapVersion = "7.0.0"   # installed as a local dotnet tool when no WiX 7 is found

function Get-WixInfo([string]$exe) {
    # `wix --version` is not gated behind the OSMF EULA, so this works pre-acceptance.
    try { $raw = (& $exe --version 2>$null | Select-Object -First 1) } catch { return $null }
    if ($raw -match '^(\d+\.\d+\.\d+)') {
        [pscustomobject]@{ Exe = $exe; Dir = (Split-Path $exe); Version = [version]$Matches[1] }
    }
}

# --- 1. Pick the newest installed WiX 7.x (PATH + a previous tools\wix bootstrap) -----
$candidates = @($env:PATH -split ';' | Where-Object { $_ } |
    ForEach-Object { Join-Path $_ 'wix.exe' })
$candidates += Join-Path $PSScriptRoot 'tools\wix\wix.exe'
$wix = $candidates | Where-Object { Test-Path $_ } | ForEach-Object { Get-WixInfo $_ } |
    Where-Object { $_ -and $_.Version.Major -eq $wixRequiredMajor } |
    Sort-Object Version -Descending | Select-Object -First 1

if (-not $wix) {
    if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
        throw "No WiX $wixRequiredMajor found and no .NET SDK to bootstrap one. Install WiX v$wixRequiredMajor (https://wixtoolset.org) or the .NET SDK."
    }
    Write-Host "No WiX $wixRequiredMajor found - installing WiX $wixBootstrapVersion as a local dotnet tool (tools\wix)..."
    # `update` instead of `install`: installs when missing, replaces a stale tools\wix.
    dotnet tool update wix --tool-path (Join-Path $PSScriptRoot 'tools\wix') --version $wixBootstrapVersion | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "dotnet tool update wix failed." }
    $wix = Get-WixInfo (Join-Path $PSScriptRoot 'tools\wix\wix.exe')
    if (-not $wix) { throw "Bootstrapped WiX did not report a usable version." }
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
