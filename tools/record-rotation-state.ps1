[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after', 'sample')]
    [string]$Phase,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Action,

    # Empty: the single device listed by `adb devices`.
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
$script:AdbPath = (Get-Command adb -ErrorAction Stop).Source
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $connected = @(& $script:AdbPath 'devices' 2>&1 | ForEach-Object { "$_" } |
        Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($connected.Count -ne 1) {
        throw "Pass -Serial: adb lists $($connected.Count) connected devices."
    }
    $Serial = $connected[0]
}
$script:Serial = $Serial

function Get-SystemSetting {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $nativeArguments = @(
        '-s', $script:Serial,
        'shell', 'settings', 'get', 'system', $Name
    )
    $nativeOutput = & $script:AdbPath @nativeArguments
    $nativeExitCode = $LASTEXITCODE
    if ($nativeExitCode -ne 0) {
        throw "ADB settings query failed for '$Name' (exit $nativeExitCode)."
    }

    $value = ($nativeOutput -join "`n").Trim()
    if ($value -notmatch '^\d+$') {
        throw "ADB returned an invalid value for '$Name': '$value'."
    }
    return $value
}

$accelerometerRotation = Get-SystemSetting -Name 'accelerometer_rotation'
$userRotation = Get-SystemSetting -Name 'user_rotation'
$projectRoot = [System.IO.Path]::GetDirectoryName($PSScriptRoot)
$logDirectory = Join-Path $projectRoot 'logs'
$logPath = Join-Path $logDirectory 'rotation-state.jsonl'

if (-not (Test-Path -LiteralPath $logDirectory -PathType Container)) {
    New-Item -Path $logDirectory -ItemType Directory -Force | Out-Null
}

$previousBefore = $null
if ($Phase -eq 'after' -and (Test-Path -LiteralPath $logPath -PathType Leaf)) {
    foreach ($line in Get-Content -LiteralPath $logPath) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $entry = ConvertFrom-Json -InputObject $line
        $matchesAction = ($entry.phase -eq 'before') -and
                ($entry.action -eq $Action) -and
                ($entry.serial -eq $Serial)
        if ($matchesAction) {
            $previousBefore = $entry
        }
    }
}

$anomalies = @()
if ($accelerometerRotation -ne '0') {
    $anomalies += 'AUTO_ROTATE_NOT_OFF'
}
if ($Phase -eq 'after') {
    if ($null -eq $previousBefore) {
        $anomalies += 'BEFORE_SNAPSHOT_MISSING'
    } elseif ($previousBefore.accelerometer_rotation -ne $accelerometerRotation) {
        $anomalies += 'AUTO_ROTATE_CHANGED'
    }
}

$orientationChanged = $null -ne $previousBefore -and
        $previousBefore.user_rotation -ne $userRotation
$record = [ordered]@{
    timestamp = [DateTimeOffset]::Now.ToString('o')
    action = $Action
    phase = $Phase
    serial = $Serial
    accelerometer_rotation = $accelerometerRotation
    user_rotation = $userRotation
    orientation_changed_since_before = $orientationChanged
    anomalies = $anomalies
}
$jsonLine = ConvertTo-Json -InputObject $record -Compress
[System.IO.File]::AppendAllText(
    $logPath,
    $jsonLine + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output $jsonLine
if ($anomalies.Count -gt 0) {
    Write-Error ('Rotation guard detected: ' + ($anomalies -join ', '))
}
