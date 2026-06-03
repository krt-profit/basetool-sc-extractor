# Builds the Windows MSI installer reliably under JDK 25.
#
# Why this wrapper exists:
#   JDK 25's jpackage prefers a WiX 4+ `wix.exe` whenever it finds one on PATH, but
#   jpackage has known bugs with WiX 4/5/6/7 — it aborts with error code 144
#   (OpenJDK bug JDK-8356592). It only works reliably with WiX 3.x
#   (candle.exe / light.exe). The Compose Gradle plugin auto-downloads WiX 3, but it
#   gets shadowed if a newer `wix.exe` is on PATH (e.g. an installed "WiX Toolset v6/v7").
#
#   This script removes every "WiX Toolset" (v4+) directory from PATH for the build and
#   prepends the bundled WiX 3.14 in tools\wix3, so jpackage falls back to the reliable
#   WiX 3 path. Nothing on the system is changed — only this process's PATH.
#
# Usage:  .\package-msi.ps1            # builds dist\...-1.0.0.msi
#         .\package-msi.ps1 --info     # extra args are forwarded to Gradle
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$wix3 = Join-Path $PSScriptRoot "tools\wix3"
$filtered = ($env:PATH -split ';' | Where-Object { $_ -and $_ -notlike "*WiX Toolset*" }) -join ';'
$env:PATH = "$wix3;$filtered"

if ((Get-Command wix -ErrorAction SilentlyContinue)) {
    Write-Warning "A WiX v4+ 'wix.exe' is still on PATH; jpackage may fail with error 144."
}

& "$PSScriptRoot\gradlew.bat" packageMsi @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$msi = Get-ChildItem "build\compose\binaries\main\msi\*.msi" | Select-Object -First 1
if ($msi) {
    New-Item -ItemType Directory -Force "dist" | Out-Null
    Copy-Item $msi.FullName "dist\" -Force
    Write-Host "MSI -> dist\$($msi.Name)" -ForegroundColor Green
}
