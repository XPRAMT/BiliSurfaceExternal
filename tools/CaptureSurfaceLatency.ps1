[CmdletBinding(DefaultParameterSetName = 'Capture')]
param(
    [Parameter(Mandatory = $true)]
    [string]$Layer,

    [string]$AdbPath = 'adb',

    [string]$Serial = '',

    [ValidateRange(1, 120)]
    [int]$DurationSeconds = 15,

    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\logs\surface-latency')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $connected = @(& $AdbPath 'devices' 2>&1 | ForEach-Object { "$_" } |
        Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($connected.Count -ne 1) {
        throw "Pass -Serial: adb lists $($connected.Count) connected devices."
    }
    $Serial = $connected[0]
}

function Invoke-RootSurfaceFlinger {
    param([Parameter(Mandatory = $true)][string]$Command)

    $result = @(& $AdbPath -s $Serial exec-out su -c $Command)
    if ($LASTEXITCODE -ne 0) {
        throw "SurfaceFlinger command failed with exit code ${LASTEXITCODE}: $Command"
    }
    return $result
}

function Convert-ToNanoseconds {
    param([Parameter(Mandatory = $true)][string]$Value)

    [Int64]$nanoseconds = 0
    if (-not [Int64]::TryParse($Value, [ref]$nanoseconds)) {
        throw "Unexpected SurfaceFlinger timestamp: $Value"
    }
    return $nanoseconds
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable not found: $AdbPath"
}

& $AdbPath -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "ADB device check failed with exit code $LASTEXITCODE for $Serial"
}

$layerForShell = $Layer.Replace("'", "'\''")
$latencyCommand = "dumpsys SurfaceFlinger --latency '$layerForShell'"
$clearCommand = "dumpsys SurfaceFlinger --latency-clear '$layerForShell'"

Invoke-RootSurfaceFlinger -Command $clearCommand | Out-Null
Write-Host "Cleared SurfaceFlinger latency history for: $Layer"
Write-Host "Capturing $DurationSeconds seconds. Keep normal Bilibili playback running."
Start-Sleep -Seconds $DurationSeconds
$rawLatency = Invoke-RootSurfaceFlinger -Command $latencyCommand

if ($rawLatency.Count -lt 2) {
    throw 'SurfaceFlinger returned no frame-latency samples. Confirm the exact active video Surface layer.'
}

$refreshPeriodNs = Convert-ToNanoseconds -Value $rawLatency[0].Trim()
$samples = New-Object 'System.Collections.Generic.List[object]'
$previousPresentNs = [Int64]0
$sampleIndex = 0

foreach ($line in $rawLatency | Select-Object -Skip 1) {
    $parts = @($line.Trim() -split '\s+' | Where-Object { $_.Length -gt 0 })
    if ($parts.Count -ne 3 -or $parts -contains '0') {
        continue
    }

    $desiredNs = Convert-ToNanoseconds -Value $parts[0]
    $presentNs = Convert-ToNanoseconds -Value $parts[1]
    $readyNs = Convert-ToNanoseconds -Value $parts[2]
    $deltaMs = $null
    if ($previousPresentNs -gt 0 -and $presentNs -gt $previousPresentNs) {
        $deltaMs = [Math]::Round(($presentNs - $previousPresentNs) / 1000000.0, 3)
    }
    $previousPresentNs = $presentNs
    $sampleIndex++
    $samples.Add([PSCustomObject]@{
            Sample = $sampleIndex
            DesiredTimeNs = $desiredNs
            ActualPresentNs = $presentNs
            ReadyTimeNs = $readyNs
            PresentDeltaMs = $deltaMs
        })
}

if ($samples.Count -lt 2) {
    throw 'Fewer than two valid frame samples were returned. The selected layer may not be a video Surface.'
}

if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $OutputDirectory -ErrorAction Stop | Out-Null
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$baseName = "surface-latency-$stamp"
$rawPath = Join-Path $OutputDirectory "$baseName-raw.txt"
$csvPath = Join-Path $OutputDirectory "$baseName-frames.csv"
$summaryPath = Join-Path $OutputDirectory "$baseName-summary.txt"

$rawLatency | Set-Content -LiteralPath $rawPath -Encoding utf8
$samples | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
$deltas = @($samples | ForEach-Object { $_.PresentDeltaMs } | Where-Object { $null -ne $_ })
$sortedDeltas = @($deltas | Sort-Object)
$medianIndex = [int][Math]::Floor(($sortedDeltas.Count - 1) / 2)
$p95Index = [int][Math]::Ceiling(($sortedDeltas.Count - 1) * 0.95)
$refreshPeriodMs = [Math]::Round($refreshPeriodNs / 1000000.0, 3)
$summary = @(
    "Layer: $Layer"
    "Capture duration seconds: $DurationSeconds"
    "SurfaceFlinger refresh period ms: $refreshPeriodMs"
    "Valid presented frames: $($samples.Count)"
    "Intervals: $($deltas.Count)"
    "Present delta minimum ms: $([Math]::Round(($deltas | Measure-Object -Minimum).Minimum, 3))"
    "Present delta median ms: $($sortedDeltas[$medianIndex])"
    "Present delta p95 ms: $($sortedDeltas[$p95Index])"
    "Present delta maximum ms: $([Math]::Round(($deltas | Measure-Object -Maximum).Maximum, 3))"
    "Raw samples: $rawPath"
    "Frame CSV: $csvPath"
)
$summary | Set-Content -LiteralPath $summaryPath -Encoding utf8
$summary | ForEach-Object { Write-Host $_ }
Write-Host "Summary: $summaryPath"
