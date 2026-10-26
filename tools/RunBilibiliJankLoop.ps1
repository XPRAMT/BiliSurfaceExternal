[CmdletBinding()]
param(
    [string]$Url = 'https://www.bilibili.com/video/BV1PR4y1Y75W/?p=3',

    [string]$AdbPath = 'adb',

    [string]$Serial = '',

    [string[]]$FallbackSerials = @(),

    [ValidateRange(1, 30)]
    [int]$ConnectTimeoutSeconds = 5,

    [string]$PackageName = 'tv.danmaku.bili',

    [ValidateRange(1, 120)]
    [int]$LaunchTimeoutSeconds = 30,

    [ValidateRange(1, 10)]
    [int]$FullscreenAttempts = 1,

    [ValidateRange(0, 2000)]
    [int]$FullscreenRevealDelayMilliseconds = 500,

    [ValidateRange(1, 15)]
    [int]$FullscreenTransitionTimeoutSeconds = 5,

    [ValidateRange(0, 30)]
    [int]$PlayerReadyDelaySeconds = 5,

    [int]$FullscreenRevealX = 608,

    [int]$FullscreenRevealY = 650,

    [int]$FullscreenButtonX = 1125,

    [int]$FullscreenButtonY = 740,

    [ValidateRange(1, 240)]
    [double]$TargetVideoFps = 120,

    [ValidateRange(0.01, 1.0)]
    [double]$TargetVideoFpsTolerance = 0.25,

    [ValidateSet('TEXTURE_EXTERNAL', 'SURFACE_EXTERNAL', 'SURFACE')]
    [string]$RendererMode = 'SURFACE_EXTERNAL',

    # 'Url' opens $Url through the system VIEW intent (needs a network good enough for
    # 120 fps streaming).  'OfflineCache' walks the app UI instead: main activity ->
    # bottom-right "我的" tab -> "離線快取" -> one tap per level of $OfflineItemTapsY
    # (folder "分享" -> "屏幕測試 (UFO,4K)" -> the part), which plays the local file in
    # UnitedPlaylistActivity and does not depend on the network.
    [ValidateSet('Url', 'OfflineCache')]
    [string]$LaunchMode = 'Url',

    [int]$OfflineMineTabX = 1088,
    [int]$OfflineMineTabY = 2640,
    [int]$OfflineCacheEntryX = 150,
    [int]$OfflineCacheEntryY = 1092,
    [int]$OfflineFirstItemX = 603,
    # Y of each successive row to tap after the cache list opens (1216x2688 coordinates).
    # Default: folder row, series row, first part ("UFO Test 120"); the 60 fps part is the
    # second row of the last level: -OfflineItemTapsY 571,434,766.
    [int[]]$OfflineItemTapsY = @(571, 434, 434),
    [int]$OfflineTapDelayMilliseconds = 4000,

    [bool]$RenderCadenceProbe = $false,

    [bool]$Media3FrameSyncEnabled = $false,


    [bool]$FrameQueueWaitRoundUpEnabled = $false,

    # The historical A/B baseline blocks IJK's own frame drop and A/V delay correction.
    # Pass $false for both to measure the module under the user's normal settings.
    [bool]$BlockIjkFrameDrop = $false,

    [bool]$BlockComputeTargetDelay = $true,

    # Online playback switches 1080p30 -> 4K120 ~15 s after start and then rebuffers;
    # 40 s keeps the fixed 30 s window clear of that transition.
    [ValidateRange(0, 90)]
    [int]$FullscreenStabilizationSeconds = 20,

    [ValidateRange(5, 120)]
    [int]$CaptureWaitSeconds = 45,

    [ValidateRange(0.1, 10)]
    [double]$GapThresholdPeriods = 1.5,

    [string]$BaselineReportPath = '',

    [string]$OutputDirectory = '',

    [string]$TraceConfigPath = '',

    [string]$TelemetryRemotePath = '/data/user/0/tv.danmaku.bili/files/bilisurfacebridge/media3_frame_timing.csv',

    [ValidateRange(1048576, 268435456)]
    [long]$TelemetryMaxBytes = 67108864,

    [string]$NativeTelemetryRemotePath = '/data/user/0/tv.danmaku.bili/files/bilisurfacebridge/refresh_samples.csv',

    [ValidateRange(1048576, 67108864)]
    [long]$NativeTelemetryMaxBytes = 16777216,

    [string]$PythonPath = 'python',

    [string]$ModulePackageName = 'dev.xpramt.bilisurfacebridge',

    [string]$ModulePreferencesPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
    throw 'Could not resolve the directory containing RunBilibiliJankLoop.ps1'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $scriptRoot '..\logs\autotest-runs'
}
if ([string]::IsNullOrWhiteSpace($TraceConfigPath)) {
    $TraceConfigPath = Join-Path $scriptRoot 'bili-frame-timeline.pbtxt'
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # ADB writes normal push/progress text to stderr on this host. Collect
        # both streams, then make the native exit code the only failure signal.
        $ErrorActionPreference = 'Continue'
        $output = @(& $AdbPath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        $renderedArguments = $Arguments -join ' '
        $formattedOutput = $output -join [Environment]::NewLine
        throw "ADB command failed with exit code ${exitCode}: ${renderedArguments}`n${formattedOutput}"
    }
    return $output
}

function Resolve-ModulePreferencesRelocatedPath {
    try {
        $searchPattern = "*/prefs/$ModulePackageName/module.xml"
        $escapedPackageName = [regex]::Escape($ModulePackageName)
        $xposedPaths = @(
            Invoke-Adb -Arguments @(
                '-s', $Serial, 'shell', 'su', '-c',
                "find /data/misc/apexdata -type f -path '$searchPattern' -print"
            ) |
                ForEach-Object { $_.ToString().Trim() } |
                Where-Object { $_ -match "/prefs/$escapedPackageName/module\.xml$" }
        )
        if ($xposedPaths.Count -gt 0) {
            return [string]$xposedPaths[0]
        }
    } catch {
        # The direct Android SharedPreferences path remains the compatibility fallback.
    }
    return ''
}

function Resolve-ModulePreferencesPath {
    if (-not [string]::IsNullOrWhiteSpace($ModulePreferencesPath)) {
        return [PSCustomObject]@{
            Path = $ModulePreferencesPath
            Source = 'explicit-override'
        }
    }

    $relocatedPath = Resolve-ModulePreferencesRelocatedPath
    if (-not [string]::IsNullOrWhiteSpace($relocatedPath)) {
        return [PSCustomObject]@{
            Path = $relocatedPath
            Source = 'lsposed-xposedsharedprefs'
        }
    }

    return [PSCustomObject]@{
        Path = "/data/user/0/$ModulePackageName/shared_prefs/module.xml"
        Source = 'android-sharedprefs-direct'
    }
}

function Get-ModulePreferenceState {
    $resolvedPath = Resolve-ModulePreferencesPath
    $result = [ordered]@{
        Available = $false
        Path = $resolvedPath.Path
        PathSource = $resolvedPath.Source
        Values = [ordered]@{}
        Error = $null
    }
    try {
        $output = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'su', '-c', "cat '$($resolvedPath.Path)'"))
        $xmlText = ($output -join [Environment]::NewLine).Trim()
        if ([string]::IsNullOrWhiteSpace($xmlText)) {
            throw "empty preference XML from $($resolvedPath.Path)"
        }
        [xml]$xml = $xmlText
        foreach ($node in @($xml.map.ChildNodes)) {
            $name = [string]$node.name
            $value = if ($node.Attributes['value']) {
                [string]$node.Attributes['value'].Value
            } else {
                [string]$node.InnerText
            }
            if (-not [string]::IsNullOrWhiteSpace($name)) {
                $result.Values[$name] = $value
            }
        }
        $result.Available = $true
    } catch {
        $result.Error = $_.Exception.Message
    }
    return [PSCustomObject]$result
}

function Get-ModulePackageState {
    $result = [ordered]@{
        PackageName = $ModulePackageName
        VersionCode = $null
        VersionName = $null
        ApkPaths = @()
        Error = $null
    }
    try {
        $packageOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'package', $ModulePackageName))
        $packageText = $packageOutput -join "`n"
        $versionCodeMatch = [regex]::Match($packageText, 'versionCode=(?<value>[^\s]+)')
        $versionNameMatch = [regex]::Match($packageText, 'versionName=(?<value>[^\s]+)')
        if ($versionCodeMatch.Success) {
            $result.VersionCode = $versionCodeMatch.Groups['value'].Value
        }
        if ($versionNameMatch.Success) {
            $result.VersionName = $versionNameMatch.Groups['value'].Value
        }
        $result.ApkPaths = @(
            @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $ModulePackageName)) |
                ForEach-Object { $_.ToString().Trim() } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
    } catch {
        $result.Error = $_.Exception.Message
    }
    return [PSCustomObject]$result
}

function Get-AndroidProcessIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProcessId,

        [string]$BootId = '',

        [double]$UptimeSeconds = 0
    )

    $result = [ordered]@{
        IdentityAvailable = $false
        Pid = $ProcessId
        Comm = $null
        State = $null
        ParentPid = $null
        StartTimeTicks = $null
        BootId = $BootId
        UptimeSecondsAtCapture = $UptimeSeconds
        Error = $null
    }
    try {
        $statOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'cat', "/proc/$ProcessId/stat"))
        $statText = ($statOutput -join ' ').Trim()
        $match = [regex]::Match($statText, '^\s*(?<pid>\d+)\s+\((?<comm>.*)\)\s+(?<rest>.+)$')
        if (-not $match.Success) {
            throw "could not parse /proc/$ProcessId/stat"
        }
        $rest = @($match.Groups['rest'].Value -split '\s+')
        if ($rest.Count -le 19) {
            throw "/proc/$ProcessId/stat did not contain starttime field"
        }
        $startTimeTicks = 0L
        if (-not [long]::TryParse($rest[19], [ref]$startTimeTicks)) {
            throw "invalid /proc/$ProcessId/stat starttime"
        }
        $result.Comm = $match.Groups['comm'].Value
        $result.State = $rest[0]
        $result.ParentPid = $rest[1]
        $result.StartTimeTicks = $startTimeTicks
        $result.IdentityAvailable = $true
    } catch {
        $result.Error = $_.Exception.Message
    }
    return [PSCustomObject]$result
}

function Get-ProcessLifecycleState {
    $result = [ordered]@{
        BilibiliPids = @()
        IjkServicePids = @()
        ProcessIdentities = @()
        BootId = $null
        UptimeSeconds = $null
        Error = $null
    }
    try {
        $bootOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'cat', '/proc/sys/kernel/random/boot_id'))
        $result.BootId = ($bootOutput -join '').Trim()
        $uptimeOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'cat', '/proc/uptime'))
        $uptimeText = ($uptimeOutput -join ' ').Trim()
        $uptimeToken = @($uptimeText -split '\s+')[0]
        $result.UptimeSeconds = [double]::Parse($uptimeToken, [Globalization.CultureInfo]::InvariantCulture)
        $bilibili = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pidof', $PackageName))
        $ijk = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pidof', "$PackageName`:ijkservice"))
        $result.BilibiliPids = @($bilibili -join ' ' -split '\s+' | Where-Object { $_ -match '^\d+$' })
        $result.IjkServicePids = @($ijk -join ' ' -split '\s+' | Where-Object { $_ -match '^\d+$' })
        $identities = New-Object 'System.Collections.Generic.List[object]'
        foreach ($processId in @($result.BilibiliPids + $result.IjkServicePids)) {
            $identity = Get-AndroidProcessIdentity -ProcessId ([string]$processId) -BootId ([string]$result.BootId) -UptimeSeconds ([double]$result.UptimeSeconds)
            [void]$identities.Add($identity)
        }
        $result.ProcessIdentities = @($identities.ToArray())
    } catch {
        $result.Error = $_.Exception.Message
    }
    return [PSCustomObject]$result
}

function Get-HookLogEvidence {
    param(
        [string[]]$AllowedPids = @()
    )

    $result = [ordered]@{
        Available = $false
        CaptureStartedAt = (Get-Date).ToString('o')
        CaptureFinishedAt = $null
        AllowedPids = @($AllowedPids)
        Lines = @()
        CounterSamples = @()
        PidsObserved = @()
        RendererEvidence = @()
        RawLineCount = 0
        FilteredLineCount = 0
        Error = $null
    }
    try {
        $allowed = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($pidValue in @($AllowedPids)) {
            if (-not [string]::IsNullOrWhiteSpace([string]$pidValue)) {
                [void]$allowed.Add(([string]$pidValue).Trim())
            }
        }
        # At 120 fps the native debug stream can roll the renderer-selection
        # lines out of a 4000-line tail before the player-ready check. Keep the
        # same bounded logcat evidence path, but retain enough history for the
        # renderer gate to see the startup selection and constructed layer.
        $lines = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'logcat', '-d', '-t', '20000', '-v', 'threadtime'))
        $result.RawLineCount = $lines.Count
        $evidenceLines = New-Object 'System.Collections.Generic.List[object]'
        $counterSamples = New-Object 'System.Collections.Generic.List[object]'
        $observedPids = New-Object 'System.Collections.Generic.HashSet[string]'
        $rendererEvidence = New-Object 'System.Collections.Generic.List[object]'
        foreach ($lineObject in $lines) {
            $line = $lineObject.ToString()
            $pidMatch = [regex]::Match($line, '^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(?<pid>\d+)\s+(?<tid>\d+)\s+')
            if (-not $pidMatch.Success) {
                continue
            }
            $logPid = $pidMatch.Groups['pid'].Value
            if ($allowed.Count -gt 0 -and -not $allowed.Contains($logPid)) {
                continue
            }
            if ($line -notmatch 'loaded for Bilibili|installed|hooked|Media3|IJK|compute_target_delay|is_need_drop_frame|timed=|videoDropBypassedCalls=|renderer=') {
                continue
            }
            [void]$observedPids.Add($logPid)
            $result.FilteredLineCount++
            [void]$evidenceLines.Add([PSCustomObject]@{
                    Text = $line
                    Pid = $logPid
                    Tid = $pidMatch.Groups['tid'].Value
                    LogTimestamp = [regex]::Match($line, '^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+').Value
                })
            $timedMatch = [regex]::Match($line, '\bMedia3 sync timed=(?<value>\d+)')
            $bypassMatch = [regex]::Match($line, '\bvideoDropBypassedCalls=(?<value>\d+)')
            $dropMatch = [regex]::Match($line, '\bvideoDropRequested=(?<value>\d+)')
            if ($timedMatch.Success -or $bypassMatch.Success -or $dropMatch.Success) {
                [void]$counterSamples.Add([PSCustomObject]@{
                        Pid = $logPid
                        LogTimestamp = [regex]::Match($line, '^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+').Value
                        Timed = if ($timedMatch.Success) { [long]$timedMatch.Groups['value'].Value } else { $null }
                        VideoDropBypassedCalls = if ($bypassMatch.Success) { [long]$bypassMatch.Groups['value'].Value } else { $null }
                        VideoDropRequested = if ($dropMatch.Success) { [long]$dropMatch.Groups['value'].Value } else { $null }
                    })
            }
            $selectedRendererMatch = [regex]::Match(
                $line, 'selected video-detail renderer=(?<value>.*?)\s+requested=')
            if ($selectedRendererMatch.Success) {
                [void]$rendererEvidence.Add([PSCustomObject]@{
                        Kind = 'selected'
                        Value = $selectedRendererMatch.Groups['value'].Value.Trim()
                        Pid = $logPid
                        Text = $line
                    })
            }
            $factoryRendererMatch = [regex]::Match(
                $line, 'factory renderer=(?<value>[^\s]+)')
            if ($factoryRendererMatch.Success) {
                [void]$rendererEvidence.Add([PSCustomObject]@{
                        Kind = 'factory'
                        Value = $factoryRendererMatch.Groups['value'].Value.Trim()
                        Pid = $logPid
                        Text = $line
                    })
            }
            $overlayRendererMatch = [regex]::Match(
                $line, 'debug overlay attached renderer=(?<value>[^\s]+)')
            if ($overlayRendererMatch.Success) {
                [void]$rendererEvidence.Add([PSCustomObject]@{
                        Kind = 'overlay'
                        Value = $overlayRendererMatch.Groups['value'].Value.Trim()
                        Pid = $logPid
                        Text = $line
                    })
            }
        }
        $result.Lines = @($evidenceLines.ToArray())
        $result.CounterSamples = @($counterSamples.ToArray())
        $result.PidsObserved = @($observedPids | ForEach-Object { $_ } | Sort-Object)
        $result.RendererEvidence = @($rendererEvidence.ToArray())
        $result.Available = $true
    } catch {
        $result.Error = $_.Exception.Message
    }
    $result.CaptureFinishedAt = (Get-Date).ToString('o')
    return [PSCustomObject]$result
}

function Get-TestRuntimeState {
    $processes = Get-ProcessLifecycleState
    $allowedPids = @($processes.BilibiliPids + $processes.IjkServicePids)
    return [PSCustomObject]@{
        CapturedAt = (Get-Date).ToString('o')
        Foreground = Get-ForegroundState
        Preferences = Get-ModulePreferenceState
        ModulePackage = Get-ModulePackageState
        Processes = $processes
        HookLogEvidence = Get-HookLogEvidence -AllowedPids $allowedPids
    }
}

function Get-RuntimeStateReport {
    param(
        [Parameter(Mandatory = $true)]
        [object]$RuntimeState
    )

    $hook = $RuntimeState.HookLogEvidence
    return [PSCustomObject]@{
        CapturedAt = $RuntimeState.CapturedAt
        Foreground = $RuntimeState.Foreground
        Preferences = $RuntimeState.Preferences
        ModulePackage = $RuntimeState.ModulePackage
        Processes = $RuntimeState.Processes
        HookLogEvidence = [PSCustomObject]@{
            Available = $hook.Available
            CaptureStartedAt = $hook.CaptureStartedAt
            CaptureFinishedAt = $hook.CaptureFinishedAt
            AllowedPids = @($hook.AllowedPids)
            PidsObserved = @($hook.PidsObserved)
            RawLineCount = $hook.RawLineCount
            FilteredLineCount = $hook.FilteredLineCount
            CounterSamples = @($hook.CounterSamples)
            RendererEvidence = @($hook.RendererEvidence)
            Error = $hook.Error
            Detail = 'Full log lines are intentionally kept out of the run report; use the bounded log artifact or gate summary for details.'
        }
    }
}

function Get-AnalysisReportSummary {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Analysis
    )

    $telemetry = $Analysis.media3_telemetry
    $nativeTelemetry = $Analysis.native_telemetry
    $nativeDisplayTrace = $Analysis.native_display_trace_analysis
    $scheduler = if ($null -ne $telemetry) { $telemetry.scheduler } else { $null }
    $calibration = if ($null -ne $telemetry) { $telemetry.clock_calibration } else { $null }
    return [PSCustomObject]@{
        Decision = $Analysis.decision
        StatusSummary = $Analysis.status_summary
        SensorQuality = $Analysis.sensor_quality
        SourceMode = $Analysis.source_mode
        CadenceObserved = $Analysis.cadence_observed
        SchedulerTargetAlignment = $Analysis.scheduler_target_alignment
        NativeHandoffAlignment = $Analysis.native_handoff_alignment
        TimingExamples = $Analysis.timing_examples
        NativeDisplayTrace = [PSCustomObject]@{
            Status = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.status } else { 'UNKNOWN' }
            NativeDisplayCount = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.native_display_count } else { 0 }
            NativeDisplayCallCount = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.native_display_call_count } else { 0 }
            BinderDisplayCallbackCount = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.binder_display_callback_count } else { 0 }
            NativeCallGapsCrossingExpected = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.dispatch_gaps_native_call_crossing_expected } else { 0 }
            OrderedBinderCallbackGapsCrossingExpected = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.dispatch_gaps_ordered_binder_before_callback_after } else { 0 }
            BinderCallbackTransport = if ($null -ne $nativeDisplayTrace) { $nativeDisplayTrace.ordered_binder_callback_transport } else { $null }
        }
        Media3Telemetry = [PSCustomObject]@{
            Status = if ($null -ne $telemetry) { $telemetry.status } else { 'UNKNOWN' }
            RowsRead = if ($null -ne $telemetry) { $telemetry.rows_read } else { 0 }
            RowsInTraceWindow = if ($null -ne $telemetry) { $telemetry.rows_in_trace_window } else { 0 }
            RowsInTraceMarginOnly = if ($null -ne $telemetry) { $telemetry.rows_in_trace_margin_only } else { 0 }
            RowsOutsideTraceWindow = if ($null -ne $telemetry) { $telemetry.rows_outside_trace_window } else { 0 }
            SessionIds = if ($null -ne $telemetry) { @($telemetry.session_ids) } else { @() }
            Scheduler = $scheduler
            ClockCalibration = $calibration
        }
        NativeTelemetry = [PSCustomObject]@{
            Status = if ($null -ne $nativeTelemetry) { $nativeTelemetry.status } else { 'UNKNOWN' }
            Build = if ($null -ne $nativeTelemetry) { $nativeTelemetry.native_telemetry_build } else { $null }
            RowsRead = if ($null -ne $nativeTelemetry) { $nativeTelemetry.rows_read } else { 0 }
            RowsInTraceWindow = if ($null -ne $nativeTelemetry) { $nativeTelemetry.rows_in_trace_window } else { 0 }
            RowsInTraceMarginOnly = if ($null -ne $nativeTelemetry) { $nativeTelemetry.rows_in_trace_margin_only } else { 0 }
            RowsOutsideTraceWindow = if ($null -ne $nativeTelemetry) { $nativeTelemetry.rows_outside_trace_window } else { 0 }
            EventCounts = if ($null -ne $nativeTelemetry) { $nativeTelemetry.event_counts } else { $null }
            SourceMode = if ($null -ne $nativeTelemetry) { $nativeTelemetry.source_mode } else { $null }
            ClockCalibration = if ($null -ne $nativeTelemetry) { $nativeTelemetry.clock_calibration } else { $null }
        }
    }
}

function Test-ExpectedRuntimePreferences {
    param(
        [Parameter(Mandatory = $true)]
        [object]$RuntimeState,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedRendererMode,

        [Parameter(Mandatory = $true)]
        [bool]$ExpectedRenderCadenceProbe,

        [Parameter(Mandatory = $true)]
        [bool]$ExpectedMedia3FrameSync,

        [Parameter(Mandatory = $true)]
        [bool]$ExpectedFrameQueueWaitRoundUp
    )

    $probeValue = if ($ExpectedRenderCadenceProbe) { 'true' } else { 'false' }
    $expected = [ordered]@{
        playback_debug_overlay = 'true'
        render_cadence_probe = $probeValue
        video_detail_renderer_mode = $ExpectedRendererMode
        media3_frame_sync = $ExpectedMedia3FrameSync.ToString().ToLowerInvariant()
        frame_queue_wait_round_up = $ExpectedFrameQueueWaitRoundUp.ToString().ToLowerInvariant()
        block_compute_target_delay = $BlockComputeTargetDelay.ToString().ToLowerInvariant()
        block_ijk_frame_drop = $BlockIjkFrameDrop.ToString().ToLowerInvariant()
    }
    $actual = $RuntimeState.Preferences.Values
    $checks = [ordered]@{}
    $allMatch = $RuntimeState.Preferences.Available
    foreach ($key in $expected.Keys) {
        $value = if ($actual.Contains($key)) { [string]$actual[$key] } else { $null }
        $matches = $RuntimeState.Preferences.Available -and $value -eq $expected[$key]
        $checks[$key] = [PSCustomObject]@{
            Expected = $expected[$key]
            Actual = $value
            Match = $matches
        }
        if (-not $matches) {
            $allMatch = $false
        }
    }
    return [PSCustomObject]@{
        Status = if ($allMatch) { 'PASS' } else { 'FAIL' }
        Expected = $expected
        Checks = $checks
        Detail = if ($allMatch) {
            'persistent module preferences match the requested measurement baseline'
        } else {
            'persistent module preferences are missing or do not match the requested measurement baseline'
        }
    }
}

function Sync-ModulePreferencesToLsposed {
    $relocatedPath = Resolve-ModulePreferencesRelocatedPath
    if ([string]::IsNullOrWhiteSpace($relocatedPath)) {
        return 'lsposed relocation not present; direct Android preferences remain active'
    }
    # Under LSPosed, the module process is redirected to this relocation path. Copying the
    # app-private source XML here is incorrect: the source can be stale while the framework
    # already contains the freshly committed profile. Only read the effective relocation later.
    return "LSPosed relocation is managed by the module process; no source copy performed path=$relocatedPath"
}

function Invoke-ModuleAutomationProfile {
    $probeValue = $RenderCadenceProbe.ToString().ToLowerInvariant()
    $media3FrameSyncValue = $Media3FrameSyncEnabled.ToString().ToLowerInvariant()
    $frameQueueWaitRoundUpValue = $FrameQueueWaitRoundUpEnabled.ToString().ToLowerInvariant()
    $blockDropValue = $BlockIjkFrameDrop.ToString().ToLowerInvariant()
    $blockComputeValue = $BlockComputeTargetDelay.ToString().ToLowerInvariant()
    # MainActivity applies the automation profile in onCreate.  Without a
    # module-only force-stop, am start may merely bring an existing settings
    # task to the front and leave the previous renderer mode persisted.
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'force-stop', $ModulePackageName) | Out-Null
    $output = @(Invoke-Adb -Arguments @(
            '-s', $Serial, 'shell', 'am', 'start', '-n',
            "$ModulePackageName/.MainActivity", '--es', 'automation_profile', $RendererMode,
            '--ez', 'render_cadence_probe', $probeValue,
            '--ez', 'media3_frame_sync', $media3FrameSyncValue,
            '--ez', 'frame_queue_wait_round_up', $frameQueueWaitRoundUpValue,
            '--ez', 'block_ijk_frame_drop', $blockDropValue,
            '--ez', 'block_compute_target_delay', $blockComputeValue
        ))
    $syncDetail = Sync-ModulePreferencesToLsposed
    return (($output -join ' ') + " | $syncDetail")
}

function Convert-RendererEvidenceToMode {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )
    if ($Value -match 'Texture') {
        return 'TEXTURE_EXTERNAL'
    }
    if ($Value -match 'Surface external|WithExternalRender') {
        return 'SURFACE_EXTERNAL'
    }
    if ($Value -match 'Surface') {
        return 'SURFACE'
    }
    return ''
}

function Test-RendererGate {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$RuntimeStates,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedRendererMode
    )

    $evidence = @(
        $RuntimeStates |
            ForEach-Object { @($_.HookLogEvidence.RendererEvidence) }
    )
    $selected = @($evidence | Where-Object { $_.Kind -eq 'selected' })
    # The debug overlay is attached to the selected detail-player renderer and is
    # stronger evidence than a later factory log from another renderer instance.
    # RuntimeAfter can contain renderer creation events after the fixed trace has
    # already ended, so do not let its last factory event overwrite player-ready
    # evidence from RuntimeAfterLaunch.
    $overlay = @($evidence | Where-Object { $_.Kind -eq 'overlay' })
    $factory = @($evidence | Where-Object { $_.Kind -eq 'factory' })
    $selectedMode = @(
        $selected | ForEach-Object { Convert-RendererEvidenceToMode -Value ([string]$_.Value) }
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1
    $actualModes = @(
        @(
            $overlay | ForEach-Object {
                Convert-RendererEvidenceToMode -Value ([string]$_.Value)
            }
        ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($actualModes.Count -eq 0) {
        $actualModes = @(
            @(
                $factory | ForEach-Object {
                    Convert-RendererEvidenceToMode -Value ([string]$_.Value)
                }
            ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
    }
    $actualMode = $actualModes | Select-Object -Last 1
    $selectedMatch = [string]$selectedMode -eq $ExpectedRendererMode
    $actualMatch = [string]$actualMode -eq $ExpectedRendererMode
    $pass = $selectedMatch -and $actualMatch
    return [PSCustomObject]@{
        Status = if ($pass) { 'PASS' } else { 'FAIL' }
        RequestedRenderer = $ExpectedRendererMode
        SelectedRenderer = $selectedMode
        ActualRenderer = $actualMode
        SelectedEvidenceCount = $selected.Count
        ActualEvidenceCount = $actualModes.Count
        Detail = if ($pass) {
            'requested renderer, selected renderer, and constructed video layer agree'
        } else {
            'renderer A/B requires current-PID selected and constructed-layer evidence with matching modes'
        }
    }
}

function Test-HookRuntimeEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [object]$BeforeRuntimeState,

        [Parameter(Mandatory = $true)]
        [object]$AfterRuntimeState,

        [Parameter(Mandatory = $true)]
        [bool]$Media3FrameSyncEnabled
    )

    $beforeLines = @($BeforeRuntimeState.HookLogEvidence.Lines | ForEach-Object { $_.Text })
    $afterLines = @($AfterRuntimeState.HookLogEvidence.Lines | ForEach-Object { $_.Text })
    $afterText = $afterLines -join "`n"
    # The trace can finish while the test host or another foreground window is
    # active. Process lifetime and same-window telemetry prove that Bilibili was
    # running; foreground-at-capture is informational and must not invalidate it.
    $bilibiliProcessPresent = @($AfterRuntimeState.Processes.BilibiliPids).Count -gt 0
    $bilibiliForegroundAtCapture = $AfterRuntimeState.Foreground.Package -eq $PackageName
    $ijkServiceProcessPresent = @($AfterRuntimeState.Processes.IjkServicePids).Count -gt 0

    $beforeIdentities = @($BeforeRuntimeState.Processes.ProcessIdentities | Where-Object { $_.IdentityAvailable })
    $afterIdentities = @($AfterRuntimeState.Processes.ProcessIdentities | Where-Object { $_.IdentityAvailable })
    $sameBootId =
        -not [string]::IsNullOrWhiteSpace([string]$BeforeRuntimeState.Processes.BootId) -and
        [string]$BeforeRuntimeState.Processes.BootId -eq [string]$AfterRuntimeState.Processes.BootId
    $sameIjkLifetime = $false
    foreach ($afterIdentity in $afterIdentities | Where-Object { @($AfterRuntimeState.Processes.IjkServicePids) -contains [string]$_.Pid }) {
        $matchingBefore = $beforeIdentities | Where-Object {
            [string]$_.Pid -eq [string]$afterIdentity.Pid -and
            [long]$_.StartTimeTicks -eq [long]$afterIdentity.StartTimeTicks -and
            [string]$_.BootId -eq [string]$afterIdentity.BootId
        }
        if (@($matchingBefore).Count -gt 0) {
            $sameIjkLifetime = $true
            break
        }
    }
    $observedCurrentPid = @(
        @($AfterRuntimeState.HookLogEvidence.PidsObserved) |
            Where-Object {
                @($AfterRuntimeState.Processes.BilibiliPids + $AfterRuntimeState.Processes.IjkServicePids) -contains [string]$_
            }
    )

    $beforeTimedMax = 0L
    foreach ($sample in @($BeforeRuntimeState.HookLogEvidence.CounterSamples)) {
        if ($null -ne $sample.Timed -and [long]$sample.Timed -gt $beforeTimedMax) {
            $beforeTimedMax = [long]$sample.Timed
        }
    }
    $afterTimedMax = 0L
    foreach ($sample in @($AfterRuntimeState.HookLogEvidence.CounterSamples)) {
        if ($null -ne $sample.Timed -and [long]$sample.Timed -gt $afterTimedMax) {
            $afterTimedMax = [long]$sample.Timed
        }
    }
    $afterBypassSamples = @(
        @($AfterRuntimeState.HookLogEvidence.CounterSamples) |
            Where-Object { $null -ne $_.VideoDropBypassedCalls -and [long]$_.VideoDropBypassedCalls -gt 0 }
    )
    $afterDropRequestedSamples = @(
        @($AfterRuntimeState.HookLogEvidence.CounterSamples) |
            Where-Object { $null -ne $_.VideoDropRequested }
    )
    $media3TelemetryRequired = $Media3FrameSyncEnabled
    $media3TimedRequired = $Media3FrameSyncEnabled
    $checks = [ordered]@{
        bilibili_process_present = $bilibiliProcessPresent
        bilibili_foreground_at_capture = $bilibiliForegroundAtCapture
        ijk_service_process_present = $ijkServiceProcessPresent
        same_boot_id = $sameBootId
        same_ijk_process_lifetime = $sameIjkLifetime
        current_pid_log_evidence = $observedCurrentPid.Count -gt 0
        ijk_service_hooked = $afterText -match 'BiliSurfaceBridge.*(installed|hooked|Media3|IJK)'
        media3_runtime_telemetry = $afterText -match 'Media3 sync timed='
        timed_releases_nonzero_same_window = ($afterTimedMax -gt $beforeTimedMax)
        compute_target_delay_telemetry = $afterText -match 'compute_target_delay|computeTarget(?:>=|MaxMs=)'
        ijk_drop_bypass_telemetry = $afterText -match 'videoDropBypassedCalls='
        video_drop_bypassed_nonzero_same_window = $afterBypassSamples.Count -gt 0
    }
    $checks.media3_runtime_telemetry_required = $media3TelemetryRequired
    $checks.timed_releases_required = $media3TimedRequired
    $allMatch = $BeforeRuntimeState.HookLogEvidence.Available -and
        $AfterRuntimeState.HookLogEvidence.Available -and
        $checks.bilibili_process_present -and
        $checks.ijk_service_process_present -and
        $checks.same_boot_id -and
        $checks.same_ijk_process_lifetime -and
        $checks.current_pid_log_evidence -and
        $checks.ijk_service_hooked -and
        (-not $media3TelemetryRequired -or $checks.media3_runtime_telemetry) -and
        (-not $media3TimedRequired -or $checks.timed_releases_nonzero_same_window) -and
        $checks.compute_target_delay_telemetry -and
        $checks.ijk_drop_bypass_telemetry -and
        (-not $BlockIjkFrameDrop -or $checks.video_drop_bypassed_nonzero_same_window)
    return [PSCustomObject]@{
        Status = if ($allMatch) { 'PASS' } else { 'UNCONFIRMED' }
        Checks = $checks
        CounterEvidence = [PSCustomObject]@{
            BeforeTimedMax = $beforeTimedMax
            AfterTimedMax = $afterTimedMax
            TimedDelta = $afterTimedMax - $beforeTimedMax
            AfterVideoDropBypassedPositiveSampleCount = $afterBypassSamples.Count
            AfterVideoDropRequestedSampleCount = $afterDropRequestedSamples.Count
            SameWindowRule = 'Media3 timed is a cumulative counter increase; videoDropBypassedCalls is an interval counter and must contain a positive current-PID sample.'
        }
        ProcessEvidence = [PSCustomObject]@{
            Before = $beforeIdentities
            After = $afterIdentities
            ObservedCurrentLogPids = $observedCurrentPid
        }
        Detail = if ($allMatch) {
            'Current-PID hook logs, process lifetime, and same-window nonzero timed/bypass telemetry are all present; foreground-at-capture is informational'
        } else {
            'runtime hook evidence is incomplete; current-PID process lifetime and same-window nonzero telemetry are required'
        }
    }
}

function Test-AdbSerialReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CandidateSerial
    )

    try {
        $output = @(& $AdbPath '-s' $CandidateSerial 'get-state' 2>&1)
        $exitCode = $LASTEXITCODE
        return [PSCustomObject]@{
            Ready = ($exitCode -eq 0)
            Detail = ($output -join ' ')
        }
    } catch {
        return [PSCustomObject]@{
            Ready = $false
            Detail = $_.Exception.Message
        }
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
            return [PSCustomObject]@{ Connected = $false; Detail = "connect timeout after ${ConnectTimeoutSeconds}s" }
        }
        $detail = @($process.StandardOutput.ReadToEnd(), $process.StandardError.ReadToEnd()) -join ' '
        return [PSCustomObject]@{
            Connected = ($process.ExitCode -eq 0)
            Detail = $detail.Trim()
        }
    } catch {
        return [PSCustomObject]@{ Connected = $false; Detail = $_.Exception.Message }
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
            [void]$attempts.Add([PSCustomObject]@{ Serial = $candidate; Method = 'already-connected'; Success = $true; Detail = $state.Detail })
            return [PSCustomObject]@{ Success = $true; Serial = $candidate; Attempts = @($attempts.ToArray()) }
        }

        $connectDetail = 'connect skipped'
        $connectSuccess = $false
        if ($candidate -match ':\d+$') {
            $connectResult = Invoke-AdbConnectWithTimeout -CandidateSerial $candidate
            $connectDetail = $connectResult.Detail
            $connectSuccess = $connectResult.Connected
        }
        $stateAfterConnect = Test-AdbSerialReady -CandidateSerial $candidate
        $attemptSuccess = $stateAfterConnect.Ready
        $attemptMethod = if ($connectSuccess) { 'adb-connect' } else { 'adb-connect-failed' }
        $attemptDetail = if ($attemptSuccess) { $stateAfterConnect.Detail } else { $connectDetail + '; state=' + $stateAfterConnect.Detail }
        [void]$attempts.Add([PSCustomObject]@{ Serial = $candidate; Method = $attemptMethod; Success = $attemptSuccess; Detail = $attemptDetail })
        if ($attemptSuccess) {
            return [PSCustomObject]@{ Success = $true; Serial = $candidate; Attempts = @($attempts.ToArray()) }
        }
    }

    return [PSCustomObject]@{ Success = $false; Serial = ''; Attempts = @($attempts.ToArray()) }
}

function Get-ForegroundState {
    $activityOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'activity', 'activities'))
    $activityText = $activityOutput -join "`n"
    $patterns = @(
        'mResumedActivity:.*?\bu\d+\s+(?<component>[^\s}]+/[^\s}]+)',
        'mFocusedApp=.*?\bu\d+\s+(?<component>[^\s}]+/[^\s}]+)'
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($activityText, $pattern)
        if ($match.Success) {
            $component = $match.Groups['component'].Value
            return [PSCustomObject]@{ Package = $component.Split('/')[0]; Component = $component }
        }
    }
    return [PSCustomObject]@{ Package = ''; Component = '' }
}

function Get-CurrentRotation {
    $displayOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'window', 'displays'))
    $displayText = $displayOutput -join "`n"
    $match = [regex]::Match($displayText, 'mCurrentRotation=ROTATION_(?<rotation>\d+)')
    if ($match.Success) {
        return [int]$match.Groups['rotation'].Value
    }
    return -1
}

function Invoke-ExitFullscreenIfNeeded {
    $initialRotation = Get-CurrentRotation
    if ($initialRotation -ne 90 -and $initialRotation -ne 270) {
        return [PSCustomObject]@{
            Success = $true
            InitialRotation = $initialRotation
            FinalRotation = $initialRotation
            Detail = 'player was already in portrait or non-landscape state'
        }
    }

    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'input', 'keyevent', '4') | Out-Null
    $finalRotation = $initialRotation
    $transitionDeadline = (Get-Date).AddSeconds($FullscreenTransitionTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 100
        $finalRotation = Get-CurrentRotation
        if ($finalRotation -ne 90 -and $finalRotation -ne 270 -and $finalRotation -ge 0) {
            return [PSCustomObject]@{
                Success = $true
                InitialRotation = $initialRotation
                FinalRotation = $finalRotation
                Detail = 'previous fullscreen state exited with Back key'
            }
        }
    } while ((Get-Date) -lt $transitionDeadline)

    return [PSCustomObject]@{
        Success = $false
        InitialRotation = $initialRotation
        FinalRotation = $finalRotation
        Detail = 'previous fullscreen state did not exit'
    }
}

function Invoke-FullscreenPlayer {
    $initialRotation = Get-CurrentRotation
    if ($initialRotation -eq 90 -or $initialRotation -eq 270) {
        return [PSCustomObject]@{
            Success = $true
            InitialRotation = $initialRotation
            FinalRotation = $initialRotation
            Attempts = 0
            Detail = 'player was already in landscape fullscreen'
        }
    }

    $finalRotation = $initialRotation
    $success = $false
    $attemptsUsed = 0
    for ($attempt = 1; $attempt -le $FullscreenAttempts; $attempt++) {
        $attemptsUsed = $attempt
        Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'input', 'tap', [string]$FullscreenRevealX, [string]$FullscreenRevealY) | Out-Null
        Start-Sleep -Milliseconds $FullscreenRevealDelayMilliseconds

        $transitionDeadline = (Get-Date).AddSeconds($FullscreenTransitionTimeoutSeconds)
        $buttonPoints = @(
            [PSCustomObject]@{ X = $FullscreenButtonX; Y = $FullscreenButtonY }
        )
        foreach ($buttonPoint in $buttonPoints) {
            Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'input', 'tap', [string]$buttonPoint.X, [string]$buttonPoint.Y) | Out-Null
            Start-Sleep -Milliseconds 100
            $finalRotation = Get-CurrentRotation
            if ($finalRotation -eq 90 -or $finalRotation -eq 270) {
                $success = $true
                break
            }
            if ((Get-Date) -ge $transitionDeadline) {
                break
            }
        }

        if ($success) {
            break
        }
        do {
            Start-Sleep -Milliseconds 100
            $finalRotation = Get-CurrentRotation
            if ($finalRotation -eq 90 -or $finalRotation -eq 270) {
                $success = $true
                break
            }
        } while ((Get-Date) -lt $transitionDeadline)

        if ($success) {
            break
        }
    }

    return [PSCustomObject]@{
        Success = $success
        InitialRotation = $initialRotation
        FinalRotation = $finalRotation
        Attempts = $attemptsUsed
        Detail = if ($success) { 'landscape fullscreen confirmed by display rotation' } else { 'fullscreen transition was not confirmed' }
    }
}

function Get-RemoteFileSize {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RemotePath
    )

    $output = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'su', '-c', "stat -c %s '$RemotePath' 2>/dev/null || echo 0"))
    $size = 0L
    foreach ($line in $output) {
        if ([long]::TryParse(($line.ToString().Trim()), [ref]$size)) {
            return $size
        }
    }
    return 0L
}

function Save-AdbExecOut {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RemoteCommand,

        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $AdbPath
    $startInfo.Arguments = "-s $Serial exec-out su -c `"$RemoteCommand`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $fileStream = $null
    try {
        [void]$process.Start()
        $fileStream = [System.IO.File]::Open($DestinationPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $process.StandardOutput.BaseStream.CopyTo($fileStream)
        $fileStream.Flush()
        $fileStream.Dispose()
        $fileStream = $null
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "ADB exec-out failed with exit code $($process.ExitCode): $errorText"
        }
    } finally {
        if ($null -ne $fileStream) {
            $fileStream.Dispose()
        }
        $process.Dispose()
    }
}

function Compare-Reports {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Baseline,

        [Parameter(Mandatory = $true)]
        [object]$Candidate
    )

    $baselineSmallGaps = [int]$Baseline.video_present_summary.small_cadence_gap_count
    $candidateSmallGaps = [int]$Candidate.video_present_summary.small_cadence_gap_count
    $baselineLargeGaps = [int]$Baseline.video_present_summary.interval_gt_100ms_count
    $candidateLargeGaps = [int]$Candidate.video_present_summary.interval_gt_100ms_count
    $baselineCoverage = [double]$Baseline.sensor_quality.video_coverage_ratio_of_trace_span
    $candidateCoverage = [double]$Candidate.sensor_quality.video_coverage_ratio_of_trace_span
    $baselineLowFps = [double]$Baseline.video_present_summary.one_percent_low_fps_excluding_over_100ms
    $candidateLowFps = [double]$Candidate.video_present_summary.one_percent_low_fps_excluding_over_100ms

    $improved = (
        $candidateSmallGaps -le $baselineSmallGaps -and
        $candidateLargeGaps -le $baselineLargeGaps -and
        $candidateCoverage -ge $baselineCoverage -and
        $candidateLowFps -ge $baselineLowFps -and
        (
            $candidateSmallGaps -lt $baselineSmallGaps -or
            $candidateLargeGaps -lt $baselineLargeGaps -or
            $candidateCoverage -gt $baselineCoverage -or
            $candidateLowFps -gt $baselineLowFps
        )
    )
    $resolved = ($candidateSmallGaps -eq 0 -and $candidateLargeGaps -eq 0)
    return [PSCustomObject]@{
        Improved = $improved
        Resolved = $resolved
        BaselineSmallCadenceGapCount = $baselineSmallGaps
        CandidateSmallCadenceGapCount = $candidateSmallGaps
        BaselineLargeGapCount = $baselineLargeGaps
        CandidateLargeGapCount = $candidateLargeGaps
        BaselineVideoCoverage = $baselineCoverage
        CandidateVideoCoverage = $candidateCoverage
        BaselineOnePercentLowFpsExcludingLargeGaps = $baselineLowFps
        CandidateOnePercentLowFpsExcludingLargeGaps = $candidateLowFps
    }
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB executable not found: $AdbPath"
}
if (-not (Test-Path -LiteralPath $TraceConfigPath -PathType Leaf)) {
    throw "Perfetto config not found: $TraceConfigPath"
}
if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    [System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
}

$startedAt = Get-Date
$stamp = $startedAt.ToString('yyyyMMdd-HHmmssfff')
$remoteConfigPath = '/data/local/tmp/bili-frame-timeline-autotest.pbtxt'
$remoteTracePath = '/data/local/tmp/bili-frame-timeline-autotest.pftrace'
$localTracePath = Join-Path $OutputDirectory "jank-$stamp.pftrace"
$localAnalysisPath = Join-Path $OutputDirectory "jank-$stamp.analysis.json"
$localReportPath = Join-Path $OutputDirectory "jank-$stamp.run.json"
$localTelemetryPath = Join-Path $OutputDirectory "jank-$stamp.media3_frame_timing.csv"
$localNativeTelemetryPath = Join-Path $OutputDirectory "jank-$stamp.refresh_samples.csv"
$events = New-Object 'System.Collections.Generic.List[object]'
$result = 'FAIL'
$failure = $null
$selection = $null
$selectedSerial = ''
$analysis = $null
$comparison = $null
$runtimeBefore = $null
$runtimeAfterLaunch = $null
$runtimeAtMeasurement = $null
$runtimeAfter = $null
$preferenceGate = $null
$hookGate = $null
$rendererGate = $null
$profileApplyOutput = $null
$foregroundAfterLaunch = $null
$fullscreenState = $null
$modeReady = $false
$observedVideoFps = $null
$sourceModeStatus = 'UNKNOWN'
$schedulerTargetAlignmentStatus = 'UNKNOWN'
$telemetryCapture = $null
$nativeTelemetryCapture = $null

try {
    $selection = Select-WorkingSerial
    if (-not $selection.Success) {
        $attemptDetails = @($selection.Attempts | ForEach-Object { "$($_.Serial): $($_.Detail)" }) -join ' | '
        throw "No ADB device available. Tried: $attemptDetails"
    }
    $Serial = $selection.Serial
    $selectedSerial = $Serial
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'adb-device-selected'; Detail = $Serial })

    $profileApplyOutput = Invoke-ModuleAutomationProfile
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'automation-profile-applied'
        Detail = "renderer=$RendererMode; render_cadence_probe=$RenderCadenceProbe; output=$profileApplyOutput"
    })
    Start-Sleep -Milliseconds 500
    $runtimeBefore = Get-TestRuntimeState
    $preferenceGate = Test-ExpectedRuntimePreferences -RuntimeState $runtimeBefore `
        -ExpectedRendererMode $RendererMode -ExpectedRenderCadenceProbe $RenderCadenceProbe `
        -ExpectedMedia3FrameSync $Media3FrameSyncEnabled `
        -ExpectedFrameQueueWaitRoundUp $FrameQueueWaitRoundUpEnabled
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'runtime-preferences-checked'
        Detail = "status=$($preferenceGate.Status); $($preferenceGate.Detail)"
    })
    if ($preferenceGate.Status -ne 'PASS') {
        throw "Runtime preference baseline is not active: $($preferenceGate.Detail)"
    }

    Invoke-Adb -Arguments @('-s', $Serial, 'push', $TraceConfigPath, $remoteConfigPath) | Out-Null
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'su', '-c', "rm -f '$remoteTracePath'") | Out-Null
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'perfetto-prepared'; Detail = $remoteTracePath })

    $fullscreenReset = Invoke-ExitFullscreenIfNeeded
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'fullscreen-reset'
        Detail = "success=$($fullscreenReset.Success); initial_rotation=$($fullscreenReset.InitialRotation); final_rotation=$($fullscreenReset.FinalRotation); $($fullscreenReset.Detail)"
    })
    if (-not $fullscreenReset.Success) {
        throw 'Could not reset the previous fullscreen state before starting a new URL test'
    }

    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'force-stop', $PackageName) | Out-Null
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'bilibili-force-stopped'; Detail = $PackageName })
    Start-Sleep -Milliseconds 500

    if ($LaunchMode -eq 'OfflineCache') {
        $launchOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'start', '-W', '-n', "$PackageName/.MainActivityV2"))
        [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'main-activity-launched'; Detail = ($launchOutput -join ' | ') })
        Start-Sleep -Milliseconds ($OfflineTapDelayMilliseconds * 2)
        $taps = [System.Collections.Generic.List[object]]::new()
        $taps.Add(@{ Name = 'mine-tab'; X = $OfflineMineTabX; Y = $OfflineMineTabY })
        $taps.Add(@{ Name = 'offline-cache-entry'; X = $OfflineCacheEntryX; Y = $OfflineCacheEntryY })
        $level = 0
        foreach ($y in $OfflineItemTapsY) {
            $level++
            $taps.Add(@{ Name = "offline-item-level$level"; X = $OfflineFirstItemX; Y = $y })
        }
        foreach ($tap in $taps) {
            Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'input', 'tap', [string]$tap.X, [string]$tap.Y) | Out-Null
            [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = "offline-tap-$($tap.Name)"; Detail = "x=$($tap.X); y=$($tap.Y)" })
            Start-Sleep -Milliseconds $OfflineTapDelayMilliseconds
        }
    } else {
        $launchOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', $Url))
        [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'url-launched'; Detail = ($launchOutput -join ' | ') })
    }

    $launchDeadline = (Get-Date).AddSeconds($LaunchTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $foregroundAfterLaunch = Get-ForegroundState
        if ($foregroundAfterLaunch.Package -eq $PackageName) {
            break
        }
    } while ((Get-Date) -lt $launchDeadline)
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'foreground-after-launch'; Detail = $foregroundAfterLaunch.Component })
    if ($foregroundAfterLaunch.Package -ne $PackageName) {
        throw "URL did not foreground $PackageName within $LaunchTimeoutSeconds seconds"
    }

    if ($PlayerReadyDelaySeconds -gt 0) {
        Start-Sleep -Seconds $PlayerReadyDelaySeconds
    }
    $runtimeAfterLaunch = Get-TestRuntimeState
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'renderer-evidence-after-player-ready'
        Detail = "renderer_evidence_count=$(@($runtimeAfterLaunch.HookLogEvidence.RendererEvidence).Count)"
    })
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'player-ready-wait-finished'; Detail = "waited_seconds=$PlayerReadyDelaySeconds after foreground" })

    $fullscreenState = Invoke-FullscreenPlayer
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'fullscreen-transition'
        Detail = "success=$($fullscreenState.Success); initial_rotation=$($fullscreenState.InitialRotation); final_rotation=$($fullscreenState.FinalRotation); attempts=$($fullscreenState.Attempts); $($fullscreenState.Detail)"
    })
    if (-not $fullscreenState.Success) {
        throw 'Bilibili player did not enter landscape fullscreen; 120fps mode was not activated'
    }
    if ($FullscreenStabilizationSeconds -gt 0) {
        Start-Sleep -Seconds $FullscreenStabilizationSeconds
    }
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'target-video-mode-started'; Detail = "target_fps=$TargetVideoFps; tolerance=$TargetVideoFpsTolerance" })

    $runtimeAtMeasurement = Get-TestRuntimeState

    $perfettoCommand = "/system/bin/perfetto --txt --background-wait -c '$remoteConfigPath' -o '$remoteTracePath'"
    $perfettoStartOutput = @(Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'su', '-c', $perfettoCommand))
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'perfetto-started'; Detail = ($perfettoStartOutput -join ' | ') })
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'measurement-window-started'; Detail = 'fullscreen was stable for 20 seconds; trace capture is configured for a fixed 30-second measurement' })

    $traceDeadline = (Get-Date).AddSeconds($CaptureWaitSeconds)
    $lastSize = 0L
    $stableReads = 0
    do {
        Start-Sleep -Milliseconds 1000
        $currentSize = Get-RemoteFileSize -RemotePath $remoteTracePath
        if ($currentSize -gt 0 -and $currentSize -eq $lastSize) {
            $stableReads++
        } else {
            $stableReads = 0
        }
        $lastSize = $currentSize
    } while ($stableReads -lt 2 -and (Get-Date) -lt $traceDeadline)
    if ($lastSize -le 0) {
        throw "Perfetto trace did not become available within $CaptureWaitSeconds seconds"
    }
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'perfetto-finished'; Detail = "remote_bytes=$lastSize" })

    Save-AdbExecOut -RemoteCommand "cat '$remoteTracePath'" -DestinationPath $localTracePath
    if ((Get-Item -LiteralPath $localTracePath).Length -le 0) {
        throw "Pulled Perfetto trace is empty: $localTracePath"
    }
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'trace-pulled'; Detail = $localTracePath })

    Start-Sleep -Milliseconds 500
    $telemetryCapture = [ordered]@{
        RemotePath = $TelemetryRemotePath
        LocalPath = $localTelemetryPath
        Available = $false
        RemoteBytes = 0L
        CaptureMode = 'NOT_ATTEMPTED'
        Status = 'NOT_FOUND'
        Error = $null
    }
    try {
        $telemetryBytes = Get-RemoteFileSize -RemotePath $TelemetryRemotePath
        $telemetryCapture.RemoteBytes = $telemetryBytes
        if ($telemetryBytes -gt $TelemetryMaxBytes) {
            $telemetryCapture.CaptureMode = 'HEADER_AND_TAIL_BYTES'
            $boundedTelemetryCommand = "head -n 1 '$TelemetryRemotePath'; tail -c $TelemetryMaxBytes '$TelemetryRemotePath'"
            Save-AdbExecOut -RemoteCommand $boundedTelemetryCommand -DestinationPath $localTelemetryPath
            if ((Get-Item -LiteralPath $localTelemetryPath).Length -gt 0) {
                $telemetryCapture.Available = $true
                $telemetryCapture.Status = 'BOUNDED_TAIL_PULLED'
            } else {
                $telemetryCapture.Status = 'EMPTY'
            }
        } elseif ($telemetryBytes -gt 0) {
            $telemetryCapture.CaptureMode = 'FULL_FILE'
            Save-AdbExecOut -RemoteCommand "cat '$TelemetryRemotePath'" -DestinationPath $localTelemetryPath
            if ((Get-Item -LiteralPath $localTelemetryPath).Length -gt 0) {
                $telemetryCapture.Available = $true
                $telemetryCapture.Status = 'PULLED'
            } else {
                $telemetryCapture.Status = 'EMPTY'
            }
        }
    } catch {
        $telemetryCapture.Status = 'ERROR'
        $telemetryCapture.Error = $_.Exception.Message
    }
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'media3-telemetry-captured'
        Detail = "status=$($telemetryCapture.Status); remote_bytes=$($telemetryCapture.RemoteBytes); path=$($telemetryCapture.LocalPath)"
    })

    $nativeTelemetryCapture = [ordered]@{
        RemotePath = $NativeTelemetryRemotePath
        LocalPath = $localNativeTelemetryPath
        Available = $false
        RemoteBytes = 0L
        CaptureMode = 'NOT_ATTEMPTED'
        Status = 'NOT_FOUND'
        Error = $null
    }
    try {
        $nativeTelemetryBytes = Get-RemoteFileSize -RemotePath $NativeTelemetryRemotePath
        $nativeTelemetryCapture.RemoteBytes = $nativeTelemetryBytes
        if ($nativeTelemetryBytes -gt $NativeTelemetryMaxBytes) {
            $nativeTelemetryCapture.CaptureMode = 'HEADER_AND_TAIL_BYTES'
            $boundedNativeTelemetryCommand = "head -n 3 '$NativeTelemetryRemotePath'; tail -c $NativeTelemetryMaxBytes '$NativeTelemetryRemotePath'"
            Save-AdbExecOut -RemoteCommand $boundedNativeTelemetryCommand -DestinationPath $localNativeTelemetryPath
            if ((Get-Item -LiteralPath $localNativeTelemetryPath).Length -gt 0) {
                $nativeTelemetryCapture.Available = $true
                $nativeTelemetryCapture.Status = 'BOUNDED_TAIL_PULLED'
            } else {
                $nativeTelemetryCapture.Status = 'EMPTY'
            }
        } elseif ($nativeTelemetryBytes -gt 0) {
            $nativeTelemetryCapture.CaptureMode = 'FULL_FILE'
            Save-AdbExecOut -RemoteCommand "cat '$NativeTelemetryRemotePath'" -DestinationPath $localNativeTelemetryPath
            if ((Get-Item -LiteralPath $localNativeTelemetryPath).Length -gt 0) {
                $nativeTelemetryCapture.Available = $true
                $nativeTelemetryCapture.Status = 'PULLED'
            } else {
                $nativeTelemetryCapture.Status = 'EMPTY'
            }
        }
    } catch {
        $nativeTelemetryCapture.Status = 'ERROR'
        $nativeTelemetryCapture.Error = $_.Exception.Message
    }
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'native-telemetry-captured'
        Detail = "status=$($nativeTelemetryCapture.Status); remote_bytes=$($nativeTelemetryCapture.RemoteBytes); path=$($nativeTelemetryCapture.LocalPath)"
    })

    $runtimeAfter = Get-TestRuntimeState

    $analysisArguments = @{
        TracePath = $localTracePath
        ReportPath = $localAnalysisPath
        PackageName = $PackageName
        GapThresholdPeriods = $GapThresholdPeriods
        DisplayDeadlineThresholdMs = 2.42
        NominalSourceFps = $TargetVideoFps
        PythonPath = $PythonPath
    }
    if ($telemetryCapture.Available) {
        $analysisArguments.TelemetryCsvPath = $localTelemetryPath
    }
    if ($nativeTelemetryCapture.Available) {
        $analysisArguments.NativeTelemetryCsvPath = $localNativeTelemetryPath
    }
    & (Join-Path $scriptRoot 'AnalyzeBiliFrameTimeline.ps1') @analysisArguments
    $analysisExitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $localAnalysisPath -PathType Leaf)) {
        throw "Analyzer did not create report: $localAnalysisPath"
    }
    $analysis = Get-Content -LiteralPath $localAnalysisPath -Raw | ConvertFrom-Json
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'trace-analyzed'; Detail = $analysis.decision.status })

    $hookGate = Test-HookRuntimeEvidence -BeforeRuntimeState $runtimeAtMeasurement `
        -AfterRuntimeState $runtimeAfter -Media3FrameSyncEnabled $Media3FrameSyncEnabled
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'runtime-hooks-checked'
        Detail = "status=$($hookGate.Status); $($hookGate.Detail); timed_delta=$($hookGate.CounterEvidence.TimedDelta); bypass_positive_samples=$($hookGate.CounterEvidence.AfterVideoDropBypassedPositiveSampleCount)"
    })

    $rendererGate = Test-RendererGate -RuntimeStates @($runtimeAfterLaunch, $runtimeAfter) `
        -ExpectedRendererMode $RendererMode
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'renderer-gate-checked'
        Detail = "status=$($rendererGate.Status); requested=$($rendererGate.RequestedRenderer); selected=$($rendererGate.SelectedRenderer); actual=$($rendererGate.ActualRenderer); $($rendererGate.Detail)"
    })
    if ($rendererGate.Status -ne 'PASS') {
        throw "Actual renderer could not be proven for requested $RendererMode"
    }

    if ($null -ne $analysis.measurement.observed_video_surface_cadence_fps) {
        $observedVideoFps = [double]$analysis.measurement.observed_video_surface_cadence_fps
    }
    $sourceModeStatus = [string]$analysis.status_summary.source_mode_status
    $schedulerTargetAlignmentStatus = [string]$analysis.status_summary.scheduler_target_alignment
    $videoSpanSeconds = if ($null -ne $analysis.sensor_quality.video_present_span_ms) {
        [double]$analysis.sensor_quality.video_present_span_ms / 1000.0
    } else {
        0.0
    }
    $modeReady = (
        [string]$analysis.fixed_analysis_window.status -eq 'VALID' -and
        [int]$analysis.fixed_analysis_window.present_count -ge 100
    )
    [void]$events.Add([PSCustomObject]@{
        Time = (Get-Date).ToString('o')
        Name = 'target-video-mode-checked'
        Detail = "source_mode=$sourceModeStatus; observed_present_cadence_fps=$observedVideoFps; full_present_span_seconds=$videoSpanSeconds; fixed_window_status=$($analysis.fixed_analysis_window.status); fixed_window_presents=$($analysis.fixed_analysis_window.present_count); ready=$modeReady"
    })
    if (-not $modeReady) {
        throw "Trace did not provide a valid fixed 30-second video-present window"
    }

    if (-not [string]::IsNullOrWhiteSpace($BaselineReportPath)) {
        if (-not (Test-Path -LiteralPath $BaselineReportPath -PathType Leaf)) {
            throw "Baseline report not found: $BaselineReportPath"
        }
        $baseline = Get-Content -LiteralPath $BaselineReportPath -Raw | ConvertFrom-Json
        $comparison = Compare-Reports -Baseline $baseline -Candidate $analysis
        [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'baseline-compared'; Detail = "improved=$($comparison.Improved); resolved=$($comparison.Resolved)" })
    }
    $result = if (
        $analysis.sensor_quality.status -eq 'VALID' -and
        $analysis.fixed_analysis_window.status -eq 'VALID' -and
        $preferenceGate.Status -eq 'PASS' -and
        $hookGate.Status -eq 'PASS' -and
        $rendererGate.Status -eq 'PASS'
    ) { 'PASS' } else { 'FAIL' }
} catch {
    $failure = $_.Exception.Message
    [void]$events.Add([PSCustomObject]@{ Time = (Get-Date).ToString('o'); Name = 'failure'; Detail = $failure })
}

$finishedAt = Get-Date
$runReport = [PSCustomObject]@{
    Result = $result
    WorkflowStatus = $result
    ResultSemantics = 'Result reports whether the requested capture and evidence gates completed; it is not a no-jank or stutter-resolved verdict.'
    SensorQualityStatus = if ($analysis) { $analysis.sensor_quality.status } else { 'UNKNOWN' }
    SourceModeStatus = $sourceModeStatus
    CadenceObserved = if ($analysis) { $analysis.cadence_observed } else { $null }
    SchedulerTargetAlignment = if ($analysis) { $analysis.scheduler_target_alignment } else { $null }
    Url = $Url
    PackageName = $PackageName
    RequestedSerial = if ($selection) { $selection.Serial } else { $Serial }
    SelectedSerial = $selectedSerial
    StartedAt = $startedAt.ToString('o')
    FinishedAt = $finishedAt.ToString('o')
    DurationSeconds = [Math]::Round(($finishedAt - $startedAt).TotalSeconds, 3)
    Failure = $failure
    TracePath = $localTracePath
    AnalysisPath = $localAnalysisPath
    BaselineReportPath = $BaselineReportPath
    Fullscreen = $fullscreenState
    TargetVideoFps = $TargetVideoFps
    TargetVideoFpsTolerance = $TargetVideoFpsTolerance
    ObservedVideoFps = $observedVideoFps
    TargetVideoModeReady = $modeReady
    FixedAnalysisWindow = if ($analysis) { $analysis.fixed_analysis_window } else { $null }
    TelemetryCapture = $telemetryCapture
    NativeTelemetryCapture = $nativeTelemetryCapture
    RuntimeBefore = if ($runtimeBefore) { Get-RuntimeStateReport -RuntimeState $runtimeBefore } else { $null }
    RuntimeAfterLaunch = if ($runtimeAfterLaunch) { Get-RuntimeStateReport -RuntimeState $runtimeAfterLaunch } else { $null }
    RuntimeAtMeasurement = if ($runtimeAtMeasurement) { Get-RuntimeStateReport -RuntimeState $runtimeAtMeasurement } else { $null }
    RuntimeAfter = if ($runtimeAfter) { Get-RuntimeStateReport -RuntimeState $runtimeAfter } else { $null }
    PreferenceGate = $preferenceGate
    HookGate = $hookGate
    RequestedRenderer = $RendererMode
    RenderCadenceProbe = $RenderCadenceProbe
    Media3FrameSyncEnabled = $Media3FrameSyncEnabled
    BlockIjkFrameDrop = $BlockIjkFrameDrop
    BlockComputeTargetDelay = $BlockComputeTargetDelay
    ProfileApplyOutput = $profileApplyOutput
    RendererGate = $rendererGate
    ActualRenderer = if ($rendererGate) { $rendererGate.ActualRenderer } else { $null }
    AnalysisSummary = if ($analysis) { Get-AnalysisReportSummary -Analysis $analysis } else { $null }
    Comparison = $comparison
    ConnectionAttempts = if ($selection) { @($selection.Attempts) } else { @() }
    Events = @($events.ToArray())
}
$runReport | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $localReportPath -Encoding utf8

Write-Host "Result: $result"
Write-Host "Selected ADB: $selectedSerial"
Write-Host "Trace: $localTracePath"
Write-Host "Analysis: $localAnalysisPath"
Write-Host "Run report: $localReportPath"
if ($null -ne $comparison) {
    Write-Host "Comparison: improved=$($comparison.Improved), resolved=$($comparison.Resolved)"
}
if ($result -ne 'PASS') {
    exit 1
}
exit 0
