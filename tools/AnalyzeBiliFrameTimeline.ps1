[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TracePath,

    [string]$ReportPath = '',

    [string]$PackageName = 'tv.danmaku.bili',

    [ValidateRange(0.1, 10)]
    [double]$GapThresholdPeriods = 1.5,

    [ValidateRange(0, 100)]
    [double]$DisplayDeadlineThresholdMs = 2.42,

    [ValidateRange(1, 240)]
    [double]$NominalSourceFps = 120,

    [string]$TelemetryCsvPath = '',

    [string]$NativeTelemetryCsvPath = '',

    [string]$PythonPath = 'python'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $TracePath -PathType Leaf)) {
    throw "Perfetto trace not found: $TracePath"
}

$resolvedTracePath = (Resolve-Path -LiteralPath $TracePath).Path
$analysisScriptPath = Join-Path $PSScriptRoot 'FrameTimelineAnalysis.py'
if (-not (Test-Path -LiteralPath $analysisScriptPath -PathType Leaf)) {
    throw "Frame timeline analysis module not found: $analysisScriptPath"
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $traceDirectory = [System.IO.Path]::GetDirectoryName($resolvedTracePath)
    $traceBaseName = [System.IO.Path]::GetFileNameWithoutExtension($resolvedTracePath)
    $ReportPath = Join-Path $traceDirectory "$traceBaseName.analysis.json"
}

$reportDirectory = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($ReportPath))
if (-not [string]::IsNullOrWhiteSpace($reportDirectory) -and
    -not (Test-Path -LiteralPath $reportDirectory -PathType Container)) {
    [System.IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
}

$pythonArguments = @(
    $analysisScriptPath,
    $resolvedTracePath,
    $ReportPath,
    $PackageName,
    '--gap-threshold-periods', [string]$GapThresholdPeriods,
    '--display-deadline-threshold-ms', [string]$DisplayDeadlineThresholdMs,
    '--nominal-source-fps', [string]$NominalSourceFps
)
if (-not [string]::IsNullOrWhiteSpace($TelemetryCsvPath)) {
    if (-not (Test-Path -LiteralPath $TelemetryCsvPath -PathType Leaf)) {
        throw "Media3 telemetry CSV not found: $TelemetryCsvPath"
    }
    $pythonArguments += @('--telemetry-csv', (Resolve-Path -LiteralPath $TelemetryCsvPath).Path)
}
if (-not [string]::IsNullOrWhiteSpace($NativeTelemetryCsvPath)) {
    if (-not (Test-Path -LiteralPath $NativeTelemetryCsvPath -PathType Leaf)) {
        throw "Native IJK telemetry CSV not found: $NativeTelemetryCsvPath"
    }
    $pythonArguments += @('--native-telemetry-csv', (Resolve-Path -LiteralPath $NativeTelemetryCsvPath).Path)
}

& $PythonPath @pythonArguments
$exitCode = $LASTEXITCODE
if ($exitCode -ne 0 -and $exitCode -ne 2) {
    throw "Frame timeline analysis failed with exit code ${exitCode}"
}

if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
    throw "Frame timeline analyzer did not create report: $ReportPath"
}

$report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$expectedDisplayCount = [int]$report.display_frame_timing_summary.paired_count + [int]$report.display_frame_timing_summary.unmatched_expected_count
$actualDisplayCount = [int]$report.display_frame_timing_summary.paired_count + [int]$report.display_frame_timing_summary.unmatched_actual_count
Write-Host "Sensor status: $($report.sensor_quality.status)"
Write-Host "Scheduler baseline status: $($report.sensor_quality.scheduler_conclusion_status)"
Write-Host "Video layer: $($report.measurement.video_layer_selection.selected.layer_name)"
Write-Host "Display expected / actual / paired / video PresentFence: $expectedDisplayCount/$actualDisplayCount/$($report.display_frame_timing_summary.paired_count)/$($report.video_present_summary.present_count)"
Write-Host "Video intervals > nominal / > gap threshold / >100ms: $($report.video_present_summary.interval_gt_nominal_source_period_count)/$($report.video_present_summary.interval_gt_gap_threshold_count)/$($report.video_present_summary.interval_gt_100ms_count)"
Write-Host "Video cadence median FPS: $($report.video_present_summary.median_cadence_fps)"
Write-Host "Video 1% low FPS, all / <=100ms: $($report.video_present_summary.one_percent_low_fps_all_intervals) / $($report.video_present_summary.one_percent_low_fps_excluding_over_100ms)"
Write-Host "Fixed 30s window status / presents / small gaps / 1% low: $($report.fixed_analysis_window.status) / $($report.fixed_analysis_window.present_count) / $($report.fixed_analysis_window.video_present_summary.small_cadence_gap_count) / $($report.fixed_analysis_window.video_present_summary.one_percent_low_fps_excluding_over_100ms)"
Write-Host "Source mode / cadence / scheduler target alignment: $($report.status_summary.source_mode_status) / $($report.status_summary.cadence_observed) / $($report.status_summary.scheduler_target_alignment)"
Write-Host "Native IJK handoff / queue / display temporal matches: $($report.status_summary.native_handoff_status) / $($report.native_handoff_alignment.queue_temporal_match_count) / $($report.native_handoff_alignment.display_temporal_match_count)"
Write-Host "Native scheduler phase / conclusion / missing-present: $($report.status_summary.native_scheduler_phase_status) / $($report.status_summary.native_scheduler_phase_conclusion) / $($report.native_scheduler_phase_analysis.missing_present_count)"
Write-Host "Native vp_duration phase / conclusion / source rows: $($report.status_summary.native_vp_duration_phase_status) / $($report.status_summary.native_vp_duration_phase_conclusion) / $($report.native_vp_duration_phase_analysis.vp_duration_source_count)"
Write-Host "SF Queue phase / conclusion / primary stream: $($report.status_summary.surface_flinger_queue_phase_status) / $($report.status_summary.surface_flinger_queue_phase_conclusion) / $($report.surface_flinger_queue_phase_analysis.producer_stream)"
Write-Host "Scheduler/native PTS exact queue / display: $($report.scheduler_native_alignment.status) / $($report.scheduler_native_alignment.queue.exact_pts_count) / $($report.scheduler_native_alignment.display.exact_pts_count)"
Write-Host "Scheduler/render-callback PTS exact / callback count: $($report.scheduler_render_alignment.status) / $($report.scheduler_render_alignment.exact_pts_count) / $($report.scheduler_render_alignment.render_callback_count)"
Write-Host "SurfaceFlinger latch gap classification / unsignaled-fence / commit-cadence: $($report.surface_flinger_latch_analysis.status) / $($report.surface_flinger_latch_analysis.gap_with_all_skipped_commits_unsignaled_count) / $($report.surface_flinger_latch_analysis.gap_with_surfaceflinger_commit_cadence_gap_count)"
Write-Host "SurfaceFlinger queued non-latched / 2-slot candidates / burst-linked: $($report.surface_flinger_buffer_replacement_analysis.queued_non_latched_count) / $($report.surface_flinger_buffer_replacement_analysis.two_slot_gap_candidate_count) / $($report.surface_flinger_buffer_replacement_analysis.submission_burst_linked_count)"
Write-Host "Renderer doFrame cadence / video gaps matched / renderer-gap matches: $($report.renderer_cadence_analysis.do_frame_count) / $($report.renderer_cadence_analysis.video_gaps_temporally_matched_to_renderer) / $($report.renderer_cadence_analysis.video_gaps_with_renderer_do_frame_gap)"
Write-Host "Display callback / renderer gaps with callback / without callback: $($report.renderer_cadence_analysis.display_callback_count) / $($report.renderer_cadence_analysis.renderer_gaps_with_display_callback) / $($report.renderer_cadence_analysis.renderer_gaps_without_display_callback)"
Write-Host "Native display / original call / Binder 0x0b: $($report.native_display_trace_analysis.native_display_count) / $($report.native_display_trace_analysis.native_display_call_count) / $($report.native_display_trace_analysis.binder_display_callback_count)"
Write-Host "Native call gaps crossing expected / Binder-before-callback-after gaps: $($report.native_display_trace_analysis.dispatch_gaps_native_call_crossing_expected) / $($report.native_display_trace_analysis.dispatch_gaps_ordered_binder_before_callback_after)"
Write-Host "Binder-to-callback transport within dispatch gaps: $($report.native_display_trace_analysis.ordered_binder_callback_transport | ConvertTo-Json -Compress)"
Write-Host "Renderer onVsync gaps / with app onVsync / with renderer schedule request: $($report.renderer_cadence_analysis.renderer_on_vsync_gap_count) / $($report.renderer_cadence_analysis.renderer_on_vsync_gaps_with_app_vsync) / $($report.renderer_cadence_analysis.renderer_on_vsync_gaps_with_schedule)"
Write-Host "Renderer schedule phase vs app onVsync (before / at-or-after): $($report.renderer_cadence_analysis.renderer_on_vsync_gaps_schedule_before_app_on_vsync) / $($report.renderer_cadence_analysis.renderer_on_vsync_gaps_schedule_at_or_after_app_on_vsync)"
Write-Host "Frame receiver onVsync / run: $($report.renderer_cadence_analysis.receiver_on_vsync_count) / $($report.renderer_cadence_analysis.receiver_run_count)"
Write-Host "Frame receiver dispatchVsync / run: $($report.renderer_cadence_analysis.receiver_dispatch_vsync_count) / $($report.renderer_cadence_analysis.receiver_run_count)"
Write-Host "Frame receiver dispatch threads: $($report.renderer_cadence_analysis.receiver_dispatch_vsync_thread_counts | ConvertTo-Json -Compress)"
Write-Host "Renderer dispatch gaps / with app VSync / schedule after expected VSync: $($report.renderer_cadence_analysis.receiver_dispatch_analysis.dispatch_gap_count) / $($report.renderer_cadence_analysis.receiver_dispatch_analysis.dispatch_gaps_with_app_vsync) / $($report.renderer_cadence_analysis.receiver_dispatch_analysis.dispatch_gaps_with_schedule_after_expected_vsync)"
Write-Host "Renderer dispatch gap phase classes: $($report.renderer_cadence_analysis.receiver_dispatch_analysis.dispatch_gap_classification_counts | ConvertTo-Json -Compress)"
Write-Host "Frame receiver thread counts (onVsync): $($report.renderer_cadence_analysis.receiver_on_vsync_thread_counts | ConvertTo-Json -Compress)"
Write-Host "Frame receiver thread counts (run): $($report.renderer_cadence_analysis.receiver_run_thread_counts | ConvertTo-Json -Compress)"
Write-Host "Analysis report: $ReportPath"

if ($exitCode -eq 2) {
    exit 2
}
exit 0
