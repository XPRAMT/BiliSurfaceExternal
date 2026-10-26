#!/usr/bin/env python3
"""Plot one SurfaceFlinger gap and the surrounding 50 presented frames."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any


def ns(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def in_window(timestamp: int | None, start: int, end: int) -> bool:
    return timestamp is not None and start <= timestamp <= end


NATIVE_EVENT_NAMES = {
    "ijk_queue_picture": "IJK queue_picture：送入畫面佇列",
    "ijk_display_picture": "IJK display：進入顯示回呼",
    "mediacodec_release": "MediaCodec 釋放輸出幀",
    "frame_queue_wait": "frame_queue_wait_timeout 等待",
    "sdl_cond_wait": "SDL_CondWaitTimeout 等待",
    "vp_duration": "vp_duration 幀間隔",
    "vp_duration_context": "vp_duration 上下文",
    "compute_target": "compute_target_delay 計算",
    "display_interval": "顯示回呼間隔",
    "display_call": "顯示回呼執行時間",
    "ijk_early_us_ns": "IJK earlyUs 採樣",
    "master_clock": "IJK master clock 採樣",
    "sdl_delay": "SDL_Delay 等待",
}

NATIVE_EVENT_SOURCES = {
    "ijk_queue_picture": (
        "native_refresh_cap.cpp：observedQueuePicture() "
        "→ recordRawSample(kQueuePicture)"
    ),
    "ijk_display_picture": (
        "native_refresh_cap.cpp：observedVoutDisplayYuvOverlay() "
        "→ recordRawSample(kDisplayPicture)"
    ),
    "mediacodec_release": (
        "native_refresh_cap.cpp：observedMediaCodecReleaseOutputBuffer() "
        "→ recordRawSample(kMediaCodecRelease)"
    ),
    "frame_queue_wait": (
        "native_refresh_cap.cpp：observedFrameQueueWaitTimeout() "
        "→ recordRawSample(kFrameQueueWait)"
    ),
    "sdl_cond_wait": (
        "native_refresh_cap.cpp：observedSdlCondWaitTimeout() "
        "→ recordRawSample(kSdlCondWait)"
    ),
    "vp_duration": (
        "native_refresh_cap.cpp：observedVpDuration() "
        "→ recordRawSample(kVpDuration)"
    ),
    "vp_duration_context": (
        "native_refresh_cap.cpp：observedVpDuration() "
        "→ recordRawSample(kVpDurationContext)"
    ),
    "compute_target": (
        "native_refresh_cap.cpp：observedComputeTargetDelay() "
        "→ recordRawSample(kComputeTarget)"
    ),
    "display_interval": (
        "native_refresh_cap.cpp：observedVoutDisplayYuvOverlay() "
        "→ recordRawSample(kDisplayInterval)"
    ),
    "display_call": (
        "native_refresh_cap.cpp：observedVoutDisplayYuvOverlay() "
        "→ recordRawSample(kDisplayCall)"
    ),
    "ijk_early_us_ns": (
        "native_refresh_cap.cpp：recordVideoDropTiming() "
        "→ recordRawSample(kIjkEarlyUs)"
    ),
    "master_clock": (
        "native_refresh_cap.cpp：observedComputeTargetDelay() "
        "→ recordRawSample(kMasterClock)"
    ),
    "sdl_delay": (
        "native_refresh_cap.cpp：observedSdlDelay() "
        "→ recordRawSample(kSdlDelay)"
    ),
}

STAGE_SOURCES = {
    "Dequeue": "Perfetto FrameTimeline：selected video layer 的 Dequeue 階段（非本專案函式）",
    "Queue": "Perfetto FrameTimeline：selected video layer 的 Queue 階段（非本專案函式）",
    "AcquireFenceSignaled": "Perfetto FrameTimeline：AcquireFenceSignaled 階段（非本專案函式）",
    "Latch": "Perfetto FrameTimeline：selected video layer 的 Latch 階段（非本專案函式）",
    "PresentFenceSignaled": (
        "Perfetto FrameTimeline／HWC：PresentFenceSignaled 最終呈現代理事件；"
        "不是面板掃描線時間"
    ),
}


def add_event(events: list[dict[str, Any]], timestamp: int, lane: str, kind: str,
              label: str, frame_number: int | None = None,
              frame_index: int | None = None, pts_us: int | None = None,
              absolute_ns: int | None = None, source: str = "") -> None:
    events.append({
        "ts": timestamp,
        "lane": lane,
        "kind": kind,
        "label": label,
        "frame": frame_number,
        "frameIndex": frame_index,
        "ptsUs": pts_us,
        "absoluteNs": absolute_ns if absolute_ns is not None else timestamp,
        "source": source,
    })


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis", required=True, type=Path)
    parser.add_argument("--media3", required=True, type=Path)
    parser.add_argument("--native", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--before", type=int, default=50)
    parser.add_argument("--after", type=int, default=50)
    return parser.parse_args()


def load_analysis(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def select_gap(analysis: dict[str, Any], before: int, after: int) -> tuple[dict[str, Any], list[dict[str, Any]], int, int]:
    presents = analysis["video_present_timing"]
    selected_index = None
    for index in range(before, len(presents) - after):
        row = presents[index]
        interval = row.get("interval", {})
        if (interval.get("classification") == "whole_slot_or_cadence_gap"
                and not interval.get("is_large_gap", False)):
            selected_index = index
            break
    if selected_index is None:
        raise RuntimeError("no non-large SurfaceFlinger gap was found")

    start_index = max(0, selected_index - before)
    end_index = min(len(presents) - 1, selected_index + after)
    selected = presents[selected_index]
    start_ts = ns(presents[start_index]["present_ts_ns"])
    end_ts = ns(presents[end_index]["present_ts_ns"])
    if start_ts is None or end_ts is None:
        raise RuntimeError("selected window has invalid PresentFence timestamps")
    return selected, presents[start_index:end_index + 1], start_ts, end_ts


def collect_events(analysis: dict[str, Any], media3_path: Path, native_path: Path,
                   start_ts: int, end_ts: int, clock_offset_ns: int) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []

    # Presenting frames and their available SurfaceFlinger producer stages.
    for row in analysis["video_present_timing"]:
        present_ts = ns(row.get("present_ts_ns"))
        if not in_window(present_ts, start_ts, end_ts):
            continue
        frame_number = ns(row.get("frame_number"))
        codec_frame = ns(row.get("codec_frame_index"))
        pts_us = ns(row.get("codec_pts_us"))
        stage_ts = row.get("stage_join", {}).get("stage_ts_ns", {})
        for stage in ("Dequeue", "Queue", "AcquireFenceSignaled", "Latch", "PresentFenceSignaled"):
            timestamp = ns(stage_ts.get(stage))
            if timestamp is not None and in_window(timestamp, start_ts, end_ts):
                add_event(events, timestamp, "SurfaceFlinger stages", stage,
                          f"{stage} · SF frame {frame_number}", frame_number,
                          codec_frame, pts_us, source=STAGE_SOURCES[stage])

    scheduler = analysis.get("surface_flinger_scheduler_events", {})
    for item in scheduler.get("commit_cycles", []):
        timestamp = ns(item.get("ts_ns"))
        if timestamp is not None and in_window(timestamp, start_ts, end_ts):
            add_event(events, timestamp, "SF scheduler", "commit",
                      item.get("name", "commit"), ns(item.get("vsync_id")),
                      source="Perfetto slice：SurfaceFlinger 的 commit 排程事件")
    for item in scheduler.get("begin_frame_cycles", []):
        timestamp = ns(item.get("ts_ns"))
        if timestamp is not None and in_window(timestamp, start_ts, end_ts):
            add_event(events, timestamp, "SF scheduler", "beginFrame",
                      item.get("name", "beginFrame"), ns(item.get("vsync_id")),
                      source="Perfetto slice：SurfaceFlinger 的 beginFrame 排程事件")
    for timestamp in scheduler.get("predictor_frame_begin_ts_ns", []):
        timestamp = ns(timestamp)
        if timestamp is not None and in_window(timestamp, start_ts, end_ts):
            add_event(
                events,
                timestamp,
                "VSync predictor",
                "frameBegin",
                "VSyncPredictor::onFrameBegin · SurfaceFlinger",
                source=(
                    "Perfetto slice：VSyncPredictor::onFrameBegin "
                    "（SurfaceFlinger，非本專案函式；不是硬體 VSync 訊號）"
                ),
            )
    for key, label in (("set_buffer_events", "setBuffer"), ("latch_buffer_events", "latchBuffer"),
                       ("should_latch_unsignaled_events", "shouldLatchUnsignaled"),
                       ("unsignaled_fence_events", "fence unsignaled")):
        for item in scheduler.get(key, []):
            timestamp = ns(item.get("ts_ns"))
            if timestamp is not None and in_window(timestamp, start_ts, end_ts):
                frame_number = ns(item.get("frame_number"))
                add_event(events, timestamp, "SF scheduler", label,
                          item.get("name", label), frame_number,
                          source=f"Perfetto slice：SurfaceFlinger 的 {label} 事件")

    # Media3 timestamps are CLOCK_MONOTONIC; convert to the Perfetto BOOTTIME axis.
    with media3_path.open(newline="", encoding="utf-8", errors="replace") as handle:
        for row in csv.DictReader(handle):
            raw_timestamp = ns(row.get("eventNs"))
            timestamp = None if raw_timestamp is None else raw_timestamp + clock_offset_ns
            if not in_window(timestamp, start_ts, end_ts):
                continue
            event = row.get("event", "")
            action = row.get("action", "")
            if event == "FRAME" and action == "SCHEDULED_TIMED_RELEASE":
                lane, kind = "Media3 release", "timed release"
                source = (
                    "Media3FrameSynchronizer.releaseAtAdjustedTime() "
                    "→ recordFrameEvent()"
                )
            elif event == "FRAME" and action == "FRAME_RENDERED":
                lane, kind = "Media3 release", "render callback"
                source = (
                    "Media3FrameSynchronizer.createRenderListener().onFrameRendered() "
                    "→ recordFrameRendered() → recordFrameEvent()"
                )
            elif event == "FRAME" and action == "DEQUEUED":
                lane, kind = "Media3 release", "dequeue"
                source = "Media3FrameSynchronizer.onOutputDequeued() → recordFrameEvent()"
            elif event == "WINDOW_FRAME_METRICS":
                lane, kind = "Window metrics", "frame metrics"
                source = (
                    "PlaybackDebugOverlay.onFrameMetricsAvailable() "
                    "→ Media3FrameSynchronizer.recordWindowFrameMetrics()"
                )
            else:
                lane, kind = "Media3 other", event or action or "event"
                if event == "FRAME" and action == "PASS_THROUGH":
                    source = (
                        "Media3FrameSynchronizer.recordReleaseRequest() "
                        "→ recordFrameEvent()"
                    )
                elif event == "FRAME" and action == "DIRECT_TIMED_RELEASE":
                    source = (
                        "Media3FrameSynchronizer.recordDirectTimedRelease() "
                        "→ recordFrameEvent()"
                    )
                elif event == "CODEC_LIFECYCLE":
                    source = "Media3FrameSynchronizer.recordCodecLifecycle() → eventLine()"
                else:
                    source = (
                        f"Media3 遙測 API：{row.get('api') or '未提供'} "
                        "→ Media3FrameSynchronizer.eventLine()"
                    )
            buffer_index = row.get("bufferIndex", "")
            label = f"{event}/{action} · buffer {buffer_index} · {source.split(' → ')[0]}".strip(" ·")
            add_event(events, timestamp, lane, kind, label,
                      frame_index=ns(row.get("frameIndex")), pts_us=ns(row.get("ptsUs")),
                      absolute_ns=timestamp, source=source)

    # Native timestamps are CLOCK_MONOTONIC and use the same conversion.
    with native_path.open(newline="", encoding="utf-8", errors="replace") as handle:
        for row in csv.DictReader(line for line in handle if not line.startswith("#")):
            raw_timestamp = ns(row.get("timestamp_ns"))
            timestamp = None if raw_timestamp is None else raw_timestamp + clock_offset_ns
            if not in_window(timestamp, start_ts, end_ts):
                continue
            event = row.get("event", "")
            if event in {"ijk_queue_picture", "ijk_display_picture"}:
                lane = "Native IJK"
            elif event == "mediacodec_release":
                lane = "Native release"
            elif event in {"frame_queue_wait", "sdl_cond_wait"}:
                lane = "Native waits"
            else:
                lane = "Native other"
            value = row.get("value_ns", "")
            auxiliary = row.get("auxiliary_ns", "")
            event_name = NATIVE_EVENT_NAMES.get(event, event)
            source = NATIVE_EVENT_SOURCES.get(
                event,
                "native_refresh_cap.cpp：recordRawSample()（事件來源函式未映射）",
            )
            add_event(
                events,
                timestamp,
                lane,
                event,
                f"{event_name} [{event}] · value {value} · aux {auxiliary}",
                absolute_ns=timestamp,
                source=source,
            )

    events.sort(key=lambda item: (item["ts"], item["lane"], item["kind"]))
    return events


def render_html(metadata: dict[str, Any], events: list[dict[str, Any]], output: Path) -> None:
    data = json.dumps({"metadata": metadata, "events": events}, ensure_ascii=False,
                      separators=(",", ":")).replace("<", "\\u003c")
    html = f'''<!doctype html>
<html lang="zh-Hant">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Frame event window timeline</title>
</head>
<body>
<div id="frame-event-window" class="frame-event-window">
  <style>
    :root {{
      --background: #0b1120;
      --foreground: #e2e8f0;
      --border: #334155;
      --destructive: #fb7185;
      --popover: #111827;
      --popover-foreground: #f8fafc;
      --viz-series-1: #7dd3fc;
      --viz-series-2: #86efac;
      --viz-series-3: #fde68a;
      --viz-series-4: #f9a8d4;
      --viz-series-5: #c4b5fd;
      --viz-series-6: #67e8f9;
      --accent: #f0abfc;
      --muted-foreground: #94a3b8;
      --primary: #93c5fd;
    }}
    html, body {{ min-height: 100%; }}
    body {{ margin: 0; padding: 16px; background: var(--background); color: var(--foreground); }}
    #frame-event-window {{
      font-family: system-ui, sans-serif;
      color: var(--foreground);
    }}
    #frame-event-window .summary {{ font-size: 13px; margin-bottom: 10px; }}
    #frame-event-window .summary strong {{ font-variant-numeric: tabular-nums; }}
    #frame-event-window .flow-guide {{ display: grid; gap: 4px; margin: 0 0 12px; color: var(--muted-foreground); font-size: 12px; line-height: 1.45; }}
    #frame-event-window .flow-guide strong {{ color: var(--foreground); font-weight: 500; }}
    #frame-event-window .flow-guide span {{ margin-left: 4px; }}
    #frame-event-window .viz-controls {{ display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }}
    #frame-event-window .viz-controls label {{ display: inline-flex; align-items: center; gap: 8px; }}
    #frame-event-window .viz-controls input {{ min-width: 180px; accent-color: var(--primary); }}
    #frame-event-window .timeline-scroll {{ width: 100%; overflow-x: auto; overflow-y: hidden;
      background: #0f172a; scrollbar-color: var(--border) var(--background); }}
    #frame-event-window svg {{ display: block; height: auto; max-width: none; overflow: visible; }}
    #frame-event-window .axis {{ stroke: #64748b; stroke-width: 1; }}
    #frame-event-window .grid {{ stroke: #263449; stroke-width: 1; }}
    #frame-event-window .group-separator {{ stroke: #475569; stroke-width: 1.25; }}
    #frame-event-window .lane-label, #frame-event-window .tick-label,
    #frame-event-window .frame-label {{ fill: var(--foreground); font-size: 11px; }}
    #frame-event-window .group-label {{ fill: var(--accent); font-size: 11px; font-weight: 500; }}
    #frame-event-window .tick-label, #frame-event-window .frame-label {{ font-variant-numeric: tabular-nums; }}
    #frame-event-window .gap-band {{ fill: var(--destructive); opacity: .12; }}
    #frame-event-window .gap-line {{ stroke: var(--destructive); stroke-width: 1.5; stroke-dasharray: 4 3; }}
    #frame-event-window .event {{ stroke: var(--background); stroke-width: .7; }}
    #frame-event-window .tooltip {{ position: fixed; display: none; z-index: 2; max-width: 420px;
      padding: 8px; background: var(--popover); color: var(--popover-foreground);
      border: 1px solid var(--border); font-size: 12px; pointer-events: none; }}
    #frame-event-window .tooltip-source {{ color: var(--muted-foreground); }}
  </style>
  <div class="summary" aria-live="polite"></div>
  <div class="flow-guide" aria-label="時間軸分組說明">
    <div><strong>1. 上游提交與播放器時序</strong><span>Media3、IJK 與原生等待／釋放事件；表示畫面送往 SurfaceFlinger 前的活動。</span></div>
    <div><strong>2. SurfaceFlinger 排程</strong><span>VSync 預測與排程週期；表示系統何時準備接收或處理畫面。</span></div>
    <div><strong>3. SurfaceFlinger 最終呈現</strong><span>Dequeue → Queue → AcquireFence → Latch → PresentFence；這是圖中最接近最終畫面的階段鏈。</span></div>
    <div><strong>4. 旁路觀測</strong><span>視窗幀指標與未歸入主流程的事件，只作輔助對照。</span></div>
    <div>上下位置是分組，不代表固定先後；實際先後請沿橫軸由左至右閱讀。</div>
    <div>滑過事件可查看「記錄來源」；本專案函式以 <code>函式名()</code> 顯示，Perfetto 事件會標示為「追蹤事件」。</div>
  </div>
  <div class="viz-controls">
    <label for="timeline-zoom">時間軸縮放
      <input id="timeline-zoom" type="range" min="1" max="12" step="0.25" value="1">
      <output id="timeline-zoom-value" for="timeline-zoom">1×</output>
    </label>
  </div>
  <div class="timeline-scroll">
    <svg role="img" aria-label="SurfaceFlinger 漏幀事件前後 50 幀的完整事件時間軸"></svg>
  </div>
  <div class="tooltip" role="tooltip"></div>
  <script>
  (() => {{
    const payload = {data};
    const root = document.getElementById('frame-event-window');
    const svg = root.querySelector('svg');
    const summary = root.querySelector('.summary');
    const tooltip = root.querySelector('.tooltip');
    const zoomInput = root.querySelector('#timeline-zoom');
    const zoomValue = root.querySelector('#timeline-zoom-value');
    const ns = payload.metadata;
    const groups = [
      {{
        title: '1. 上游提交與播放器時序',
        lanes: ['Media3 release', 'Native release', 'Native IJK', 'Native waits', 'Native other'],
      }},
      {{
        title: '2. SurfaceFlinger 排程',
        lanes: ['VSync predictor', 'SF scheduler'],
      }},
      {{
        title: '3. SurfaceFlinger 最終呈現',
        lanes: ['Dequeue', 'Queue', 'AcquireFenceSignaled', 'Latch', 'PresentFenceSignaled', 'SurfaceFlinger other'],
      }},
      {{
        title: '4. 旁路觀測',
        lanes: ['Window metrics', 'Media3 other'],
      }},
    ];
    const laneLabels = new Map([
      ['Media3 release', 'Media3 釋放'],
      ['Native release', '原生釋放'],
      ['Native IJK', 'IJK 播放器'],
      ['Native waits', '原生等待'],
      ['Native other', '原生其他'],
        ['VSync predictor', 'SF VSyncPredictor::onFrameBegin'],
        ['SF scheduler', 'SF beginFrame / commit / setBuffer'],
        ['Dequeue', 'SF Dequeue（FrameTimeline）'],
        ['Queue', 'SF Queue（FrameTimeline）'],
        ['AcquireFenceSignaled', 'SF AcquireFenceSignaled'],
        ['Latch', 'SF Latch（FrameTimeline）'],
        ['PresentFenceSignaled', 'SF PresentFenceSignaled'],
        ['SurfaceFlinger other', 'SF 其他追蹤事件'],
        ['Window metrics', '視窗幀指標（FrameMetrics）'],
        ['Media3 other', 'Media3 其他遙測'],
    ]);
    const laneColors = new Map([
      ['Media3 release', 'var(--viz-series-4)'],
      ['Native release', 'var(--viz-series-5)'],
      ['Native IJK', 'var(--viz-series-6)'],
      ['Native waits', 'var(--accent)'],
      ['Native other', 'var(--muted-foreground)'],
      ['VSync predictor', 'var(--viz-series-1)'],
      ['SF scheduler', 'var(--viz-series-2)'],
      ['Dequeue', 'var(--viz-series-1)'],
      ['Queue', 'var(--viz-series-2)'],
      ['AcquireFenceSignaled', 'var(--viz-series-3)'],
      ['Latch', 'var(--accent)'],
      ['PresentFenceSignaled', 'var(--destructive)'],
      ['SurfaceFlinger other', 'var(--muted-foreground)'],
      ['Window metrics', 'var(--primary)'],
      ['Media3 other', 'var(--muted-foreground)'],
    ]);
    const stageLaneByKind = new Map([
      ['Dequeue', 'Dequeue'],
      ['Queue', 'Queue'],
      ['AcquireFenceSignaled', 'AcquireFenceSignaled'],
      ['Latch', 'Latch'],
      ['PresentFenceSignaled', 'PresentFenceSignaled'],
    ]);
    const lanePosition = new Map();
    const groupLayouts = [];
    const groupHeaderHeight = 24;
    const groupGap = 8;
    let laneCursor = 0;
    groups.forEach((group) => {{
      groupLayouts.push({{ ...group, laneStart: laneCursor }});
      laneCursor += groupHeaderHeight;
      group.lanes.forEach((lane) => {{
        lanePosition.set(lane, laneCursor);
        laneCursor += 30;
      }});
      laneCursor += groupGap;
    }});
    const laneName = (event) => event.lane === 'SurfaceFlinger stages'
      ? (stageLaneByKind.get(event.kind) || 'SurfaceFlinger other')
      : event.lane;
    const laneRows = groups.flatMap((group) => group.lanes);
    const start = ns.startTsNs, end = ns.endTsNs, span = end - start;
    const esc = (value) => String(value ?? '').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');
    const frameRows = ns.frameRows;
    summary.innerHTML = `漏幀事件：SurfaceFlinger frame <strong>${{ns.previousFrameNumber}}</strong> → <strong>${{ns.frameNumber}}</strong>，`+
      `Present 間隔 <strong>${{ns.intervalMs.toFixed(6)}} ms</strong>，缺少 frame <strong>${{ns.missingFrameNumbers.join(', ')}}</strong>；`+
      `時間軸包含前後各 ${{ns.before}} / ${{ns.after}} 個呈現幀，事件共 <strong>${{payload.events.length}}</strong> 筆。`;
    const render = () => {{
      const zoom = Number(zoomInput.value);
      const baseWidth = 1180;
      const width = baseWidth * zoom;
      const left = 292, right = 24, top = 54, laneHeight = 30, axisHeight = 42;
      const plotWidth = width - left - right;
      const x = (timestamp) => left + ((timestamp - start) / span) * plotWidth;
      const gapStart = x(ns.previousPresentTsNs), gapEnd = x(ns.presentTsNs);
      const contentBottom = top + laneCursor;
      const height = contentBottom + axisHeight + 22;
      const line = (x1,y1,x2,y2,cls='axis') => `<line class="${{cls}}" x1="${{x1}}" y1="${{y1}}" x2="${{x2}}" y2="${{y2}}"/>`;
      let parts = [];
      parts.push(`<rect class="gap-band" x="${{gapStart}}" y="${{top-18}}" width="${{Math.max(1,gapEnd-gapStart)}}" height="${{height-top+2}}"/>`);
      parts.push(line(gapStart, top-18, gapStart, height-20, 'gap-line'), line(gapEnd, top-18, gapEnd, height-20, 'gap-line'));
      groupLayouts.forEach((group) => {{
        const groupTop = top + group.laneStart;
        parts.push(`<text class="group-label" x="8" y="${{groupTop+15}}">${{esc(group.title)}}</text>`);
        parts.push(line(left, groupTop + groupHeaderHeight - 4, width-right,
                        groupTop + groupHeaderHeight - 4, 'group-separator'));
      }});
      laneRows.forEach((lane) => {{
        const y = top + lanePosition.get(lane) + laneHeight / 2;
        parts.push(line(left, y, width-right, y, 'grid'));
        parts.push(`<text class="lane-label" x="${{left-8}}" y="${{y+4}}" text-anchor="end">${{esc(laneLabels.get(lane) || lane)}}</text>`);
      }});
      frameRows.forEach((frame, i) => {{
        const px = x(frame.presentTsNs);
        parts.push(line(px, top-18, px, height-20, 'grid'));
        if (i % Math.max(1, Math.floor(frameRows.length / 12)) === 0 || frame.frameNumber === ns.frameNumber) {{
          parts.push(`<text class="frame-label" x="${{px}}" y="${{height-6}}" text-anchor="middle">${{frame.frameNumber}}</text>`);
        }}
      }});
      const tickCount = 10;
      for (let i=0; i<=tickCount; i++) {{
        const timestamp = start + span * i / tickCount;
        const px = x(timestamp);
        parts.push(line(px, height-axisHeight, px, height-20, 'axis'));
        parts.push(`<text class="tick-label" x="${{px}}" y="${{height-20}}" text-anchor="middle">${{((timestamp-start)/1e6).toFixed(3)}} ms</text>`);
      }}
      payload.events.forEach((event, index) => {{
        const resolvedLane = laneName(event);
        const lane = lanePosition.has(resolvedLane) ? resolvedLane : 'Native other';
        const px = x(event.ts), py = top + lanePosition.get(lane) + laneHeight / 2;
        const color = laneColors.get(lane) || 'var(--muted-foreground)';
        const radius = event.kind === 'timed release' || event.kind === 'PresentFenceSignaled' ? 4 : 2.7;
        parts.push(`<circle class="event" data-index="${{index}}" cx="${{px.toFixed(2)}}" cy="${{py}}" r="${{radius}}" fill="${{color}}"/>`);
      }});
      parts.push(`<text class="tick-label" x="${{left}}" y="18">相對時間（ms，漏幀前一幀 Present = ${{((ns.previousPresentTsNs-start)/1e6).toFixed(3)}}）</text>`);
      svg.setAttribute('viewBox', `0 0 ${{width}} ${{height}}`);
      svg.style.width = `${{width}}px`;
      svg.style.height = `${{height}}px`;
      svg.innerHTML = parts.join('');
      zoomValue.textContent = `${{zoom}}×`;
      root.querySelectorAll('.event').forEach((node) => {{
        const event = payload.events[Number(node.dataset.index)];
        const show = (clientX, clientY) => {{
          tooltip.style.display = 'block';
          tooltip.style.left = `${{clientX + 12}}px`;
          tooltip.style.top = `${{clientY + 12}}px`;
          tooltip.innerHTML = `<strong>${{esc(event.lane)}} / ${{esc(event.kind)}}</strong><br>`+
            `相對時間：${{((event.ts-start)/1e6).toFixed(6)}} ms<br>`+
            `絕對時間：${{event.absoluteNs}} ns<br>`+
            `${{esc(event.label)}}<br>`+
            `<span class="tooltip-source">記錄來源：${{esc(event.source || '未提供')}}</span>`;
        }};
        node.addEventListener('pointerenter', (e) => show(e.clientX, e.clientY));
        node.addEventListener('pointermove', (e) => show(e.clientX, e.clientY));
        node.addEventListener('pointerleave', () => tooltip.style.display = 'none');
      }});
    }};
    zoomInput.addEventListener('input', render);
    render();
  }})();
  </script>
</div>
</body>
</html>
'''
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(html, encoding="utf-8")


def main() -> None:
    args = parse_args()
    analysis = load_analysis(args.analysis)
    selected, frame_rows, start_ts, end_ts = select_gap(analysis, args.before, args.after)
    offset = ns(analysis["media3_telemetry"]["clock_calibration"]["offset_boottime_minus_monotonic_ns"])
    if offset is None:
        raise RuntimeError("missing Media3 monotonic-to-Perfetto clock offset")
    interval = selected["interval"]
    previous_frame = ns(interval.get("previous_frame_number"))
    current_frame = ns(selected.get("frame_number"))
    previous_present = ns(interval.get("previous_present_ts_ns"))
    current_present = ns(selected.get("present_ts_ns"))
    if None in (previous_frame, current_frame, previous_present, current_present):
        raise RuntimeError("selected gap lacks previous/current frame identity")
    missing = list(range(previous_frame + 1, current_frame))
    events = collect_events(analysis, args.media3, args.native, start_ts, end_ts, offset)
    metadata = {
        "before": args.before,
        "after": args.after,
        "startTsNs": start_ts,
        "endTsNs": end_ts,
        "previousFrameNumber": previous_frame,
        "frameNumber": current_frame,
        "previousPresentTsNs": previous_present,
        "presentTsNs": current_present,
        "intervalMs": float(interval["interval_ms"]),
        "missingFrameNumbers": missing,
        "frameRows": [{"frameNumber": ns(row["frame_number"]), "presentTsNs": ns(row["present_ts_ns"])}
                       for row in frame_rows],
        "clockOffsetNs": offset,
        "sourceAnalysis": str(args.analysis),
    }
    render_html(metadata, events, args.output)
    print(json.dumps({"output": str(args.output), "event_count": len(events), "metadata": metadata},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
