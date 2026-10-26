[CmdletBinding()]
param(
    [string]$Url = 'https://www.bilibili.com/video/BV1PR4y1Y75W/?p=3',

    [string]$AdbPath = 'adb',

    [string]$Serial = '',

    [string[]]$FallbackSerials = @(),

    [ValidateRange(1, 30)]
    [int]$ConnectTimeoutSeconds = 5,

    [string]$PackageName = 'tv.danmaku.bili',

    [string]$BrowserPackage = 'com.android.chrome',

    [ValidateRange(1, 120)]
    [int]$LaunchTimeoutSeconds = 20,

    [ValidateRange(100, 5000)]
    [int]$PollMilliseconds = 500,

    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\logs\deeplink-tests')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = @(& $AdbPath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $renderedArguments = $Arguments -join ' '
        $formattedOutput = $output -join [Environment]::NewLine
        throw "ADB command failed with exit code ${exitCode}: ${renderedArguments}`n${formattedOutput}"
    }
    return $output
}

function Test-AdbSerialReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CandidateSerial
    )

    $output = @(& $AdbPath '-s' $CandidateSerial 'get-state' 2>&1)
    $exitCode = $LASTEXITCODE
    return [PSCustomObject]@{
        Ready = ($exitCode -eq 0)
        Detail = ($output -join ' ')
    }
}

function Invoke-AdbConnectWithTimeout {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CandidateSerial
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $AdbPath
    $startInfo.Arguments = "connect $CandidateSerial"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        if (-not $process.WaitForExit($ConnectTimeoutSeconds * 1000)) {
            try {
                $process.Kill()
            } catch {
                # The process may have exited between WaitForExit and Kill.
            }
            return [PSCustomObject]@{
                Connected = $false
                Detail = "connect timeout after ${ConnectTimeoutSeconds}s"
            }
        }
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $detail = @($standardOutput, $standardError) -join ' '
        return [PSCustomObject]@{
            Connected = ($process.ExitCode -eq 0)
            Detail = $detail.Trim()
        }
    } catch {
        return [PSCustomObject]@{
            Connected = $false
            Detail = $_.Exception.Message
        }
    } finally {
        $process.Dispose()
    }
}

function Get-ConnectedAdbSerials {
    # Devices already listed by `adb devices` in the "device" state; used when no -Serial is given.
    $lines = @(& $AdbPath 'devices' 2>&1)
    return @($lines | ForEach-Object { "$_" } | Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
}

function Select-WorkingSerial {
    $candidates = @($Serial) + @($FallbackSerials)
    if (-not (@($candidates) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
        $candidates = @(Get-ConnectedAdbSerials)
    }
    $attempts = New-Object 'System.Collections.Generic.List[object]'
    $seen = @{}

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate) -or $seen.ContainsKey($candidate)) {
            continue
        }
        $seen[$candidate] = $true

        $state = Test-AdbSerialReady -CandidateSerial $candidate
        if ($state.Ready) {
            [void]$attempts.Add([PSCustomObject]@{
                    Serial = $candidate
                    Method = 'already-connected'
                    Success = $true
                    Detail = $state.Detail
                })
            return [PSCustomObject]@{
                Success = $true
                Serial = $candidate
                Attempts = @($attempts.ToArray())
            }
        }

        $connectDetail = 'not a wireless serial; connect skipped'
        $connectSuccess = $false
        if ($candidate -match ':\d+$') {
            $connectResult = Invoke-AdbConnectWithTimeout -CandidateSerial $candidate
            $connectDetail = $connectResult.Detail
            $connectSuccess = $connectResult.Connected
        }
        $stateAfterConnect = Test-AdbSerialReady -CandidateSerial $candidate
        $attemptSuccess = $stateAfterConnect.Ready
        $attemptMethod = if ($connectSuccess) {
            'adb-connect'
        } else {
            'adb-connect-failed'
        }
        $attemptDetail = if ($attemptSuccess) {
            $stateAfterConnect.Detail
        } else {
            $connectDetail + '; state=' + $stateAfterConnect.Detail
        }
        [void]$attempts.Add([PSCustomObject]@{
                Serial = $candidate
                Method = $attemptMethod
                Success = $attemptSuccess
                Detail = $attemptDetail
            })
        if ($attemptSuccess) {
            return [PSCustomObject]@{
                Success = $true
                Serial = $candidate
                Attempts = @($attempts.ToArray())
            }
        }
    }

    return [PSCustomObject]@{
        Success = $false
        Serial = ''
        Attempts = @($attempts.ToArray())
    }
}

function Get-ForegroundState {
    $activityOutput = @(Invoke-Adb -Arguments @(
            '-s', $Serial, 'shell', 'dumpsys', 'activity', 'activities'))
    $activityText = $activityOutput -join "`n"
    $patterns = @(
        'mResumedActivity:.*?\bu\d+\s+(?<component>[^\s}]+/[^\s}]+)',
        'mFocusedApp=.*?\bu\d+\s+(?<component>[^\s}]+/[^\s}]+)'
    )

    foreach ($pattern in $patterns) {
        $match = [regex]::Match($activityText, $pattern)
        if ($match.Success) {
            $component = $match.Groups['component'].Value
            $package = $component.Split('/')[0]
            return [PSCustomObject]@{
                Package = $package
                Component = $component
                Raw = $activityText
            }
        }
    }

    return [PSCustomObject]@{
        Package = ''
        Component = ''
        Raw = $activityText
    }
}

function New-TestDirectory {
    if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
        [System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
    }
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable not found: $AdbPath"
}

New-TestDirectory
$startedAt = Get-Date
$requestedSerial = $Serial
$events = New-Object 'System.Collections.Generic.List[object]'
$result = 'FAIL'
$failure = $null
$launchOutput = @()
$resolutionOutput = @()
$foregroundBefore = $null
$foregroundAfterStop = $null
$foregroundAfterLaunch = $null
$connectionSelection = $null
$selectedSerial = ''

try {
    $connectionSelection = Select-WorkingSerial
    if (-not $connectionSelection.Success) {
        $attemptDetails = @($connectionSelection.Attempts |
            ForEach-Object { "$($_.Serial): $($_.Detail)" }) -join ' | '
        throw "No ADB device available. Tried: $attemptDetails"
    }
    $Serial = $connectionSelection.Serial
    $selectedSerial = $Serial
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'adb-device-selected'
            Detail = $Serial
        })
    $deviceState = @(Invoke-Adb -Arguments @('-s', $Serial, 'get-state'))
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'device-ready'
            Detail = ($deviceState -join ' ')
        })

    $foregroundBefore = Get-ForegroundState
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'foreground-before-stop'
            Detail = $foregroundBefore.Component
        })

    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'force-stop', $PackageName) | Out-Null
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'bilibili-force-stopped'
            Detail = $PackageName
        })

    Start-Sleep -Milliseconds 500
    $foregroundAfterStop = Get-ForegroundState
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'foreground-after-stop'
            Detail = $foregroundAfterStop.Component
        })

    try {
        $resolutionOutput = @(Invoke-Adb -Arguments @(
                '-s', $Serial, 'shell', 'cmd', 'package', 'resolve-activity', '--brief',
                '-a', 'android.intent.action.VIEW', '-d', $Url, '-p', $BrowserPackage))
    } catch {
        $resolutionOutput = @("resolve-activity failed: $($_.Exception.Message)")
    }
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'url-resolution'
            Detail = ($resolutionOutput -join ' | ')
        })

    $launchOutput = @(Invoke-Adb -Arguments @(
            '-s', $Serial, 'shell', 'am', 'start', '-W',
            '-a', 'android.intent.action.VIEW', '-d', $Url,
            '-p', $BrowserPackage))
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'url-launched'
            Detail = ($launchOutput -join ' | ')
        })

    $deadline = (Get-Date).AddSeconds($LaunchTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds $PollMilliseconds
        $foregroundAfterLaunch = Get-ForegroundState
        if ($foregroundAfterLaunch.Package -eq $PackageName -and
                $foregroundAfterLaunch.Component -match 'UnitedBizDetailsActivity') {
            $result = 'PASS'
            break
        }
    } while ((Get-Date) -lt $deadline)

    $foregroundAfterLaunchDetail = ''
    if ($null -ne $foregroundAfterLaunch) {
        $foregroundAfterLaunchDetail = $foregroundAfterLaunch.Component
    }
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'foreground-after-launch'
            Detail = $foregroundAfterLaunchDetail
        })

    if ($result -ne 'PASS') {
        throw "URL did not foreground the Bilibili video detail activity within $LaunchTimeoutSeconds seconds"
    }
} catch {
    $failure = $_.Exception.Message
    $events.Add([PSCustomObject]@{
            Time = (Get-Date).ToString('o')
            Name = 'failure'
            Detail = $failure
        })
}

$finishedAt = Get-Date
$stamp = $startedAt.ToString('yyyyMMdd-HHmmssfff')
$foregroundBeforeComponent = if ($null -eq $foregroundBefore) {
    ''
} else {
    $foregroundBefore.Component
}
$foregroundAfterStopComponent = if ($null -eq $foregroundAfterStop) {
    ''
} else {
    $foregroundAfterStop.Component
}
$foregroundAfterLaunchComponent = if ($null -eq $foregroundAfterLaunch) {
    ''
} else {
    $foregroundAfterLaunch.Component
}
$eventArray = @($events.ToArray())
$connectionAttemptArray = @()
if ($null -ne $connectionSelection) {
    $connectionAttemptArray = @($connectionSelection.Attempts)
}
$resultObject = [PSCustomObject]@{
    Result = $result
    Url = $Url
    PackageName = $PackageName
    BrowserPackage = $BrowserPackage
    RequestedSerial = $requestedSerial
    SelectedSerial = $selectedSerial
    StartedAt = $startedAt.ToString('o')
    FinishedAt = $finishedAt.ToString('o')
    DurationSeconds = [Math]::Round(($finishedAt - $startedAt).TotalSeconds, 3)
    Failure = $failure
    ForegroundBeforeStop = $foregroundBeforeComponent
    ForegroundAfterStop = $foregroundAfterStopComponent
    ForegroundAfterLaunch = $foregroundAfterLaunchComponent
    ResolutionOutput = $resolutionOutput
    LaunchOutput = $launchOutput
    ConnectionAttempts = $connectionAttemptArray
    Events = $eventArray
}

$jsonPath = Join-Path $OutputDirectory "deep-link-$stamp.json"
$resultObject | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding utf8

Write-Host "Result: $result"
Write-Host "URL: $Url"
Write-Host "Foreground after launch: $($resultObject.ForegroundAfterLaunch)"
Write-Host "Report: $jsonPath"

if ($result -ne 'PASS') {
    exit 1
}

exit 0
