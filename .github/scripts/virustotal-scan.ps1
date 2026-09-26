[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Path,
    [string]$ApiKey = $env:VIRUSTOTAL_API_KEY,
    [int]$TimeoutMinutes = 20,
    [int]$PollSeconds = 30
)

$ErrorActionPreference = "Stop"

$api = "https://www.virustotal.com/api/v3"
$directUploadLimit = 32MB

function Write-Outputs([hashtable]$Values) {
    if (-not $env:GITHUB_OUTPUT) {
        $Values.GetEnumerator() | ForEach-Object { Write-Host "[output] $($_.Key) = $($_.Value)" }
        return
    }
    foreach ($entry in $Values.GetEnumerator()) {
        if ("$($entry.Value)" -match "`n") {
            $delim = "vt_$([guid]::NewGuid().ToString('N'))"
            "$($entry.Key)<<$delim" | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
            "$($entry.Value)"       | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
            $delim                  | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
        } else {
            "$($entry.Key)=$($entry.Value)" | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
        }
    }
}

function Stop-WithoutNote([string]$Reason, [string]$Sha = "") {
    Write-Host "::warning title=VirusTotal::$Reason - the release is published without the scan section."
    Write-Outputs @{ status = "unavailable"; sha256 = $Sha; permalink = ""; release_note = "" }
    exit 0
}

$file = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
if (-not $file) { Stop-WithoutNote "No artifact at '$Path'" }

$sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$permalink = "https://www.virustotal.com/gui/file/$sha256"
Write-Host "Artifact : $($file.Name) ($([math]::Round($file.Length / 1MB, 1)) MB)"
Write-Host "SHA-256  : $sha256"

if (-not $ApiKey) { Stop-WithoutNote "VIRUSTOTAL_API_KEY is not set" $sha256 }

function Invoke-Vt {
    param([string]$Uri, [string]$Method = 'Get', [hashtable]$Form, [int]$TimeoutSec = 120)
    $params = @{
        Uri        = $Uri
        Method     = $Method
        Headers    = @{ 'x-apikey' = $ApiKey; 'accept' = 'application/json' }
        TimeoutSec = $TimeoutSec
    }
    if ($Form) { $params.Form = $Form }
    Invoke-RestMethod @params
}

function Get-HttpStatus($ErrorRecord) {
    if ($ErrorRecord.Exception.Response) { return [int]$ErrorRecord.Exception.Response.StatusCode }
    return 0
}

function Send-MultipartFile([string]$Uri, [System.IO.FileInfo]$File) {
    $curl = Join-Path $env:SystemRoot 'System32\curl.exe'
    if (-not (Test-Path -LiteralPath $curl)) { $curl = 'curl.exe' }

    $keyConfig = 'header = "x-apikey: {0}"' -f $ApiKey

    $lines = $keyConfig | & $curl --config - `
        --silent --show-error --fail-with-body `
        --max-time 900 `
        --header 'accept: application/json' `
        --form "file=@$($File.FullName)" `
        --write-out '\n%{http_code}' `
        --url $Uri
    $exit = $LASTEXITCODE

    $all = @($lines)
    $status = 0
    if ($all.Count) { [int]::TryParse(("$($all[-1])").Trim(), [ref]$status) | Out-Null }
    $body = if ($all.Count -gt 1) { ($all[0..($all.Count - 2)]) -join "`n" } else { '' }

    [pscustomobject]@{
        Ok       = ($exit -eq 0 -and $status -ge 200 -and $status -lt 300)
        Status   = $status
        Body     = $body
        CurlExit = $exit
    }
}

$report = $null
$analysisId = $null
try {
    try { $report = Invoke-Vt "$api/files/$sha256" }
    catch { if ((Get-HttpStatus $_) -ne 404) { throw } }

    if ($report) {
        Write-Host "VirusTotal already has a report for this hash - skipping the upload."
    } else {
        if ($file.Length -le $directUploadLimit) {
            $uploadUrl = "$api/files"
        } else {
            $uploadUrl = (Invoke-Vt "$api/files/upload_url").data
            Write-Host "Artifact exceeds 32 MB - using a one-shot upload URL."
        }
        Write-Host "Uploading..."
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Send-MultipartFile $uploadUrl $file
        Write-Host "Uploaded in $([math]::Round($watch.Elapsed.TotalSeconds, 1))s."
        if (-not $response.Ok) {
            $why = if ($response.Status) { "HTTP $($response.Status) - $($response.Body)" }
                   else { "curl exit $($response.CurlExit)" }
            Stop-WithoutNote "Upload rejected ($why)" $sha256
        }
        $analysisId = ($response.Body | ConvertFrom-Json).data.id
        Write-Host "Analysis : $analysisId"
    }
} catch {
    $err = $_
    $status = Get-HttpStatus $err
    $detail = if ($err.ErrorDetails.Message) { $err.ErrorDetails.Message } else { $err.Exception.Message }
    $hint = switch ($status) {
        401     { "HTTP 401 - the API key was rejected" }
        429     { "HTTP 429 - the public-API quota (500/day, 4/min) is exhausted" }
        0       { "$($err.Exception.GetType().Name): $detail" }
        default { "HTTP $status - $detail" }
    }
    if ($watch) { $hint += " (after $([math]::Round($watch.Elapsed.TotalSeconds, 1))s)" }
    Stop-WithoutNote "Upload failed ($hint)" $sha256
}

$stats = $null
if ($report) {
    $stats = $report.data.attributes.last_analysis_stats
} else {
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        try { $analysis = Invoke-Vt "$api/analyses/$analysisId" }
        catch {
            Write-Host "  poll failed ($($_.Exception.Message)) - retrying."
            continue
        }
        Write-Host "  status: $($analysis.data.attributes.status)"
        if ($analysis.data.attributes.status -eq 'completed') {
            $stats = $analysis.data.attributes.stats
            break
        }
    }
}

$verify = "The hash belongs to exactly the MSI attached to this release - check it locally with ``Get-FileHash <your-file>.msi``."

if (-not $stats) {
    Write-Host "::warning title=VirusTotal::Analysis still running after $TimeoutMinutes min - linking the pending report."
    $state = "pending"
    $note = @"
## Security check

The MSI was uploaded to VirusTotal straight after the CI build; the analysis was still
running when these release notes were written. Result: **[VirusTotal report]($permalink)**

SHA-256: ``$sha256``

$verify
"@
} else {
    $flagged = [int]$stats.malicious + [int]$stats.suspicious
    $engines = $flagged + [int]$stats.undetected + [int]$stats.harmless
    $state = if ($flagged -eq 0) { "clean" } else { "flagged" }
    Write-Host "Verdict  : $flagged / $engines engines flagged the file."

    $headline = if ($flagged -eq 0) {
        "The MSI was scanned on VirusTotal automatically, straight after the CI build: **0 of $engines engines** flag it."
    } else {
        "The MSI was scanned on VirusTotal automatically, straight after the CI build: **$flagged of $engines engines** report a hit."
    }
    $caveat = if ($flagged -eq 0) { "" } else { @"


Individual hits on an unsigned installer are routinely false positives - typically
``Wacatac``/``!ml`` from Microsoft Defender, because the MSI carries no Authenticode
signature and is a brand-new, unknown file on every release. The report shows which
engine says what.
"@ }

    $note = @"
## Security check

$headline

- Report: **[VirusTotal]($permalink)**
- SHA-256: ``$sha256``

$verify$caveat
"@
}

Write-Outputs @{
    status       = $state
    sha256       = $sha256
    permalink    = $permalink
    release_note = $note
}
