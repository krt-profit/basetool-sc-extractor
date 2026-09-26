$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$wixRequiredMajor = 7
$wixBootstrapVersion = "7.0.0"
$wixBootstrapSha512 = "4GbdoDWjm0QmBLyJ8PPiknXomjI5vHat4H27AB6NNGzFpbEhN9Xw0kiPQokZ3zH8F1RcjvDSn8yGDXwKxlW8Tg=="
$wixExtensionSha512 = @{
    'WixToolset.Util.wixext' = "JHl/qbyJsyzQbNUd4Jz30IHFUZHn48GfLEkRnfRZieeEp2DnAknVDvCLQgnV/mO8QaCH7ibUXtJ4/BGSDjsZ9A=="
    'WixToolset.UI.wixext'   = "prwMDQ+gIhtnH6jZVP3LDYnTnQFbJRo1WIJlTNx1ioc7lgUf1Q+uU2aOfS23f8TctuiDWAgVVJoU6aqh5KuG2g=="
}
$wixToolDir = Join-Path $PSScriptRoot 'tools\wix'
$userExtRoot = Join-Path $env:USERPROFILE '.wix\extensions'
$machineExtRoot = Join-Path $env:CommonProgramFiles 'WixToolset\extensions'

function Get-WixInfo([string]$exe) {
    try { $raw = (& $exe --version 2>$null | Select-Object -First 1) } catch { return $null }
    if ($raw -match '^(\d+\.\d+\.\d+)') {
        [pscustomobject]@{ Exe = $exe; Dir = (Split-Path $exe); Version = [version]$Matches[1] }
    }
}

function Get-FileSha512Base64([string]$path) {
    $sha = [System.Security.Cryptography.SHA512]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { [Convert]::ToBase64String($sha.ComputeHash($stream)) } finally { $stream.Dispose(); $sha.Dispose() }
}

function Test-WixBootstrap {
    $nupkg = Join-Path $wixToolDir ".store\wix\$wixBootstrapVersion\wix\$wixBootstrapVersion\wix.$wixBootstrapVersion.nupkg"
    if (-not (Test-Path $nupkg)) { return $false }
    $actual = Get-FileSha512Base64 $nupkg
    if ($actual -ne $wixBootstrapSha512) {
        Write-Warning "tools\wix: wix.$wixBootstrapVersion.nupkg has SHA-512 $actual, expected $wixBootstrapSha512."
        return $false
    }
    return $true
}

function Test-WixExtensionCopy([string]$dll, [string]$ext) {
    $actual = Get-FileSha512Base64 $dll
    if ($actual -eq $wixExtensionSha512[$ext]) { return $true }
    Write-Warning "$dll has SHA-512 $actual, expected $($wixExtensionSha512[$ext])."
    return $false
}

$wix = @($env:PATH -split ';' | Where-Object { $_ } | ForEach-Object { Join-Path $_ 'wix.exe' }) |
    Where-Object { Test-Path $_ } | ForEach-Object { Get-WixInfo $_ } |
    Where-Object { $_ -and $_.Version.Major -eq $wixRequiredMajor } |
    Sort-Object Version -Descending | Select-Object -First 1

if (-not $wix) {
    $localExe = Join-Path $wixToolDir 'wix.exe'
    if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
        throw ("No WiX $wixRequiredMajor found and no .NET SDK to bootstrap one. Install WiX v$wixRequiredMajor " +
            "(https://wixtoolset.org) or the .NET SDK, then re-run.")
    }
    if (Test-Path $wixToolDir) { Remove-Item $wixToolDir -Recurse -Force }
    Write-Host "No WiX $wixRequiredMajor found - installing WiX $wixBootstrapVersion afresh as a local dotnet tool (tools\wix)..."
    dotnet tool install wix --tool-path $wixToolDir --version $wixBootstrapVersion | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "dotnet tool install wix --version $wixBootstrapVersion failed." }
    if (-not (Test-WixBootstrap)) {
        Remove-Item $wixToolDir -Recurse -Force -ErrorAction SilentlyContinue
        throw ("The downloaded wix.$wixBootstrapVersion.nupkg does not match the pinned SHA-512 - refusing to run it " +
            "(tools\wix removed). Do not just update the pin: find out why the package differs first.")
    }
    $wix = Get-WixInfo $localExe
    if (-not $wix -or $wix.Version.Major -ne $wixRequiredMajor) {
        throw "Bootstrapped WiX in tools\wix did not report a usable $wixRequiredMajor.x version."
    }
}
Write-Host "Using WiX $($wix.Version) ($($wix.Exe))"

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

$wixPinned = "$($wix.Version)" -eq $wixBootstrapVersion

function Add-WixExtension([string]$ext) {
    if (-not $wixPinned) {
        throw ("WiX $($wix.Version) lacks $ext, and this script only downloads extensions it can verify " +
            "(pinned for $wixBootstrapVersion). Install WiX $($wix.Version) with its extensions, or move the pins.")
    }
    Write-Host "Adding missing extension $ext/$($wix.Version) to the user's extension cache..."
    & $wix.Exe extension add --global "$ext/$($wix.Version)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "wix extension add --global $ext/$($wix.Version) failed." }
}

foreach ($ext in 'WixToolset.Util.wixext', 'WixToolset.UI.wixext') {
    $healthy = $gateOut -split "`r?`n" |
        Where-Object { $_ -match "^\s*$([regex]::Escape($ext))\s+$($wix.Version.Major)\.[\d.]+\s*$" }
    if (-not $healthy) { Add-WixExtension $ext }
    if (-not $wixPinned) { continue }

    $rel = "$ext\$($wix.Version)\wixext$($wix.Version.Major)\$ext.dll"
    $newer = foreach ($root in $userExtRoot, $machineExtRoot) {
        Get-ChildItem (Join-Path $root $ext) -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+(\.\d+){1,3}$' -and ([version]$_.Name).Major -eq $wix.Version.Major -and
                [version]$_.Name -gt $wix.Version } | ForEach-Object { $_.FullName }
    }
    if ($newer) {
        throw ("A newer $ext is cached ($($newer -join ', ')); wix.exe would load it instead of the pinned " +
            "$($wix.Version). Remove it, or move the pins to that version.")
    }
    $userDll = Join-Path $userExtRoot $rel
    if ((Test-Path $userDll) -and -not (Test-WixExtensionCopy $userDll $ext)) {
        Write-Host "Removing the mismatching $ext/$($wix.Version) from the user's extension cache and adding it afresh..."
        Remove-Item (Join-Path $userExtRoot "$ext\$($wix.Version)") -Recurse -Force
        Add-WixExtension $ext
        if (-not (Test-WixExtensionCopy $userDll $ext)) {
            Remove-Item (Join-Path $userExtRoot "$ext\$($wix.Version)") -Recurse -Force -ErrorAction SilentlyContinue
            throw ("The downloaded $ext/$($wix.Version) does not match its pinned SHA-512 - refusing to use it " +
                "(removed from the user's extension cache). Find out why before touching the pin.")
        }
    }
    $machineDll = Join-Path $machineExtRoot $rel
    if ((Test-Path $machineDll) -and -not (Test-WixExtensionCopy $machineDll $ext)) {
        throw ("$machineDll does not match its pinned SHA-512. It belongs to the installed WiX - repair or " +
            "reinstall WiX $($wix.Version); this script does not modify it.")
    }
    if (-not (Test-Path $userDll) -and -not (Test-Path $machineDll)) {
        throw "$ext/$($wix.Version) is in neither extension cache ($userExtRoot, $machineExtRoot) - cannot verify it."
    }
}

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

$env:PATH = "$($wix.Dir);$env:PATH"

& "$PSScriptRoot\gradlew.bat" packageMsi @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$msi = Get-ChildItem "build\compose\binaries\main\msi\*.msi" | Select-Object -First 1
if ($msi) {
    New-Item -ItemType Directory -Force "dist" | Out-Null
    Copy-Item $msi.FullName "dist\" -Force
    Write-Host "MSI -> dist\$($msi.Name)" -ForegroundColor Green
}
