"""Semantic analysis for Bilibili SurfaceFlinger frame traces.

The trace contains several different clocks and scopes that must not be mixed:

* expected_frame_timeline_slice is a DisplayFrame prediction stream.
* actual_frame_timeline_slice is the DisplayFrame present-time stream.
* frame_slice/PresentFenceSignaled is the identified video buffer present stream.

This module keeps those streams separate.  In particular, it does not call
``actual.ts - expected.ts`` video present lateness.  For DisplayFrame rows it
reports the start delay and the end/deadline difference as different fields.
The video cadence is measured from the identified PresentFenceSignaled events.
"""

from __future__ import annotations

import bisect
import csv
import json
import math
import re
import statistics
import sys
from collections import Counter, defaultdict, deque
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


STAGE_NAMES = ("Dequeue", "Queue", "AcquireFenceSignaled", "Latch", "PresentFenceSignaled")
VIDEO_STAGE_NAMES = set(STAGE_NAMES)
NOMINAL_SOURCE_FPS = 120.0
LARGE_GAP_MS = 100.0
FIXED_ANALYSIS_WINDOW_DURATION_NS = 30_000_000_000
TELEMETRY_TEMPORAL_MATCH_LIMIT_NS = 50_000_000
NATIVE_HANDOFF_TEMPORAL_MATCH_LIMIT_NS = 50_000_000
NATIVE_PTS_MATCH_WINDOW_NS = 50_000_000
RENDER_CALLBACK_MATCH_WINDOW_NS = 1_000_000_000
DISPLAY_CALLBACK_START_PATTERN = re.compile(r"^BiliP2_display_callback_(\d+)$")
DISPLAY_CALLBACK_END_PATTERN = re.compile(r"^BiliP2_display_callback_end_(\d+)$")
NATIVE_DISPLAY_START_PATTERN = re.compile(
    r"^BiliP2_native_display_(\d+)_s(-?\d+)_p(-?\d+)$"
)
NATIVE_DISPLAY_CALL_PATTERN = re.compile(r"^BiliP2_native_display_call_(\d+)$")
FRAME_RECEIVER_ON_VSYNC_PATTERN = re.compile(
    r"^BiliP2_frame_receiver_on_vsync_(-?\d+)(?:_(\d+))?$"
)
FRAME_RECEIVER_DISPATCH_PATTERN = re.compile(
    r"^BiliP2_frame_receiver_dispatch_vsync_(-?\d+)_t(\d+)_(\d+)$"
)
FRAME_RECEIVER_RUN_PATTERN = re.compile(r"^BiliP2_frame_receiver_run_(\d+)$")
CHOREOGRAPHER_SCHEDULE_FRAME_PATTERN = re.compile(
    r"^BiliP2_choreographer_schedule_frame_locked_(\d+)(?:_d(\d+))?$"
)
CHOREOGRAPHER_HANDLER_FRONT_PATTERN = re.compile(
    r"^BiliP2_choreographer_frame_handler_front_(\d+)(?:_d(\d+))?$"
)
CHOREOGRAPHER_QUEUE_ENQUEUE_PATTERN = re.compile(
    r"^BiliP2_choreographer_queue_enqueue_(\d+)(?:_d(\d+))?$"
)
SURFACE_TEXTURE_CALLBACK_NAME = "android.graphics.SurfaceTexture$1: 0"
SURFACE_TEXTURE_FRAME_READY_PATTERN = re.compile(
    r"^BiliP2_surface_texture_frame_ready_(\d+)$"
)
SURFACE_TEXTURE_UPDATE_PATTERN = re.compile(
    r"^BiliP2_surface_texture_update_(\d+)$"
)
SURFACE_TEXTURE_UPDATE_TIMESTAMP_PATTERN = re.compile(
    r"^BiliP2_surface_texture_update_timestamp_(\d+)_p(-?\d+)$"
)
SURFACE_TEXTURE_VIDEO_GAP_MATCH_LIMIT_NS = 5_000_000


def row_value(row: Any, name: str, default: Any = None) -> Any:
    if isinstance(row, Mapping):
        return row.get(name, default)
    try:
        return getattr(row, name)
    except AttributeError:
        return default


def to_int(value: Any, default: int | None = None) -> int | None:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def to_float(value: Any, default: float | None = None) -> float | None:
    if value is None:
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Mapping):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return str(value)


def percentile(values: Sequence[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def median_reference_period_ns(timestamps: Iterable[int]) -> int | None:
    ordered = sorted(int(timestamp) for timestamp in timestamps if timestamp is not None)
    intervals = [current - previous for previous, current in zip(ordered, ordered[1:]) if current > previous]
    if not intervals:
        return None
    return int(round(statistics.median(intervals)))


def interval_summary(
    timestamps: Iterable[int],
    reference_period_ns: int | None = None,
    gap_threshold_periods: float = 1.5,
    nominal_period_ns: int | None = None,
) -> dict[str, Any]:
    ordered = sorted(int(timestamp) for timestamp in timestamps if timestamp is not None)
    intervals = [current - previous for previous, current in zip(ordered, ordered[1:])]
    positive = [delta for delta in intervals if delta > 0]
    reference = reference_period_ns or median_reference_period_ns(ordered)
    nominal = nominal_period_ns or reference
    gap_limit = reference * gap_threshold_periods if reference is not None else None
    large_gap_ns = int(LARGE_GAP_MS * 1e6)
    return {
        "count": len(ordered),
        "interval_count": len(intervals),
        "positive_interval_count": len(positive),
        "nonpositive_interval_count": sum(delta <= 0 for delta in intervals),
        "first_ts_ns": ordered[0] if ordered else None,
        "last_ts_ns": ordered[-1] if ordered else None,
        "span_ms": None if len(ordered) < 2 else (ordered[-1] - ordered[0]) / 1e6,
        "median_interval_ms": None if not positive else statistics.median(positive) / 1e6,
        "p01_interval_ms": None if not positive else percentile(positive, 0.01) / 1e6,
        "p99_interval_ms": None if not positive else percentile(positive, 0.99) / 1e6,
        "min_interval_ms": None if not positive else min(positive) / 1e6,
        "max_interval_ms": None if not positive else max(positive) / 1e6,
        "reference_period_ms": None if reference is None else reference / 1e6,
        "nominal_period_ms": None if nominal is None else nominal / 1e6,
        "gap_threshold_periods": gap_threshold_periods,
        "gap_threshold_ms": None if gap_limit is None else gap_limit / 1e6,
        "gap_count": 0 if gap_limit is None else sum(delta > gap_limit for delta in positive),
        "greater_than_nominal_count": 0 if nominal is None else sum(delta > nominal for delta in positive),
        "greater_than_100ms_count": sum(delta > large_gap_ns for delta in positive),
        "small_gap_count": 0
        if gap_limit is None
        else sum(gap_limit < delta <= large_gap_ns for delta in positive),
    }


def one_percent_low_fps(intervals_ns: Sequence[int], exclude_over_ms: float | None = None) -> float | None:
    valid = [int(delta) for delta in intervals_ns if delta > 0]
    if exclude_over_ms is not None:
        valid = [delta for delta in valid if delta <= exclude_over_ms * 1e6]
    if not valid:
        return None
    count = max(1, math.ceil(len(valid) * 0.01))
    slowest = sorted(valid, reverse=True)[:count]
    mean_interval_ns = statistics.mean(slowest)
    return 1e9 / mean_interval_ns if mean_interval_ns > 0 else None


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def query_rows(tp: Any, sql: str) -> list[Any]:
    try:
        return list(tp.query(sql))
    except Exception:
        return []


def query_timeline_rows(tp: Any, table: str, actual: bool) -> list[dict[str, Any]]:
    fields = [
        "id",
        "ts",
        "dur",
        "display_frame_token",
        "surface_frame_token",
        "upid",
        "layer_name",
    ]
    if actual:
        fields.extend(
            [
                "present_type",
                "on_time_finish",
                "gpu_composition",
                "jank_type",
                "jank_severity_type",
                "prediction_type",
                "jank_tag",
                "jank_tag_experimental",
                "jank_score",
                "latched_unsignaled_count",
                "addressable_unsignaled_latch_count",
                "latched_fence_state",
            ]
        )
    rows = query_rows(tp, f"select {','.join(fields)} from {table} order by ts, id")
    return [
        {field: json_safe(row_value(row, field)) for field in fields}
        for row in rows
    ]


def select_display_stream(rows: Sequence[Mapping[str, Any]], kind: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    groups: dict[tuple[Any, Any, str], list[Mapping[str, Any]]] = defaultdict(list)
    for row in rows:
        layer_name = row.get("layer_name")
        key = (
            to_int(row.get("upid")),
            layer_name,
            "display_scope" if layer_name is None else "layer_scope",
        )
        groups[key].append(row)

    candidates: list[dict[str, Any]] = []
    for key, group in groups.items():
        upid, layer_name, scope = key
        candidates.append(
            {
                "upid": upid,
                "layer_name": layer_name,
                "scope": scope,
                "count": len(group),
                "surface_frame_token_count": len(
                    {
                        to_int(item.get("surface_frame_token"))
                        for item in group
                        if to_int(item.get("surface_frame_token")) is not None
                    }
                ),
                "is_display_scope": scope == "display_scope",
                "first_ts_ns": min(
                    (to_int(item.get("ts")) for item in group if to_int(item.get("ts")) is not None),
                    default=None,
                ),
                "last_ts_ns": max(
                    (to_int(item.get("ts")) for item in group if to_int(item.get("ts")) is not None),
                    default=None,
                ),
            }
        )

    if not candidates:
        return [], {"kind": kind, "selected": None, "candidates": [], "reason": "no FrameTimeline rows"}

    selected = max(
        candidates,
        key=lambda item: (
            bool(item["is_display_scope"]),
            int(item["count"]),
            item["upid"] is not None,
        ),
    )
    selected_key = (selected["upid"], selected["layer_name"], selected["scope"])
    selected_rows = [dict(row) for row in groups[selected_key]]
    selected_rows.sort(key=lambda item: (to_int(item.get("ts"), 0), to_int(item.get("id"), 0)))
    selected["selection_reason"] = (
        "preferred null-layer/null-surface-token DisplayFrame scope"
        if selected["is_display_scope"]
        else "largest remaining FrameTimeline stream; display scope was unavailable"
    )
    return selected_rows, {"kind": kind, "selected": selected, "candidates": candidates}


def frame_slice_rows(tp: Any) -> list[dict[str, Any]]:
    fields = [
        "id",
        "ts",
        "dur",
        "name",
        "layer_name",
        "frame_number",
        "queue_to_acquire_time",
        "acquire_to_latch_time",
        "latch_to_present_time",
    ]
    rows = query_rows(
        tp,
        "select "
        + ",".join(fields)
        + " from frame_slice where name in ('Dequeue','Queue','AcquireFenceSignaled','Latch','PresentFenceSignaled')"
        + " and layer_name is not null order by ts, id",
    )
    return [{field: json_safe(row_value(row, field)) for field in fields} for row in rows]


SET_BUFFER_FRAME_PATTERN = re.compile(r"\bframeNumber\s+(\d+)\b")


def parse_set_buffer_frame_number(name: str) -> int | None:
    match = SET_BUFFER_FRAME_PATTERN.search(name or "")
    return None if match is None else int(match.group(1))


def query_set_buffer_events(tp: Any, package_name: str) -> dict[str, Any]:
    package_literal = sql_literal(f"%{package_name}%")
    rows = query_rows(
        tp,
        "select ts,name,track_id from slice "
        f"where name like 'setBuffer %' and name like '%hasBuffer=true%' and name like {package_literal} order by ts",
    )
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        name = str(row_value(row, "name", ""))
        label = name.split(" - hasBuffer", 1)[0]
        groups[label].append(
            {
                "ts_ns": to_int(row_value(row, "ts")),
                "track_id": to_int(row_value(row, "track_id")),
                "frame_number": parse_set_buffer_frame_number(name),
            }
        )
    if not groups:
        return {
            "source": "slice.setBuffer hasBuffer=true",
            "selected_label": None,
            "event_count": 0,
            "frame_number_parse_pattern": SET_BUFFER_FRAME_PATTERN.pattern,
            "frame_number_parse_missing_count": 0,
            "note": "secondary submission diagnostic unavailable",
        }
    label, events = max(
        groups.items(),
        key=lambda item: ("SurfaceView" in item[0], package_name in item[0], len(item[1])),
    )
    events.sort(key=lambda item: (item["ts_ns"] or 0, item["track_id"] or 0))
    frame_numbers = [event["frame_number"] for event in events if event["frame_number"] is not None]
    frame_deltas = [current - previous for previous, current in zip(frame_numbers, frame_numbers[1:])]
    timestamps = [event["ts_ns"] for event in events if event["ts_ns"] is not None]
    return {
        "source": "slice.setBuffer hasBuffer=true",
        "selected_label": label,
        "event_count": len(events),
        "frame_number_min": min(frame_numbers) if frame_numbers else None,
        "frame_number_max": max(frame_numbers) if frame_numbers else None,
        "frame_number_parse_pattern": SET_BUFFER_FRAME_PATTERN.pattern,
        "frame_number_parse_missing_count": len(events) - len(frame_numbers),
        "frame_number_nonunit_delta_count": sum(delta != 1 for delta in frame_deltas),
        "timestamp_first_ns": min(timestamps) if timestamps else None,
        "timestamp_last_ns": max(timestamps) if timestamps else None,
        "timestamp_interval_summary": interval_summary(timestamps),
        "event_semantics": "SURFACEFLINGER_STATE_APPLY",
        "producer_submission_timestamp": False,
        "note": (
            "setBuffer is a SurfaceFlinger thread_execution slice under "
            "DisplayCallbackAndStatsUpdates; it is internal state/transaction timing, "
            "not a producer-side queueBuffer timestamp."
        ),
    }


def query_producer_queue_events(tp: Any, package_name: str) -> dict[str, Any]:
    """Read existing producer-side queueBuffer slices for temporal phase analysis."""
    package_literal = sql_literal(package_name)
    rows = query_rows(
        tp,
        "select s.ts,s.dur,s.name,s.track_id,s.parent_id,"
        "p.name as parent_name,p.ts as parent_ts,p.dur as parent_dur,"
        "th.name as thread_name,pr.name as process_name "
        "from slice s "
        "left join slice p on p.id=s.parent_id "
        "left join thread_track tt on tt.id=s.track_id "
        "left join thread th on th.utid=tt.utid "
        "left join process pr on pr.upid=th.upid "
        f"where s.name='queueBuffer' and ((pr.name='tv.danmaku.bili:ijkservice' "
        "and th.name in ('ijk_ff_vout','MediaCodec_loop')) "
        f"or (pr.name={package_literal} and th.name='ijk_ext_render')) "
        "order by s.ts,s.id",
    )
    events = []
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        events.append(
            {
                "ts_ns": timestamp,
                "dur_ns": to_int(row_value(row, "dur"), 0),
                "thread_name": row_value(row, "thread_name"),
                "process_name": row_value(row, "process_name"),
                "track_id": to_int(row_value(row, "track_id")),
                "parent_id": to_int(row_value(row, "parent_id")),
                "parent_name": row_value(row, "parent_name"),
                "parent_ts_ns": to_int(row_value(row, "parent_ts")),
                "parent_dur_ns": to_int(row_value(row, "parent_dur"), 0),
            }
        )
    stream_events: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in events:
        stream_events[str(item.get("thread_name"))].append(item)
    stream_counts = Counter({stream: len(items) for stream, items in stream_events.items()})
    selected_stream = next(
        (
            stream
            for stream in ("ijk_ff_vout", "ijk_ext_render", "MediaCodec_loop")
            if stream_counts.get(stream, 0) > 0
        ),
        None,
    )
    selected_events = [
        item for item in events if item.get("thread_name") == selected_stream
    ]
    return {
        "status": "AVAILABLE" if selected_events else "NO_EVENTS",
        "source": "slice.name=queueBuffer with producer thread/process filters",
        "event_count": len(events),
        "stream_counts": dict(stream_counts),
        "stream_events": dict(stream_events),
        "selected_stream": selected_stream,
        "selected_events": selected_events,
        "events": events,
        "evidence_quality": "RAW_OBSERVED",
        "note": (
            "queueBuffer is retained as producer-side temporal evidence. It has no shared "
            "buffer identity with the selected SurfaceFlinger frame_number."
        ),
    }


SURFACE_COMMIT_PATTERN = re.compile(r"^commit ([0-9]+)\s")
SURFACE_BEGIN_FRAME_PATTERN = re.compile(r"^beginFrame ([0-9]+)\b")
SURFACE_FRAME_NUMBER_PATTERN = re.compile(r"\bframeNumber\s+([0-9]+)\b")
SURFACE_LATCH_FRAME_PATTERN = re.compile(r" - ([0-9]+)$")


def query_surface_flinger_latch_events(tp: Any, layer_name: str | None) -> dict[str, Any]:
    """Read SurfaceFlinger scheduler and selected-layer latch evidence.

    The trace does not expose a shared codec-to-buffer key, so these events are
    intentionally kept as scheduler/layer evidence. They are joined to video
    frames by SurfaceFlinger frame_number and commit-cycle time only.
    """
    empty = {
        "source": "slice SurfaceFlinger commit/beginFrame/latch/setBuffer diagnostics",
        "layer_name": layer_name,
        "status": "UNAVAILABLE" if not layer_name else "NO_EVENTS",
        "commit_cycles": [],
        "begin_frame_cycles": [],
        "predictor_frame_begin_ts_ns": [],
        "should_latch_unsignaled_events": [],
        "unsignaled_fence_events": [],
        "set_buffer_events": [],
        "latch_buffer_events": [],
        "note": (
            "These are SurfaceFlinger scheduler/layer events. setBuffer is an internal "
            "state-apply slice, not producer queueBuffer. They do not create a shared codec, "
            "external-renderer, and SurfaceFlinger buffer identity."
        ),
    }
    if not layer_name:
        return empty

    rows = query_rows(
        tp,
        "select s.ts,s.dur,s.name,s.track_id,s.parent_id,"
        "p.name as parent_name,p.ts as parent_ts,p.dur as parent_dur,"
        "tr.type as track_type,th.name as thread_name,pr.name as process_name "
        "from slice s "
        "left join slice p on p.id=s.parent_id "
        "left join track tr on tr.id=s.track_id "
        "left join thread_track tt on tt.id=s.track_id "
        "left join thread th on th.utid=tt.utid "
        "left join process pr on pr.upid=th.upid "
        "where "
        "s.name like 'commit %' or s.name like 'beginFrame %' or "
        "s.name='VSyncPredictor::onFrameBegin' or "
        "s.name like 'shouldLatchUnsignaled:%' or s.name like 'fence unsignaled %' or "
        "s.name like 'setBuffer %' or s.name like 'latchBuffer %' order by s.ts",
    )
    result = {key: value for key, value in empty.items() if key not in {
        "commit_cycles",
        "begin_frame_cycles",
        "predictor_frame_begin_ts_ns",
        "should_latch_unsignaled_events",
        "unsignaled_fence_events",
        "set_buffer_events",
        "latch_buffer_events",
    }}
    result.update(
        {
            "commit_cycles": [],
            "begin_frame_cycles": [],
            "predictor_frame_begin_ts_ns": [],
            "should_latch_unsignaled_events": [],
            "unsignaled_fence_events": [],
            "set_buffer_events": [],
            "latch_buffer_events": [],
        }
    )
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        name = str(row_value(row, "name", ""))
        duration = to_int(row_value(row, "dur"), 0) or 0
        track_id = to_int(row_value(row, "track_id"))
        commit_match = SURFACE_COMMIT_PATTERN.match(name)
        if commit_match:
            result["commit_cycles"].append(
                {
                    "ts_ns": timestamp,
                    "dur_ns": duration,
                    "track_id": track_id,
                    "vsync_id": int(commit_match.group(1)),
                    "name": name,
                }
            )
            continue
        begin_match = SURFACE_BEGIN_FRAME_PATTERN.match(name)
        if begin_match:
            result["begin_frame_cycles"].append(
                {
                    "ts_ns": timestamp,
                    "dur_ns": duration,
                    "track_id": track_id,
                    "vsync_id": int(begin_match.group(1)),
                    "name": name,
                }
            )
            continue
        if name == "VSyncPredictor::onFrameBegin":
            result["predictor_frame_begin_ts_ns"].append(timestamp)
            continue
        if name.startswith("shouldLatchUnsignaled:"):
            result["should_latch_unsignaled_events"].append(
                {"ts_ns": timestamp, "dur_ns": duration, "name": name}
            )
            continue
        if name.startswith("fence unsignaled ") and layer_name in name:
            result["unsignaled_fence_events"].append(
                {"ts_ns": timestamp, "dur_ns": duration, "name": name}
            )
            continue
        if layer_name not in name:
            continue
        if name.startswith("setBuffer ") and "hasBuffer=true" in name:
            frame_match = SURFACE_FRAME_NUMBER_PATTERN.search(name)
            result["set_buffer_events"].append(
                {
                    "ts_ns": timestamp,
                    "dur_ns": duration,
                    "track_id": track_id,
                    "frame_number": None if frame_match is None else int(frame_match.group(1)),
                    "track_type": row_value(row, "track_type"),
                    "thread_name": row_value(row, "thread_name"),
                    "process_name": row_value(row, "process_name"),
                    "parent_id": to_int(row_value(row, "parent_id")),
                    "parent_name": row_value(row, "parent_name"),
                    "parent_ts_ns": to_int(row_value(row, "parent_ts")),
                    "parent_dur_ns": to_int(row_value(row, "parent_dur"), 0),
                    "event_semantics": "SURFACEFLINGER_STATE_APPLY",
                    "producer_submission_timestamp": False,
                    "name": name,
                }
            )
        elif name.startswith("latchBuffer "):
            frame_match = SURFACE_LATCH_FRAME_PATTERN.search(name)
            result["latch_buffer_events"].append(
                {
                    "ts_ns": timestamp,
                    "dur_ns": duration,
                    "track_id": track_id,
                    "frame_number": None if frame_match is None else int(frame_match.group(1)),
                    "track_type": row_value(row, "track_type"),
                    "thread_name": row_value(row, "thread_name"),
                    "process_name": row_value(row, "process_name"),
                    "parent_id": to_int(row_value(row, "parent_id")),
                    "parent_name": row_value(row, "parent_name"),
                    "parent_ts_ns": to_int(row_value(row, "parent_ts")),
                    "parent_dur_ns": to_int(row_value(row, "parent_dur"), 0),
                    "event_semantics": "SURFACEFLINGER_STATE_APPLY",
                    "producer_submission_timestamp": False,
                    "name": name,
                }
            )

    result["status"] = (
        "VALID"
        if result["commit_cycles"] and result["set_buffer_events"] and result["latch_buffer_events"]
        else "PARTIAL"
        if result["commit_cycles"]
        else "NO_EVENTS"
    )
    return result


def query_renderer_cadence_events(tp: Any) -> dict[str, Any]:
    """Read timing-only Choreographer events around the external-render thread.

    The app's main-thread ``onVsync`` stream is retained as a same-process
    scheduling witness.  It is not treated as a panel scanout signal.
    """
    rows = query_rows(
        tp,
        "select s.ts,s.dur,s.name,s.track_id,t.name as thread_name from slice s "
        "join thread_track tt on s.track_id=tt.id "
        "join thread t on tt.utid=t.utid "
        "join process p on t.upid=p.upid "
        "where p.name='tv.danmaku.bili' and ("
        "s.name like 'Choreographer#onVsync%' or "
        "s.name like 'Choreographer#doFrame%' or "
        "s.name='Choreographer#scheduleVsyncLocked' or "
        "s.name='eglSwapBuffers' or s.name='queueBuffer' or "
        "s.name like 'onFrameAvailable%') order by s.ts",
    )
    events: dict[str, list[dict[str, Any]]] = {
        "on_vsync": [],
        "app_on_vsync": [],
        "do_frame": [],
        "vsync_schedule": [],
        "egl_swap": [],
        "queue_buffer": [],
        "frame_available": [],
    }
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        name = str(row_value(row, "name", ""))
        item = {
            "ts_ns": timestamp,
            "dur_ns": to_int(row_value(row, "dur"), 0) or 0,
            "track_id": to_int(row_value(row, "track_id")),
            "name": name,
            "thread_name": row_value(row, "thread_name"),
        }
        thread_name = str(row_value(row, "thread_name", ""))
        if name.startswith("Choreographer#onVsync"):
            if thread_name == "ijk_ext_render":
                events["on_vsync"].append(item)
            else:
                events["app_on_vsync"].append(item)
        elif thread_name == "ijk_ext_render" and name.startswith("Choreographer#doFrame"):
            events["do_frame"].append(item)
        elif thread_name == "ijk_ext_render" and name == "Choreographer#scheduleVsyncLocked":
            events["vsync_schedule"].append(item)
        elif thread_name == "ijk_ext_render" and name == "eglSwapBuffers":
            events["egl_swap"].append(item)
        elif thread_name == "ijk_ext_render" and name == "queueBuffer":
            events["queue_buffer"].append(item)
        elif thread_name == "ijk_ext_render" and name.startswith("onFrameAvailable"):
            events["frame_available"].append(item)
    event_count = sum(len(items) for items in events.values())
    return {
        "source": "slice Choreographer/renderer events in tv.danmaku.bili; renderer events restricted to ijk_ext_render",
        "status": "VALID" if events["do_frame"] else "NO_EVENTS",
        "event_counts": {key: len(items) for key, items in events.items()},
        "events": events,
        "note": (
            "These are renderer-thread temporal events. They do not prove which codec frame "
            "or external buffer reached SurfaceFlinger."
        ),
        "total_event_count": event_count,
    }


def query_surface_texture_callback_events(tp: Any) -> dict[str, Any]:
    """Read the Java ``SurfaceTexture.OnFrameAvailableListener`` callback.

    On this device/framework build the callback is represented by the ART trace
    slice ``android.graphics.SurfaceTexture$1: 0`` on ``ijk_java_player``. It
    is retained as a handoff witness between the producer callback path and the
    external-render Choreographer path; it is not itself a SurfaceFlinger
    present or a codec-buffer identity.
    """
    rows = query_rows(
        tp,
        "select s.id as slice_id,s.ts,s.dur,s.name,s.track_id,"
        "tt.utid,t.tid,t.name as thread_name,t.upid,"
        "p.pid,p.name as process_name "
        "from slice s "
        "left join thread_track tt on s.track_id=tt.id "
        "left join thread t on tt.utid=t.utid "
        "left join process p on t.upid=p.upid "
        "where s.name='android.graphics.SurfaceTexture$1: 0' "
        "and t.name='ijk_java_player' order by s.ts,s.id",
    )
    events: list[dict[str, Any]] = []
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        duration = to_int(row_value(row, "dur"), 0) or 0
        events.append({
            "slice_id": to_int(row_value(row, "slice_id")),
            "ts_ns": timestamp,
            "dur_ns": duration,
            "end_ts_ns": timestamp + max(0, duration),
            "name": str(row_value(row, "name", SURFACE_TEXTURE_CALLBACK_NAME)),
            "track_id": to_int(row_value(row, "track_id")),
            "utid": to_int(row_value(row, "utid")),
            "tid": to_int(row_value(row, "tid")),
            "thread_name": row_value(row, "thread_name"),
            "upid": to_int(row_value(row, "upid")),
            "pid": to_int(row_value(row, "pid")),
            "process_name": row_value(row, "process_name"),
        })
    events.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))
    return {
        "source": "slice android.graphics.SurfaceTexture$1: 0 on ijk_java_player",
        "status": "VALID" if events else "NO_EVENTS",
        "event_counts": {"surface_texture_callback": len(events)},
        "events": {"surface_texture_callback": events},
        "thread_counts": dict(Counter(
            str(item.get("thread_name"))
            for item in events
            if item.get("thread_name") is not None
        )),
        "identity_boundary": (
            "SurfaceTexture callback timing identifies the Java frame-ready handoff, but does not expose "
            "the producer buffer identity or prove the later SurfaceFlinger frame."
        ),
    }


def query_surface_texture_probe_events(tp: Any) -> dict[str, Any]:
    """Read object-tokenized SurfaceTexture ready/update probe markers."""
    rows = query_rows(
        tp,
        "select s.id as slice_id,s.ts,s.dur,s.name,s.track_id,"
        "tt.utid,t.tid,t.name as thread_name,t.upid,"
        "p.pid,p.name as process_name "
        "from slice s "
        "left join thread_track tt on s.track_id=tt.id "
        "left join thread t on tt.utid=t.utid "
        "left join process p on t.upid=p.upid "
        "where s.name like 'BiliP2_surface_texture_frame_ready_%' "
        "or s.name like 'BiliP2_surface_texture_update_%' "
        "order by s.ts,s.id",
    )
    events = {"frame_ready": [], "update": [], "update_timestamp": []}
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        name = str(row_value(row, "name", ""))
        ready_match = SURFACE_TEXTURE_FRAME_READY_PATTERN.match(name)
        update_match = SURFACE_TEXTURE_UPDATE_PATTERN.match(name)
        timestamp_match = SURFACE_TEXTURE_UPDATE_TIMESTAMP_PATTERN.match(name)
        if ready_match is None and update_match is None and timestamp_match is None:
            continue
        match = ready_match or update_match or timestamp_match
        kind = (
            "frame_ready" if ready_match is not None
            else "update" if update_match is not None
            else "update_timestamp"
        )
        duration = to_int(row_value(row, "dur"), 0) or 0
        events[kind].append({
            "slice_id": to_int(row_value(row, "slice_id")),
            "ts_ns": timestamp,
            "dur_ns": duration,
            "end_ts_ns": timestamp + max(0, duration),
            "name": name,
            "token": int(match.group(1)),
            "producer_timestamp_ns": (
                None if timestamp_match is None else int(timestamp_match.group(2))
            ),
            "track_id": to_int(row_value(row, "track_id")),
            "utid": to_int(row_value(row, "utid")),
            "tid": to_int(row_value(row, "tid")),
            "thread_name": row_value(row, "thread_name"),
            "upid": to_int(row_value(row, "upid")),
            "pid": to_int(row_value(row, "pid")),
            "process_name": row_value(row, "process_name"),
        })
    for values in events.values():
        values.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))
    all_events = [item for values in events.values() for item in values]
    return {
        "source": "BiliP2_surface_texture_frame_ready/update tokenized SurfaceTexture probe",
        "status": "VALID" if all_events else "NO_EVENTS",
        "event_counts": {key: len(values) for key, values in events.items()},
        "events": events,
        "thread_counts": dict(Counter(
            str(item.get("thread_name"))
            for item in all_events
            if item.get("thread_name") is not None
        )),
        "identity_boundary": (
            "The token joins one SurfaceTexture object's ready callback to updateTexImage in the "
            "same process. It is not a MediaCodec buffer identity or a SurfaceFlinger frame number."
        ),
    }


def query_display_callback_events(tp: Any) -> dict[str, Any]:
    """Read the IJK ``OnVideoDisplayCallback`` trace-only markers.

    ``BiliP2_display_callback`` is emitted by the process/thread that invokes
    IJK's Java display callback, while the renderer cadence markers are emitted
    on ``ijk_ext_render``.  They therefore must not be restricted to the
    renderer thread.  The token is process-local; pid/process name are kept in
    the result so a temporal join never silently becomes a cross-process
    identity claim.
    """
    rows = query_rows(
        tp,
        "select s.id as slice_id,s.ts,s.dur,s.name,s.track_id,"
        "tt.utid,t.tid,t.name as thread_name,t.upid,"
        "p.pid,p.name as process_name "
        "from slice s "
        "left join thread_track tt on s.track_id=tt.id "
        "left join thread t on tt.utid=t.utid "
        "left join process p on t.upid=p.upid "
        "where s.name like 'BiliP2_display_callback%' order by s.ts,s.id",
    )
    if not rows:
        rows = query_rows(
            tp,
            "select id as slice_id,ts,dur,name,track_id "
            "from slice where name like 'BiliP2_display_callback%' order by ts,id",
        )

    starts: list[dict[str, Any]] = []
    ends: list[dict[str, Any]] = []
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        name = str(row_value(row, "name", ""))
        start_match = DISPLAY_CALLBACK_START_PATTERN.match(name)
        end_match = DISPLAY_CALLBACK_END_PATTERN.match(name)
        if start_match is None and end_match is None:
            continue
        token_match = start_match or end_match
        token = int(token_match.group(1))
        item = {
            "slice_id": to_int(row_value(row, "slice_id")),
            "ts_ns": timestamp,
            "dur_ns": to_int(row_value(row, "dur"), 0) or 0,
            "name": name,
            "token": token,
            "track_id": to_int(row_value(row, "track_id")),
            "utid": to_int(row_value(row, "utid")),
            "tid": to_int(row_value(row, "tid")),
            "thread_name": row_value(row, "thread_name"),
            "upid": to_int(row_value(row, "upid")),
            "pid": to_int(row_value(row, "pid")),
            "process_name": row_value(row, "process_name"),
        }
        (starts if start_match is not None else ends).append(item)

    starts.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))
    ends.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))

    def event_key(item: Mapping[str, Any]) -> tuple[Any, ...]:
        process_key = item.get("pid")
        if process_key is None:
            process_key = item.get("process_name")
        return process_key, item.get("token")

    end_by_key = {event_key(item): item for item in ends}
    paired_count = 0
    for item in starts:
        end = end_by_key.get(event_key(item))
        end_ts = None if end is None else to_int(end.get("ts_ns"))
        item["end_ts_ns"] = end_ts
        item["duration_ns"] = (
            None
            if end_ts is None
            else max(0, end_ts - to_int(item.get("ts_ns"), 0))
        )
        if end is not None:
            paired_count += 1

    thread_counts = Counter(
        str(item.get("thread_name"))
        for item in starts
        if item.get("thread_name") is not None
    )
    return {
        "source": "slice BiliP2_display_callback/BiliP2_display_callback_end markers across all processes",
        "status": "VALID" if starts else "NO_EVENTS",
        "event_counts": {
            "display_callback": len(starts),
            "display_callback_end": len(ends),
        },
        "events": {
            "display_callback": starts,
            "display_callback_end": ends,
        },
        "paired_count": paired_count,
        "unpaired_start_count": len(starts) - paired_count,
        "orphan_end_count": len(ends) - paired_count,
        "thread_counts": dict(thread_counts),
        "identity_boundary": (
            "Callback token is process-local instrumentation identity; joins to renderer and "
            "SurfaceFlinger remain temporal unless a shared buffer key is present."
        ),
    }


def query_native_display_events(tp: Any) -> dict[str, Any]:
    """Read native IJK display-entry and original-call ATrace sections.

    The serial and PTS in the section name are copied from the latest native
    queue observation immediately before the display wrapper.  They are useful
    correlation fields, but they are not a codec-buffer identity because the
    IJK display callback does not expose the frame object here.
    """
    rows = query_rows(
        tp,
        "select s.id as slice_id,s.ts,s.dur,s.name,s.track_id,"
        "tt.utid,t.tid,t.name as thread_name,t.upid,"
        "p.pid,p.name as process_name "
        "from slice s "
        "left join thread_track tt on s.track_id=tt.id "
        "left join thread t on tt.utid=t.utid "
        "left join process p on t.upid=p.upid "
        "where s.name like 'BiliP2_native_display_%' order by s.ts,s.id",
    )
    if not rows:
        rows = query_rows(
            tp,
            "select id as slice_id,ts,dur,name,track_id "
            "from slice where name like 'BiliP2_native_display_%' order by ts,id",
        )

    native_display: list[dict[str, Any]] = []
    native_display_call: list[dict[str, Any]] = []
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        name = str(row_value(row, "name", ""))
        start_match = NATIVE_DISPLAY_START_PATTERN.match(name)
        call_match = NATIVE_DISPLAY_CALL_PATTERN.match(name)
        if start_match is None and call_match is None:
            continue
        duration = to_int(row_value(row, "dur"), 0) or 0
        item: dict[str, Any] = {
            "slice_id": to_int(row_value(row, "slice_id")),
            "ts_ns": timestamp,
            "dur_ns": duration,
            "end_ts_ns": timestamp + max(0, duration),
            "name": name,
            "token": int((start_match or call_match).group(1)),
            "track_id": to_int(row_value(row, "track_id")),
            "utid": to_int(row_value(row, "utid")),
            "tid": to_int(row_value(row, "tid")),
            "thread_name": row_value(row, "thread_name"),
            "upid": to_int(row_value(row, "upid")),
            "pid": to_int(row_value(row, "pid")),
            "process_name": row_value(row, "process_name"),
        }
        if start_match is not None:
            item["serial"] = int(start_match.group(2))
            item["pts_us"] = int(start_match.group(3))
            native_display.append(item)
        else:
            native_display_call.append(item)

    native_display.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))
    native_display_call.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))
    binder_rows = query_rows(
        tp,
        "select s.id as slice_id,s.ts,s.dur,s.name,s.track_id,"
        "tt.utid,t.tid,t.name as thread_name,t.upid,"
        "p.pid,p.name as process_name "
        "from slice s "
        "join args a on s.arg_set_id=a.arg_set_id "
        "left join thread_track tt on s.track_id=tt.id "
        "left join thread t on tt.utid=t.utid "
        "left join process p on t.upid=p.upid "
        "where s.name='binder transaction' "
        "and t.name='ijk_ff_vout' "
        "and a.flat_key='code' "
        "and a.string_value='0x0b Java Layer Dependent' "
        "order by s.ts,s.id",
    )
    binder_display_callback: list[dict[str, Any]] = []
    for row in binder_rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        binder_display_callback.append({
            "slice_id": to_int(row_value(row, "slice_id")),
            "ts_ns": timestamp,
            "dur_ns": to_int(row_value(row, "dur"), 0) or 0,
            "name": str(row_value(row, "name", "binder transaction")),
            "track_id": to_int(row_value(row, "track_id")),
            "utid": to_int(row_value(row, "utid")),
            "tid": to_int(row_value(row, "tid")),
            "thread_name": row_value(row, "thread_name"),
            "upid": to_int(row_value(row, "upid")),
            "pid": to_int(row_value(row, "pid")),
            "process_name": row_value(row, "process_name"),
            "binder_code": "0x0b Java Layer Dependent",
        })
    binder_display_callback.sort(
        key=lambda item: (item["ts_ns"], item.get("slice_id") or 0)
    )
    return {
        "source": "slice BiliP2_native_display_* ATrace sections",
        "status": "VALID" if native_display else "NO_EVENTS",
        "event_counts": {
            "native_display": len(native_display),
            "native_display_call": len(native_display_call),
            "binder_display_callback": len(binder_display_callback),
        },
        "events": {
            "native_display": native_display,
            "native_display_call": native_display_call,
            "binder_display_callback": binder_display_callback,
        },
        "thread_counts": dict(Counter(
            str(item.get("thread_name"))
            for item in native_display
            if item.get("thread_name") is not None
        )),
        "identity_boundary": (
            "The serial/PTS fields are latest-queue annotations. They correlate the native wrapper "
            "to IJK telemetry, but do not prove codec-buffer or SurfaceFlinger frame identity."
        ),
    }


def query_frame_receiver_events(tp: Any) -> dict[str, Any]:
    """Read the hidden ``FrameDisplayEventReceiver`` entry and Looper markers.

    The receiver entry is intentionally not restricted to ``ijk_ext_render``.
    Android invokes the hidden ``onVsync`` boundary on the receiver's dispatch
    thread, and filtering it before recording would turn a thread-name mismatch
    into false evidence that the VSync never arrived.  Consumers may classify
    rows by the trace-provided thread name after the complete event stream has
    been retained.
    """
    rows = query_rows(
        tp,
        "select s.id as slice_id,s.ts,s.dur,s.name,s.track_id,"
        "tt.utid,t.tid,t.name as thread_name,t.upid,"
        "p.pid,p.name as process_name "
        "from slice s "
        "left join thread_track tt on s.track_id=tt.id "
        "left join thread t on tt.utid=t.utid "
        "left join process p on t.upid=p.upid "
        "where s.name glob 'BiliP2_frame_receiver_dispatch_vsync_*' "
        "or s.name glob 'BiliP2_frame_receiver_on_vsync_*' "
        "or s.name glob 'BiliP2_frame_receiver_run_*' "
        "or s.name glob 'BiliP2_choreographer_schedule_frame_locked_*' "
        "order by s.ts,s.id",
    )
    if not rows:
        rows = query_rows(
            tp,
            "select id as slice_id,ts,dur,name,track_id "
            "from slice where name glob 'BiliP2_frame_receiver_dispatch_vsync_*' "
            "or name glob 'BiliP2_frame_receiver_on_vsync_*' "
            "or name glob 'BiliP2_frame_receiver_run_*' "
            "or name glob 'BiliP2_choreographer_schedule_frame_locked_*' order by ts,id",
        )

    events: dict[str, list[dict[str, Any]]] = {
        "dispatch_vsync": [],
        "on_vsync": [],
        "run": [],
        "schedule_frame_locked": [],
        "handler_front": [],
        "queue_enqueue": [],
    }
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        name = str(row_value(row, "name", ""))
        dispatch_match = FRAME_RECEIVER_DISPATCH_PATTERN.match(name)
        on_vsync_match = FRAME_RECEIVER_ON_VSYNC_PATTERN.match(name)
        run_match = FRAME_RECEIVER_RUN_PATTERN.match(name)
        schedule_frame_match = CHOREOGRAPHER_SCHEDULE_FRAME_PATTERN.match(name)
        handler_front_match = CHOREOGRAPHER_HANDLER_FRONT_PATTERN.match(name)
        queue_enqueue_match = CHOREOGRAPHER_QUEUE_ENQUEUE_PATTERN.match(name)
        if (
            dispatch_match is None
            and on_vsync_match is None
            and run_match is None
            and schedule_frame_match is None
            and handler_front_match is None
            and queue_enqueue_match is None
        ):
            continue
        item: dict[str, Any] = {
            "slice_id": to_int(row_value(row, "slice_id")),
            "ts_ns": timestamp,
            "dur_ns": to_int(row_value(row, "dur"), 0) or 0,
            "name": name,
            "track_id": to_int(row_value(row, "track_id")),
            "utid": to_int(row_value(row, "utid")),
            "tid": to_int(row_value(row, "tid")),
            "thread_name": row_value(row, "thread_name"),
            "upid": to_int(row_value(row, "upid")),
            "pid": to_int(row_value(row, "pid")),
            "process_name": row_value(row, "process_name"),
        }
        if dispatch_match is not None:
            item["frame"] = int(dispatch_match.group(1))
            item["vsync_timestamp_ns"] = int(dispatch_match.group(2))
            item["token"] = int(dispatch_match.group(3))
            events["dispatch_vsync"].append(item)
        elif on_vsync_match is not None:
            item["frame"] = int(on_vsync_match.group(1))
            item["token"] = (
                None
                if on_vsync_match.group(2) is None
                else int(on_vsync_match.group(2))
            )
            events["on_vsync"].append(item)
        elif run_match is not None:
            item["token"] = int(run_match.group(1))
            events["run"].append(item)
        elif schedule_frame_match is not None:
            item["token"] = int(schedule_frame_match.group(1))
            item["display_token"] = (
                None
                if schedule_frame_match.group(2) is None
                else int(schedule_frame_match.group(2))
            )
            events["schedule_frame_locked"].append(item)
        elif handler_front_match is not None:
            item["token"] = int(handler_front_match.group(1))
            item["display_token"] = (
                None
                if handler_front_match.group(2) is None
                else int(handler_front_match.group(2))
            )
            events["handler_front"].append(item)
        else:
            item["token"] = int(queue_enqueue_match.group(1))
            item["display_token"] = (
                None
                if queue_enqueue_match.group(2) is None
                else int(queue_enqueue_match.group(2))
            )
            events["queue_enqueue"].append(item)

    for items in events.values():
        items.sort(key=lambda item: (item["ts_ns"], item.get("slice_id") or 0))
    thread_counts = {
        key: dict(Counter(str(item.get("thread_name")) for item in items
                          if item.get("thread_name") is not None))
        for key, items in events.items()
    }
    return {
        "source": "slice FrameDisplayEventReceiver dispatch/onVsync/run markers",
        "status": "VALID" if any(events.values()) else "NO_EVENTS",
        "event_counts": {key: len(items) for key, items in events.items()},
        "events": events,
        "thread_counts": thread_counts,
        "identity_boundary": (
            "Receiver markers identify trace events only; thread names classify the observed "
            "dispatch path, not a panel scanline or codec-buffer identity."
        ),
    }


def select_video_layer(frame_rows: Sequence[Mapping[str, Any]], package_name: str) -> tuple[str | None, list[dict[str, Any]], dict[str, Any]]:
    present_groups: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    stage_groups: dict[str, Counter[str]] = defaultdict(Counter)
    stage_by_layer_frame: dict[str, dict[int, set[str]]] = defaultdict(lambda: defaultdict(set))
    for row in frame_rows:
        layer = row.get("layer_name")
        if not layer or package_name not in str(layer):
            continue
        layer = str(layer)
        name = str(row.get("name"))
        stage_groups[layer][name] += 1
        frame_number = to_int(row.get("frame_number"))
        if frame_number is not None:
            stage_by_layer_frame[layer][frame_number].add(name)
        if name == "PresentFenceSignaled":
            present_groups[layer].append(row)

    candidates: list[dict[str, Any]] = []
    for layer, presents in present_groups.items():
        frame_numbers = [to_int(row.get("frame_number")) for row in presents]
        frame_numbers = [number for number in frame_numbers if number is not None]
        timestamps = [to_int(row.get("ts")) for row in presents]
        timestamps = [timestamp for timestamp in timestamps if timestamp is not None]
        stages = stage_groups[layer]
        surface_view = "SurfaceView" in layer
        complete_stage_count = sum(
            {
                "Queue",
                "AcquireFenceSignaled",
                "Latch",
                "PresentFenceSignaled",
            }
            <= stage_by_layer_frame[layer].get(number, set())
            for number in set(frame_numbers)
        )
        candidates.append(
            {
                "layer_name": layer,
                "is_surface_view": surface_view,
                "package_match": package_name in layer,
                "present_count": len(presents),
                "unique_present_frame_count": len(set(frame_numbers)),
                "frame_number_min": min(frame_numbers) if frame_numbers else None,
                "frame_number_max": max(frame_numbers) if frame_numbers else None,
                "first_present_ts_ns": min(timestamps) if timestamps else None,
                "last_present_ts_ns": max(timestamps) if timestamps else None,
                "stage_counts": dict(stages),
                "complete_stage_frame_count": complete_stage_count,
            }
        )

    if not candidates:
        return None, [], {"selected": None, "candidates": [], "reason": "no package-owned PresentFenceSignaled layer"}

    selected = max(
        candidates,
        key=lambda item: (
            bool(item["is_surface_view"]),
            bool(item["package_match"]),
            int(item["unique_present_frame_count"]),
            int(item["present_count"]),
            int(item["complete_stage_frame_count"]),
        ),
    )
    layer_name = selected["layer_name"]
    selected_rows = [dict(row) for row in frame_rows if row.get("layer_name") == layer_name]
    selected_rows.sort(key=lambda item: (to_int(item.get("ts"), 0), to_int(item.get("id"), 0)))
    selected["selection_reason"] = (
        "package-owned SurfaceView with the strongest PresentFenceSignaled identity stream"
        if selected["is_surface_view"]
        else "package-owned layer selected because no SurfaceView candidate had a stronger identity stream"
    )
    return layer_name, selected_rows, {"selected": selected, "candidates": candidates}


def group_stage_events(frame_rows: Sequence[Mapping[str, Any]]) -> dict[int, dict[str, list[dict[str, Any]]]]:
    grouped: dict[int, dict[str, list[dict[str, Any]]]] = defaultdict(lambda: defaultdict(list))
    for row in frame_rows:
        frame_number = to_int(row.get("frame_number"))
        name = str(row.get("name"))
        if frame_number is not None and name in VIDEO_STAGE_NAMES:
            grouped[frame_number][name].append(dict(row))
    for frame in grouped.values():
        for events in frame.values():
            events.sort(key=lambda item: (to_int(item.get("ts"), 0), to_int(item.get("id"), 0)))
    return grouped


def nearest_stage_event(events: Sequence[Mapping[str, Any]], present_ts_ns: int | None) -> Mapping[str, Any] | None:
    if not events:
        return None
    if present_ts_ns is None:
        return events[-1]
    before = [event for event in events if (to_int(event.get("ts")) or 0) <= present_ts_ns]
    if before:
        return before[-1]
    return min(events, key=lambda event: abs((to_int(event.get("ts")) or 0) - present_ts_ns))


def stage_timestamp(event: Mapping[str, Any] | None) -> int | None:
    return None if event is None else to_int(event.get("ts"))


def stage_join_for_present(
    present: Mapping[str, Any],
    grouped: Mapping[int, Mapping[str, Sequence[Mapping[str, Any]]]],
    duplicate_frame_numbers: set[int],
) -> dict[str, Any]:
    frame_number = to_int(present.get("frame_number"))
    present_ts = to_int(present.get("ts"))
    frame = grouped.get(frame_number, {}) if frame_number is not None else {}
    selected: dict[str, Mapping[str, Any] | None] = {}
    duplicate_counts: dict[str, int] = {}
    for name in STAGE_NAMES:
        events = list(frame.get(name, []))
        duplicate_counts[name] = max(0, len(events) - 1)
        selected[name] = present if name == "PresentFenceSignaled" else nearest_stage_event(events, present_ts)

    timestamps = {name: stage_timestamp(event) for name, event in selected.items()}
    invalid_reasons: list[str] = []
    if frame_number is None:
        invalid_reasons.append("missing_frame_number")
    if frame_number in duplicate_frame_numbers:
        invalid_reasons.append("duplicate_present_frame_number")
    for name in ("Queue", "AcquireFenceSignaled", "Latch", "PresentFenceSignaled"):
        if timestamps[name] is None:
            invalid_reasons.append(f"missing_{name.lower()}_stage")
    if timestamps["Queue"] is not None and timestamps["AcquireFenceSignaled"] is not None:
        if timestamps["AcquireFenceSignaled"] < timestamps["Queue"]:
            invalid_reasons.append("acquire_before_queue")
    if timestamps["AcquireFenceSignaled"] is not None and timestamps["Latch"] is not None:
        if timestamps["Latch"] < timestamps["AcquireFenceSignaled"]:
            invalid_reasons.append("latch_before_acquire")
    if timestamps["Latch"] is not None and timestamps["PresentFenceSignaled"] is not None:
        if timestamps["PresentFenceSignaled"] < timestamps["Latch"]:
            invalid_reasons.append("present_before_latch")

    return {
        "frame_number": frame_number,
        "present_ts_ns": present_ts,
        "stage_ts_ns": timestamps,
        "stage_latency_ns": {
            "queue_to_acquire": None
            if timestamps["Queue"] is None or timestamps["AcquireFenceSignaled"] is None
            else timestamps["AcquireFenceSignaled"] - timestamps["Queue"],
            "acquire_to_latch": None
            if timestamps["AcquireFenceSignaled"] is None or timestamps["Latch"] is None
            else timestamps["Latch"] - timestamps["AcquireFenceSignaled"],
            "latch_to_present": None
            if timestamps["Latch"] is None or timestamps["PresentFenceSignaled"] is None
            else timestamps["PresentFenceSignaled"] - timestamps["Latch"],
        },
        "stage_duplicate_counts": duplicate_counts,
        "invalid_reasons": invalid_reasons,
    }


def analyze_surface_flinger_buffer_replacement(
    video_frame_timing: Sequence[Mapping[str, Any]],
    interval_rows: Sequence[Mapping[str, Any]],
    surface_events: Mapping[str, Any],
    stage_groups: Mapping[int, Mapping[str, Sequence[Mapping[str, Any]]]],
    reference_period_ns: int | None,
    gap_threshold_periods: float,
    fixed_window: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Find selected-layer buffers that were queued but never latched or presented.

    ``frame_number`` is an exact identity only inside SurfaceFlinger.  This
    function therefore stops at the SurfaceFlinger boundary and does not infer
    which MediaCodec or native frame produced the submission.
    """
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int((fixed_window or {}).get("end_ts_ns_exclusive"))
    set_buffer_by_frame: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for item in surface_events.get("set_buffer_events", []):
        frame_number = to_int(item.get("frame_number"))
        timestamp = to_int(item.get("ts_ns"))
        if frame_number is not None and timestamp is not None:
            set_buffer_by_frame[frame_number].append(dict(item))
    for events in set_buffer_by_frame.values():
        events.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), to_int(item.get("track_id"), 0)))

    latch_buffer_by_frame: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for item in surface_events.get("latch_buffer_events", []):
        frame_number = to_int(item.get("frame_number"))
        timestamp = to_int(item.get("ts_ns"))
        if frame_number is not None and timestamp is not None:
            latch_buffer_by_frame[frame_number].append(dict(item))

    present_by_frame: dict[int, dict[str, Any]] = {}
    timing_by_frame: dict[int, Mapping[str, Any]] = {}
    for item in video_frame_timing:
        frame_number = to_int(item.get("frame_number"))
        timestamp = to_int(item.get("present_ts_ns"))
        if frame_number is None or timestamp is None:
            continue
        present_by_frame[frame_number] = {"frame_number": frame_number, "ts_ns": timestamp}
        timing_by_frame[frame_number] = item
    ordered_present = sorted(present_by_frame.values(), key=lambda item: item["frame_number"])

    interval_by_present_frame: dict[int, Mapping[str, Any]] = {}
    timing_by_index = {
        to_int(item.get("frame_index")): item
        for item in video_frame_timing
        if to_int(item.get("frame_index")) is not None
    }
    for interval in interval_rows:
        present_index = to_int(interval.get("present_event_index"))
        current = timing_by_index.get(present_index)
        frame_number = None if current is None else to_int(current.get("frame_number"))
        if frame_number is not None:
            interval_by_present_frame[frame_number] = interval

    threshold_ns = None if reference_period_ns is None else reference_period_ns * gap_threshold_periods
    large_gap_ns = int(LARGE_GAP_MS * 1e6)
    submission_burst_window_ns = 1_000_000
    observations: list[dict[str, Any]] = []
    queued_non_present_count = 0
    queued_non_latched_count = 0

    def in_scope(timestamp: int | None) -> bool:
        return (
            timestamp is not None
            and (start_ns is None or timestamp >= start_ns)
            and (end_ns is None or timestamp < end_ns)
        )

    def nearest_previous(frame_number: int) -> dict[str, Any] | None:
        previous = [item for item in ordered_present if item["frame_number"] < frame_number]
        return previous[-1] if previous else None

    def nearest_next(frame_number: int) -> dict[str, Any] | None:
        following = [item for item in ordered_present if item["frame_number"] > frame_number]
        return following[0] if following else None

    for frame_number, stages in sorted(stage_groups.items()):
        stage_names = set(stages)
        set_events = set_buffer_by_frame.get(frame_number, [])
        queue_events = list(stages.get("Queue", []))
        if not queue_events or not set_events:
            continue
        set_timestamp = to_int(set_events[-1].get("ts_ns"))
        if not in_scope(set_timestamp):
            continue
        if "PresentFenceSignaled" in stage_names:
            continue
        queued_non_present_count += 1
        has_latch_stage = "Latch" in stage_names or bool(latch_buffer_by_frame.get(frame_number))
        if has_latch_stage:
            continue
        queued_non_latched_count += 1

        previous_present = nearest_previous(frame_number)
        next_present = nearest_next(frame_number)
        previous_frame_number = None if previous_present is None else previous_present["frame_number"]
        next_frame_number = None if next_present is None else next_present["frame_number"]
        present_interval_ns = (
            None
            if previous_present is None or next_present is None
            else next_present["ts_ns"] - previous_present["ts_ns"]
        )
        frame_number_delta = (
            None
            if previous_frame_number is None or next_frame_number is None
            else next_frame_number - previous_frame_number
        )
        next_timing = timing_by_frame.get(next_frame_number) if next_frame_number is not None else None
        next_stages = set() if next_present is None else set(stage_groups.get(next_frame_number, {}))
        next_present_complete = {"Queue", "AcquireFenceSignaled", "Latch", "PresentFenceSignaled"} <= next_stages
        next_interval = interval_by_present_frame.get(next_frame_number)
        is_small_present_gap = bool(
            next_interval
            and next_interval.get("is_gap_over_threshold")
            and not next_interval.get("is_large_gap")
        )
        is_two_slot_gap = bool(
            previous_present is not None
            and next_present is not None
            and frame_number_delta is not None
            and frame_number_delta >= 2
            and present_interval_ns is not None
            and threshold_ns is not None
            and threshold_ns < present_interval_ns <= large_gap_ns
            and next_present_complete
        )
        next_set_events = [] if next_frame_number is None else set_buffer_by_frame.get(next_frame_number, [])
        submission_burst = any(
            0 <= (to_int(next_event.get("ts_ns"), 0) - to_int(set_event.get("ts_ns"), 0)) <= submission_burst_window_ns
            for set_event in set_events
            for next_event in next_set_events
        )
        if is_two_slot_gap and is_small_present_gap:
            classification = "QUEUED_BUFFER_NOT_LATCHED_BEFORE_PRESENT_GAP"
        else:
            classification = "QUEUED_BUFFER_NOT_LATCHED_OUTSIDE_PRESENT_GAP"
        observations.append(
            {
                "frame_number": frame_number,
                "stage_names": sorted(stage_names),
                "set_buffer_ts_ns": set_timestamp,
                "queue_ts_ns": min(
                    (to_int(item.get("ts")) for item in queue_events if to_int(item.get("ts")) is not None),
                    default=None,
                ),
                "previous_present_frame_number": previous_frame_number,
                "next_present_frame_number": next_frame_number,
                "present_interval_ns": present_interval_ns,
                "frame_number_delta": frame_number_delta,
                "next_present_complete": next_present_complete,
                "next_present_ts_ns": None if next_present is None else next_present["ts_ns"],
                "next_present_interval": None if next_interval is None else dict(next_interval),
                "is_small_present_gap": is_small_present_gap,
                "is_two_slot_gap": is_two_slot_gap,
                "submission_burst_to_next_present": submission_burst,
                "classification": classification,
                "evidence_quality": "EXACT_WITHIN_SF",
                "cross_layer_identity": "UNKNOWN",
            }
        )

    linked_gap_frames = {
        item["next_present_frame_number"]
        for item in observations
        if item["classification"] == "QUEUED_BUFFER_NOT_LATCHED_BEFORE_PRESENT_GAP"
    }
    burst_linked_count = sum(
        item["classification"] == "QUEUED_BUFFER_NOT_LATCHED_BEFORE_PRESENT_GAP"
        and item["submission_burst_to_next_present"]
        for item in observations
    )
    small_gap_frames = {
        frame_number
        for frame_number, interval in interval_by_present_frame.items()
        if interval.get("is_gap_over_threshold") and not interval.get("is_large_gap")
    }
    classification_counts = Counter(item["classification"] for item in observations)
    return {
        "status": "VALID" if observations else "NO_QUEUED_NON_LATCHED_BUFFERS_IN_SCOPE",
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "queued_non_present_count": queued_non_present_count,
        "queued_non_latched_count": queued_non_latched_count,
        "two_slot_gap_candidate_count": sum(item["is_two_slot_gap"] for item in observations),
        "small_gap_with_queued_non_latched_count": len(linked_gap_frames),
        "small_gap_count_in_scope": len(small_gap_frames),
        "small_gap_coverage_ratio": (
            None if not small_gap_frames else len(linked_gap_frames & small_gap_frames) / len(small_gap_frames)
        ),
        "submission_burst_window_ns": submission_burst_window_ns,
        "submission_burst_total_count": sum(
            item["submission_burst_to_next_present"] for item in observations
        ),
        "submission_burst_linked_count": burst_linked_count,
        "classification_counts": dict(classification_counts),
        "observations": observations,
        "evidence_quality": "EXACT_WITHIN_SF",
        "cross_layer_identity_available": False,
        "note": (
            "A queued non-latched observation is exact only within SurfaceFlinger: setBuffer/frame_number "
            "and the selected layer stages agree. It does not identify the upstream MediaCodec or native "
            "buffer. A two-slot present gap with a following submission burst is a final-pipeline candidate, "
            "not by itself proof of the upstream cause."
        ),
    }


def analyze_surface_flinger_queue_phase(
    video_frame_timing: Sequence[Mapping[str, Any]],
    surface_events: Mapping[str, Any],
    producer_queue_events: Mapping[str, Any],
    reference_period_ns: int | None,
    fixed_window: Mapping[str, Any] | None = None,
    stage_groups: Mapping[int, Mapping[str, Sequence[Mapping[str, Any]]]] | None = None,
) -> dict[str, Any]:
    """Compare producer queue cadence with SurfaceFlinger commit phase.

    ``frame_number`` is not available on the producer ``queueBuffer`` slice in
    these traces.  Producer-to-SurfaceFlinger pairing is therefore explicitly
    temporal-only and is never promoted to cross-layer identity.  The useful
    question here is narrower: do frames that are queued but never presented
    have a later SurfaceFlinger phase while the producer-to-SF handoff latency
    stays unchanged?
    """
    stage_groups = stage_groups or {}
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int(
        (fixed_window or {}).get("end_ts_ns_exclusive",
        (fixed_window or {}).get("end_ts_exclusive"))
    )

    def in_scope(timestamp: int | None) -> bool:
        return (
            timestamp is not None
            and (start_ns is None or timestamp >= start_ns)
            and (end_ns is None or timestamp < end_ns)
        )

    def stats(values: Sequence[int]) -> dict[str, Any]:
        ordered = sorted(int(value) for value in values)
        return {
            "count": len(ordered),
            "median_ns": None if not ordered else int(statistics.median(ordered)),
            "p10_ns": None if not ordered else int(percentile(ordered, 0.10)),
            "p90_ns": None if not ordered else int(percentile(ordered, 0.90)),
            "min_ns": None if not ordered else min(ordered),
            "max_ns": None if not ordered else max(ordered),
        }

    commit_timestamps = sorted(
        to_int(item.get("ts_ns"))
        for item in surface_events.get("commit_cycles", [])
        if to_int(item.get("ts_ns")) is not None
    )
    producer_events = [
        dict(item)
        for item in producer_queue_events.get("selected_events", producer_queue_events.get("events", []))
        if to_int(item.get("ts_ns")) is not None
    ]
    producer_events.sort(key=lambda item: to_int(item.get("ts_ns"), 0))
    producer_timestamps = [to_int(item.get("ts_ns"), 0) for item in producer_events]
    presented_frames = {
        to_int(item.get("frame_number"))
        for item in video_frame_timing
        if to_int(item.get("frame_number")) is not None
    }
    queued_frames: list[dict[str, Any]] = []
    for frame_number, stages in sorted(stage_groups.items()):
        queue_timestamps = [
            to_int(item.get("ts"))
            for item in stages.get("Queue", [])
            if to_int(item.get("ts")) is not None
        ]
        if not queue_timestamps:
            continue
        queue_ts = min(queue_timestamps)
        if not in_scope(queue_ts):
            continue
        commit_index = bisect.bisect_left(commit_timestamps, queue_ts)
        previous_commit_ts = (
            commit_timestamps[commit_index - 1] if commit_index > 0 else None
        )
        next_commit_ts = (
            commit_timestamps[commit_index] if commit_index < len(commit_timestamps) else None
        )
        producer_index = bisect.bisect_left(producer_timestamps, queue_ts)
        nearest_index = None
        nearest_delta = None
        for candidate_index in (producer_index - 1, producer_index):
            if 0 <= candidate_index < len(producer_timestamps):
                delta = abs(producer_timestamps[candidate_index] - queue_ts)
                if nearest_delta is None or delta < nearest_delta:
                    nearest_index = candidate_index
                    nearest_delta = delta
        producer_ts = (
            producer_timestamps[nearest_index]
            if nearest_index is not None and nearest_delta is not None and nearest_delta <= 3_000_000
            else None
        )
        queued_frames.append(
            {
                "frame_number": frame_number,
                "queue_ts_ns": queue_ts,
                "presented": frame_number in presented_frames,
                "producer_queue_ts_ns": producer_ts,
                "producer_to_surface_queue_ns": None
                if producer_ts is None else queue_ts - producer_ts,
                "previous_commit_ts_ns": previous_commit_ts,
                "next_commit_ts_ns": next_commit_ts,
                "queue_since_previous_commit_ns": None
                if previous_commit_ts is None else queue_ts - previous_commit_ts,
                "next_commit_minus_queue_ns": None
                if next_commit_ts is None else next_commit_ts - queue_ts,
            }
        )

    matched = [item for item in queued_frames if item["producer_queue_ts_ns"] is not None]
    presented = [item for item in matched if item["presented"]]
    missing = [item for item in matched if not item["presented"]]

    def values(items: Sequence[Mapping[str, Any]], field: str) -> list[int]:
        return [to_int(item.get(field)) for item in items if to_int(item.get(field)) is not None]

    present_phase = stats(values(presented, "queue_since_previous_commit_ns"))
    missing_phase = stats(values(missing, "queue_since_previous_commit_ns"))
    present_handoff = stats(values(presented, "producer_to_surface_queue_ns"))
    missing_handoff = stats(values(missing, "producer_to_surface_queue_ns"))
    phase_delta = (
        None
        if present_phase["median_ns"] is None or missing_phase["median_ns"] is None
        else missing_phase["median_ns"] - present_phase["median_ns"]
    )
    handoff_delta = (
        None
        if present_handoff["median_ns"] is None or missing_handoff["median_ns"] is None
        else missing_handoff["median_ns"] - present_handoff["median_ns"]
    )
    phase_correlated = (
        phase_delta is not None
        and phase_delta > 1_000_000
        and handoff_delta is not None
        and abs(handoff_delta) <= 1_000_000
    )
    return {
        "status": "VALID" if matched and commit_timestamps else "INSUFFICIENT_DATA",
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "producer_stream": producer_queue_events.get("selected_stream"),
        "queued_frame_count": len(queued_frames),
        "temporal_pair_count": len(matched),
        "missing_present_frame_count": len(missing),
        "present_frame_count": len(presented),
        "present_queue_phase": present_phase,
        "missing_present_queue_phase": missing_phase,
        "present_producer_to_surface_queue": present_handoff,
        "missing_present_producer_to_surface_queue": missing_handoff,
        "missing_minus_present_queue_phase_median_ns": phase_delta,
        "missing_minus_present_handoff_median_ns": handoff_delta,
        "conclusion": (
            "UPSTREAM_PRODUCER_CADENCE_PHASE_CORRELATED"
            if phase_correlated
            else "NO_SEPARATING_PHASE_SIGNAL"
        ),
        "observations": queued_frames,
        "evidence_quality": "TEMPORAL_ONLY",
        "cross_layer_identity_available": False,
        "note": (
            "Producer queueBuffer and SurfaceFlinger Queue are matched by trace time only. "
            "A later Queue phase with unchanged producer-to-SurfaceFlinger delay shifts the "
            "candidate toward producer cadence/phase, but does not identify a codec buffer."
        ),
        "reference_period_ns": reference_period_ns,
    }


def analyze_surface_flinger_latch_gaps(
    video_frame_timing: Sequence[Mapping[str, Any]],
    interval_rows: Sequence[Mapping[str, Any]],
    surface_events: Mapping[str, Any],
    reference_period_ns: int | None,
    gap_threshold_periods: float,
    fixed_window: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Classify fixed-window PresentFence gaps against SF commit/latch cycles."""
    commit_cycles = sorted(
        [dict(item) for item in surface_events.get("commit_cycles", []) if to_int(item.get("ts_ns")) is not None],
        key=lambda item: to_int(item.get("ts_ns"), 0),
    )
    commit_timestamps = [to_int(item.get("ts_ns"), 0) for item in commit_cycles]
    should_events = sorted(
        to_int(item.get("ts_ns"))
        for item in surface_events.get("should_latch_unsignaled_events", [])
        if to_int(item.get("ts_ns")) is not None
    )
    unsignaled_events = sorted(
        to_int(item.get("ts_ns"))
        for item in surface_events.get("unsignaled_fence_events", [])
        if to_int(item.get("ts_ns")) is not None
    )
    set_buffer_by_frame = {
        to_int(item.get("frame_number")): to_int(item.get("ts_ns"))
        for item in surface_events.get("set_buffer_events", [])
        if to_int(item.get("frame_number")) is not None and to_int(item.get("ts_ns")) is not None
    }
    timing_by_index = {
        to_int(item.get("frame_index")): item
        for item in video_frame_timing
        if to_int(item.get("frame_index")) is not None
    }
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int((fixed_window or {}).get("end_ts_ns_exclusive"))
    if not commit_timestamps:
        return {
            "status": "INSUFFICIENT_DATA",
            "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
            "gap_count": 0,
            "classification_counts": {},
            "commit_cycle_count": 0,
            "begin_frame_cycle_count": len(surface_events.get("begin_frame_cycles", [])),
            "gap_observations": [],
            "note": "No SurfaceFlinger commit cycles were captured in the trace.",
        }

    def commit_index(timestamp: int | None) -> int | None:
        if timestamp is None:
            return None
        index = bisect.bisect_right(commit_timestamps, timestamp) - 1
        return index if index >= 0 else None

    def event_in_commit(events: Sequence[int], index: int) -> bool:
        start = commit_timestamps[index]
        end = (
            commit_timestamps[index + 1]
            if index + 1 < len(commit_timestamps)
            else start + (reference_period_ns or 0) * 2
        )
        return any(start <= timestamp < end for timestamp in events)

    gap_observations: list[dict[str, Any]] = []
    per_frame: dict[int, dict[str, Any]] = {}
    threshold_ns = None if reference_period_ns is None else reference_period_ns * gap_threshold_periods
    for interval in interval_rows:
        if not interval.get("is_gap_over_threshold") or interval.get("is_large_gap"):
            continue
        present_index = to_int(interval.get("present_event_index"))
        if present_index is None:
            continue
        current = timing_by_index.get(present_index)
        previous = timing_by_index.get(present_index - 1)
        current_ts = None if current is None else to_int(current.get("present_ts_ns"))
        if start_ns is not None and (current_ts is None or current_ts < start_ns):
            continue
        if end_ns is not None and (current_ts is None or current_ts >= end_ns):
            continue
        current_stage = {} if current is None else current.get("stage_join", {}).get("stage_ts_ns", {})
        previous_stage = {} if previous is None else previous.get("stage_join", {}).get("stage_ts_ns", {})
        current_latch = to_int(current_stage.get("Latch"))
        previous_latch = to_int(previous_stage.get("Latch"))
        current_commit = commit_index(current_latch)
        previous_commit = commit_index(previous_latch)
        skipped_indices = (
            list(range(previous_commit + 1, current_commit))
            if previous_commit is not None and current_commit is not None and current_commit > previous_commit + 1
            else []
        )
        skipped = []
        for index in skipped_indices:
            skipped.append(
                {
                    "commit_index": index,
                    "commit_ts_ns": commit_timestamps[index],
                    "vsync_id": commit_cycles[index].get("vsync_id"),
                    "should_latch_unsignaled": event_in_commit(should_events, index),
                    "unsignaled_fence": event_in_commit(unsignaled_events, index),
                    "current_acquire_minus_commit_ns": (
                        None
                        if to_int(current_stage.get("AcquireFenceSignaled")) is None
                        else to_int(current_stage.get("AcquireFenceSignaled")) - commit_timestamps[index]
                    ),
                    "current_queue_minus_commit_ns": (
                        None
                        if to_int(current_stage.get("Queue")) is None
                        else to_int(current_stage.get("Queue")) - commit_timestamps[index]
                    ),
                }
            )
        commit_cycle_gap = (
            None
            if current_commit is None or previous_commit is None
            else current_commit - previous_commit
        )
        commit_interval_ns = (
            None
            if current_commit is None or previous_commit is None
            else commit_timestamps[current_commit] - commit_timestamps[previous_commit]
        )
        has_unsignaled_miss = bool(skipped) and all(
            item["should_latch_unsignaled"] and item["unsignaled_fence"] for item in skipped
        )
        if current_commit is None or previous_commit is None:
            classification = "UNMATCHED_LATCH_COMMIT"
        elif has_unsignaled_miss:
            classification = "LATCH_MISS_UNSIGNALED_ACQUIRE_FENCE"
        elif skipped and any(item["unsignaled_fence"] for item in skipped):
            classification = "LATCH_MISS_PARTIAL_UNSIGNALED_EVIDENCE"
        elif commit_cycle_gap is not None and commit_cycle_gap >= 2:
            classification = "LATCH_CYCLE_GAP_NO_UNSIGNALED_EVIDENCE"
        elif (
            commit_interval_ns is not None
            and threshold_ns is not None
            and commit_interval_ns > threshold_ns
        ):
            classification = "SURFACEFLINGER_COMMIT_CADENCE_GAP"
        else:
            classification = "PRESENT_FENCE_OR_LATCH_MAPPING_GAP"
        frame_number = None if current is None else to_int(current.get("frame_number"))
        acquire_ts = to_int(current_stage.get("AcquireFenceSignaled"))
        set_buffer_ts = set_buffer_by_frame.get(frame_number)
        observation = {
            "present_event_index": present_index,
            "frame_number": frame_number,
            "previous_frame_number": None if previous is None else to_int(previous.get("frame_number")),
            "interval_ns": to_int(interval.get("interval_ns")),
            "current_latch_ts_ns": current_latch,
            "previous_latch_ts_ns": previous_latch,
            "current_commit_index": current_commit,
            "previous_commit_index": previous_commit,
            "current_commit_ts_ns": None if current_commit is None else commit_timestamps[current_commit],
            "previous_commit_ts_ns": None if previous_commit is None else commit_timestamps[previous_commit],
            "commit_cycle_gap": commit_cycle_gap,
            "commit_interval_ns": commit_interval_ns,
            "skipped_commits": skipped,
            "skipped_commit_count": len(skipped),
            "skipped_should_latch_unsignaled_count": sum(
                item["should_latch_unsignaled"] for item in skipped
            ),
            "skipped_unsignaled_fence_count": sum(item["unsignaled_fence"] for item in skipped),
            "acquire_ts_ns": acquire_ts,
            "set_buffer_ts_ns": set_buffer_ts,
            "set_buffer_minus_acquire_ns": (
                None if acquire_ts is None or set_buffer_ts is None else set_buffer_ts - acquire_ts
            ),
            "latch_to_present_ns": (
                None
                if current is None
                else to_int(current.get("stage_join", {}).get("stage_latency_ns", {}).get("latch_to_present"))
            ),
            "classification": classification,
            "evidence_boundary": (
                "SurfaceFlinger commit/latch timing correlation; no shared codec or external-renderer buffer identity."
            ),
        }
        gap_observations.append(observation)
        per_frame[present_index] = observation

    classification_counts = Counter(item["classification"] for item in gap_observations)
    return {
        "status": "VALID" if gap_observations else "NO_GAPS_IN_SCOPE",
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "gap_count": len(gap_observations),
        "classification_counts": dict(classification_counts),
        "commit_cycle_count": len(commit_cycles),
        "begin_frame_cycle_count": len(surface_events.get("begin_frame_cycles", [])),
        "predictor_frame_begin_count": len(surface_events.get("predictor_frame_begin_ts_ns", [])),
        "should_latch_unsignaled_event_count": len(should_events),
        "unsignaled_fence_event_count": len(unsignaled_events),
        "set_buffer_event_count": len(set_buffer_by_frame),
        "latch_buffer_event_count": len(surface_events.get("latch_buffer_events", [])),
        "gap_with_skipped_commit_count": sum(bool(item["skipped_commits"]) for item in gap_observations),
        "gap_with_all_skipped_commits_unsignaled_count": sum(
            item["classification"] == "LATCH_MISS_UNSIGNALED_ACQUIRE_FENCE"
            for item in gap_observations
        ),
        "gap_with_surfaceflinger_commit_cadence_gap_count": sum(
            item["classification"] == "SURFACEFLINGER_COMMIT_CADENCE_GAP"
            for item in gap_observations
        ),
        "gap_observations": gap_observations,
        "note": (
            "A LATCH_MISS_UNSIGNALED_ACQUIRE_FENCE means the skipped commit contained both "
            "shouldLatchUnsignaled=false and a selected-layer unsignaled-fence event. It is strong "
            "SurfaceFlinger evidence, but not a codec-buffer identity proof."
        ),
        "_by_frame_index": per_frame,
    }


def query_renderer_thread_wake_events(tp: Any) -> list[dict[str, Any]]:
    """Read named scheduler wakeups for the external-render thread.

    ``thread_state.waker_utid`` is scheduler evidence, not a Java callback or
    codec identity.  It is retained only to tell whether the renderer thread
    was woken by ``ijk_java_player`` before or after the missing-VSync phase.
    """
    rows = query_rows(
        tp,
        "select s.ts,s.dur,s.state,s.waker_utid,w.name as waker_thread_name "
        "from thread_state s "
        "join thread t on s.utid=t.utid "
        "left join thread w on s.waker_utid=w.utid "
        "where t.name='ijk_ext_render' and s.state like 'R%' "
        "and s.waker_utid is not null order by s.ts",
    )
    events: list[dict[str, Any]] = []
    for row in rows:
        timestamp = to_int(row_value(row, "ts"))
        if timestamp is None:
            continue
        events.append({
            "ts_ns": timestamp,
            "dur_ns": to_int(row_value(row, "dur"), 0) or 0,
            "state": row_value(row, "state"),
            "waker_utid": to_int(row_value(row, "waker_utid")),
            "waker_thread_name": row_value(row, "waker_thread_name"),
        })
    return events


def analyze_frame_receiver_dispatch(
    receiver_summary: Mapping[str, Any],
    renderer_events: Mapping[str, Any],
    reference_period_ns: int | None,
    gap_threshold_periods: float,
    fixed_window: Mapping[str, Any] | None = None,
    clock_offset_ns: int | None = None,
) -> dict[str, Any]:
    """Analyze renderer ``dispatchVsync`` gaps against app VSync and schedules.

    ``DisplayEventReceiver.dispatchVsync`` is the native-to-Java entry point.
    Its trace slice is recorded in BOOTTIME, while the framework's
    ``timestampNanos`` argument uses the ``System.nanoTime`` domain.  The
    optional clock offset lets this report retain both the dispatch latency and
    the actual VSync timestamp without calling either one a panel scanout.
    """
    all_dispatch_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("dispatch_vsync", [])
        if to_int(item.get("ts_ns")) is not None
        and str(item.get("thread_name", "")) == "ijk_ext_render"
    ]
    all_schedule_frame_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("schedule_frame_locked", [])
        if to_int(item.get("ts_ns")) is not None
        and (
            str(item.get("process_name", "")) == "tv.danmaku.bili"
            or (
                not str(item.get("process_name", ""))
                and str(item.get("thread_name", "")) == "ijk_ext_render"
            )
        )
    ]
    all_handler_front_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("handler_front", [])
        if to_int(item.get("ts_ns")) is not None
        and (
            str(item.get("process_name", "")) == "tv.danmaku.bili"
            or (
                not str(item.get("process_name", ""))
                and str(item.get("thread_name", "")) == "ijk_ext_render"
            )
        )
    ]
    all_queue_enqueue_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("queue_enqueue", [])
        if to_int(item.get("ts_ns")) is not None
        and (
            str(item.get("process_name", "")) == "tv.danmaku.bili"
            or (
                not str(item.get("process_name", ""))
                and str(item.get("thread_name", "")) == "ijk_ext_render"
            )
        )
    ]
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int(
        (fixed_window or {}).get("end_ts_ns_exclusive",
                                (fixed_window or {}).get("end_ts_exclusive"))
    )

    def in_scope(timestamp: int) -> bool:
        return start_ns is None or (
            timestamp >= start_ns and (end_ns is None or timestamp < end_ns)
        )

    dispatch_rows = sorted(
        [item for item in all_dispatch_rows if in_scope(to_int(item.get("ts_ns"), 0))],
        key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0),
    )
    schedule_frame_rows = sorted(
        [item for item in all_schedule_frame_rows if in_scope(to_int(item.get("ts_ns"), 0))],
        key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0),
    )
    handler_front_rows = sorted(
        [item for item in all_handler_front_rows if in_scope(to_int(item.get("ts_ns"), 0))],
        key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0),
    )
    queue_enqueue_rows = sorted(
        [item for item in all_queue_enqueue_rows if in_scope(to_int(item.get("ts_ns"), 0))],
        key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0),
    )
    dispatch_timestamps = [to_int(item.get("ts_ns")) for item in dispatch_rows]
    dispatch_timestamps = [timestamp for timestamp in dispatch_timestamps if timestamp is not None]
    dispatch_vsync_by_row = [
        to_int(item.get("vsync_timestamp_ns"))
        for item in dispatch_rows
    ]
    dispatch_vsync_reference_period_ns = median_reference_period_ns(
        timestamp for timestamp in dispatch_vsync_by_row if timestamp is not None
    )
    app_timestamps = [
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("app_on_vsync", [])
        if to_int(item.get("ts_ns")) is not None and in_scope(to_int(item.get("ts_ns"), 0))
    ]
    app_timestamps.sort()
    schedule_timestamps = [
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("vsync_schedule", [])
        if to_int(item.get("ts_ns")) is not None and in_scope(to_int(item.get("ts_ns"), 0))
    ]
    schedule_timestamps.sort()
    schedule_frame_timestamps = [
        to_int(item.get("ts_ns"))
        for item in schedule_frame_rows
        if to_int(item.get("ts_ns")) is not None
    ]
    handler_front_timestamps = [
        to_int(item.get("ts_ns"))
        for item in handler_front_rows
        if to_int(item.get("ts_ns")) is not None
    ]
    queue_enqueue_timestamps = [
        to_int(item.get("ts_ns"))
        for item in queue_enqueue_rows
        if to_int(item.get("ts_ns")) is not None
    ]
    renderer_wake_rows = [
        dict(item)
        for item in renderer_events.get("thread_wake_events", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    renderer_wake_rows = sorted(
        [item for item in renderer_wake_rows if in_scope(to_int(item.get("ts_ns"), 0))],
        key=lambda item: to_int(item.get("ts_ns"), 0),
    )

    threshold_ns = None if reference_period_ns is None else reference_period_ns * gap_threshold_periods
    gap_observations: list[dict[str, Any]] = []
    if threshold_ns is not None:
        for previous_row, current_row in zip(dispatch_rows, dispatch_rows[1:]):
            previous_ts = to_int(previous_row.get("ts_ns"))
            current_ts = to_int(current_row.get("ts_ns"))
            if previous_ts is None or current_ts is None:
                continue
            interval_ns = current_ts - previous_ts
            if interval_ns <= threshold_ns:
                continue
            app_ticks = [timestamp for timestamp in app_timestamps if previous_ts < timestamp < current_ts]
            schedules = [timestamp for timestamp in schedule_timestamps if previous_ts < timestamp < current_ts]
            schedule_frame_ticks = [
                timestamp for timestamp in schedule_frame_timestamps
                if previous_ts < timestamp < current_ts
            ]
            handler_front_ticks = [
                timestamp
                for timestamp in handler_front_timestamps
                if previous_ts < timestamp < current_ts
            ]
            queue_enqueue_ticks = [
                timestamp
                for timestamp in queue_enqueue_timestamps
                if previous_ts < timestamp < current_ts
            ]
            previous_vsync_raw = to_int(previous_row.get("vsync_timestamp_ns"))
            current_vsync_raw = to_int(current_row.get("vsync_timestamp_ns"))
            expected_missing_vsync_trace_timestamps = []
            if (
                clock_offset_ns is not None
                and previous_vsync_raw is not None
                and current_vsync_raw is not None
                and dispatch_vsync_reference_period_ns is not None
            ):
                raw_interval_ns = current_vsync_raw - previous_vsync_raw
                missing_slot_count = max(
                    1,
                    round(raw_interval_ns / dispatch_vsync_reference_period_ns) - 1,
                )
                expected_missing_vsync_trace_timestamps = [
                    previous_vsync_raw
                    + dispatch_vsync_reference_period_ns * slot
                    + clock_offset_ns
                    for slot in range(1, missing_slot_count + 1)
                ]
            renderer_wake_ticks = [
                item for item in renderer_wake_rows
                if previous_ts < to_int(item.get("ts_ns"), 0) < current_ts
            ]
            ijk_java_wake_ticks = [
                item for item in renderer_wake_ticks
                if str(item.get("waker_thread_name", "")) == "ijk_java_player"
            ]
            ijk_java_wake_after_expected = [
                to_int(item.get("ts_ns"), 0)
                for item in ijk_java_wake_ticks
                if any(
                    to_int(item.get("ts_ns"), 0) > expected
                    for expected in expected_missing_vsync_trace_timestamps
                )
            ]
            ijk_java_wake_before_expected = [
                to_int(item.get("ts_ns"), 0)
                for item in ijk_java_wake_ticks
                if expected_missing_vsync_trace_timestamps
                and any(
                    to_int(item.get("ts_ns"), 0) <= expected
                    for expected in expected_missing_vsync_trace_timestamps
                )
            ]
            schedules_after_app = [
                timestamp
                for timestamp in schedules
                if any(app_timestamp < timestamp for app_timestamp in app_ticks)
            ]
            schedule_phase_to_expected_vsync_ns = [
                min(
                    (schedule - expected for expected in expected_missing_vsync_trace_timestamps),
                    key=abs,
                )
                for schedule in schedules
                if expected_missing_vsync_trace_timestamps
            ]
            gap_observations.append({
                "previous_dispatch_ts_ns": previous_ts,
                "dispatch_ts_ns": current_ts,
                "dispatch_interval_ns": interval_ns,
                "is_large_gap": interval_ns > LARGE_GAP_MS * 1e6,
                "app_on_vsync_count": len(app_ticks),
                "app_on_vsync_timestamps_ns": app_ticks,
                "renderer_schedule_count": len(schedules),
                "renderer_schedule_timestamps_ns": schedules,
                "schedule_frame_locked_count": len(schedule_frame_ticks),
                "schedule_frame_locked_timestamps_ns": schedule_frame_ticks,
                "handler_front_count": len(handler_front_ticks),
                "handler_front_timestamps_ns": handler_front_ticks,
                "queue_enqueue_count": len(queue_enqueue_ticks),
                "queue_enqueue_timestamps_ns": queue_enqueue_ticks,
                "renderer_wake_count": len(renderer_wake_ticks),
                "renderer_wake_timestamps_ns": [
                    to_int(item.get("ts_ns"), 0) for item in renderer_wake_ticks
                ],
                "renderer_wake_thread_names": [
                    item.get("waker_thread_name") for item in renderer_wake_ticks
                ],
                "ijk_java_player_wake_count": len(ijk_java_wake_ticks),
                "ijk_java_player_wake_after_expected_count": len(
                    ijk_java_wake_after_expected
                ),
                "ijk_java_player_wake_before_expected_count": len(
                    ijk_java_wake_before_expected
                ),
                "ijk_java_player_wake_after_expected_timestamps_ns": (
                    ijk_java_wake_after_expected
                ),
                "schedule_after_app_on_vsync_count": len(schedules_after_app),
                "schedule_after_app_on_vsync_timestamps_ns": schedules_after_app,
                "previous_vsync_timestamp_ns": previous_vsync_raw,
                "dispatch_vsync_timestamp_ns": current_vsync_raw,
                "expected_missing_vsync_trace_timestamps_ns": expected_missing_vsync_trace_timestamps,
                "schedule_phase_to_expected_vsync_ns": schedule_phase_to_expected_vsync_ns,
                "classification": (
                    "DISPATCH_GAP_SCHEDULE_AFTER_EXPECTED_VSYNC"
                    if any(phase > 0 for phase in schedule_phase_to_expected_vsync_ns)
                    else "DISPATCH_GAP_SCHEDULE_WITHIN_1MS_BEFORE_EXPECTED_VSYNC"
                    if any(-1_000_000 <= phase <= 0 for phase in schedule_phase_to_expected_vsync_ns)
                    else "DISPATCH_GAP_SCHEDULE_AFTER_APP_VSYNC"
                    if app_ticks and schedules_after_app
                    else "DISPATCH_GAP_APP_VSYNC_WITHOUT_RENDERER_SCHEDULE"
                    if app_ticks and not schedules
                    else "DISPATCH_GAP_WITH_RENDERER_SCHEDULE"
                    if schedules
                    else "DISPATCH_GAP_NO_APP_OR_SCHEDULE_WITNESS"
                ),
            })

    dispatch_vsync_timestamps = [
        to_int(item.get("vsync_timestamp_ns"))
        for item in dispatch_rows
        if to_int(item.get("vsync_timestamp_ns")) is not None
    ]
    dispatch_vsync_timestamps.sort()
    dispatch_latency_values = []
    if clock_offset_ns is not None:
        for item in dispatch_rows:
            trace_timestamp = to_int(item.get("ts_ns"))
            vsync_timestamp = to_int(item.get("vsync_timestamp_ns"))
            if trace_timestamp is not None and vsync_timestamp is not None:
                dispatch_latency_values.append(
                    trace_timestamp - (vsync_timestamp + clock_offset_ns)
                )

    return {
        "status": receiver_summary.get("status", "NO_EVENTS"),
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "renderer_dispatch_count": len(dispatch_rows),
        "renderer_schedule_frame_locked_count": len(schedule_frame_rows),
        "renderer_schedule_frame_locked_thread_counts": dict(
            Counter(str(item.get("thread_name")) for item in schedule_frame_rows)
        ),
        "renderer_handler_front_count": len(handler_front_rows),
        "renderer_handler_front_thread_counts": dict(
            Counter(str(item.get("thread_name")) for item in handler_front_rows)
        ),
        "renderer_queue_enqueue_count": len(queue_enqueue_rows),
        "renderer_queue_enqueue_thread_counts": dict(
            Counter(str(item.get("thread_name")) for item in queue_enqueue_rows)
        ),
        "renderer_wake_event_count": len(renderer_wake_rows),
        "renderer_wake_thread_counts": dict(
            Counter(str(item.get("waker_thread_name")) for item in renderer_wake_rows)
        ),
        "renderer_ijk_java_player_wake_count": sum(
            str(item.get("waker_thread_name", "")) == "ijk_java_player"
            for item in renderer_wake_rows
        ),
        "renderer_ijk_java_player_wake_after_expected_count": sum(
            any(
                to_int(item.get("ts_ns"), 0) > expected
                for expected in gap.get("expected_missing_vsync_trace_timestamps_ns", [])
            )
            for gap in gap_observations
            for item in renderer_wake_rows
            if gap["previous_dispatch_ts_ns"] < to_int(item.get("ts_ns"), 0)
            < gap["dispatch_ts_ns"]
            and str(item.get("waker_thread_name", "")) == "ijk_java_player"
        ),
        "renderer_ijk_java_player_wake_before_or_at_expected_count": sum(
            any(
                to_int(item.get("ts_ns"), 0) <= expected
                for expected in gap.get("expected_missing_vsync_trace_timestamps_ns", [])
            )
            for gap in gap_observations
            for item in renderer_wake_rows
            if gap["previous_dispatch_ts_ns"] < to_int(item.get("ts_ns"), 0)
            < gap["dispatch_ts_ns"]
            and str(item.get("waker_thread_name", "")) == "ijk_java_player"
        ),
        "dispatch_gap_count": len(gap_observations),
        "dispatch_gaps_with_app_vsync": sum(
            item["app_on_vsync_count"] > 0 for item in gap_observations
        ),
        "dispatch_gaps_with_schedule_after_app_vsync": sum(
            item["schedule_after_app_on_vsync_count"] > 0 for item in gap_observations
        ),
        "dispatch_gaps_with_schedule_after_expected_vsync": sum(
            any(phase > 0 for phase in item["schedule_phase_to_expected_vsync_ns"])
            for item in gap_observations
        ),
        "dispatch_gaps_with_schedule_within_1ms_before_expected_vsync": sum(
            any(-1_000_000 <= phase <= 0 for phase in item["schedule_phase_to_expected_vsync_ns"])
            for item in gap_observations
        ),
        "dispatch_gap_classification_counts": dict(
            Counter(item["classification"] for item in gap_observations)
        ),
        "dispatch_gap_observations": gap_observations,
        "dispatch_interval_summary": interval_summary(
            dispatch_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "schedule_frame_locked_interval_summary": interval_summary(
            schedule_frame_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "handler_front_interval_summary": interval_summary(
            handler_front_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "queue_enqueue_interval_summary": interval_summary(
            queue_enqueue_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "dispatch_vsync_timestamp_interval_summary": interval_summary(
            dispatch_vsync_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "dispatch_vsync_reference_period_ns": dispatch_vsync_reference_period_ns,
        "dispatch_latency": _telemetry_interval_stats(dispatch_latency_values),
        "clock_offset_boottime_minus_monotonic_ns": clock_offset_ns,
        "note": (
            "A dispatch gap with an app-process VSync and a renderer schedule after that VSync "
            "locates a one-shot schedule miss at the renderer scheduling boundary. It is not a "
            "panel scanout measurement and does not by itself identify codec-buffer identity."
        ),
    }


def analyze_native_display_trace(
    native_display_summary: Mapping[str, Any],
    receiver_dispatch_analysis: Mapping[str, Any],
    display_callback_summary: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Place native display sections against renderer dispatch gaps.

    This is a boundary analysis, not a buffer-identity join.  A native section
    that crosses an expected missing VSync is evidence that the native display
    wrapper remained active across that scheduling boundary.  It does not by
    itself identify which frame was later presented.
    """
    native_rows = [
        dict(item)
        for item in native_display_summary.get("events", {}).get("native_display", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    call_rows = [
        dict(item)
        for item in native_display_summary.get("events", {}).get("native_display_call", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    native_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    call_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    binder_rows = [
        dict(item)
        for item in native_display_summary.get("events", {}).get(
            "binder_display_callback", []
        )
        if to_int(item.get("ts_ns")) is not None
    ]
    binder_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    callback_rows = [
        dict(item)
        for item in (display_callback_summary or {}).get("events", {}).get(
            "display_callback", []
        )
        if to_int(item.get("ts_ns")) is not None
    ]
    callback_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    gaps = list(receiver_dispatch_analysis.get("dispatch_gap_observations", []))
    observations: list[dict[str, Any]] = []
    transport_deltas: list[int] = []

    def rows_between(rows: Sequence[Mapping[str, Any]], start: int, end: int) -> list[dict[str, Any]]:
        return [
            dict(row)
            for row in rows
            if start < to_int(row.get("ts_ns"), start - 1) < end
        ]

    for gap in gaps:
        previous_ts = to_int(gap.get("previous_dispatch_ts_ns"))
        current_ts = to_int(gap.get("dispatch_ts_ns"))
        if previous_ts is None or current_ts is None:
            continue
        native_in_gap = rows_between(native_rows, previous_ts, current_ts)
        call_in_gap = rows_between(call_rows, previous_ts, current_ts)
        binder_in_gap = rows_between(binder_rows, previous_ts, current_ts)
        callback_in_gap = rows_between(callback_rows, previous_ts, current_ts)
        expected = [
            to_int(value)
            for value in gap.get("expected_missing_vsync_trace_timestamps_ns", [])
            if to_int(value) is not None
        ]

        def crosses_expected(row: Mapping[str, Any]) -> bool:
            start = to_int(row.get("ts_ns"))
            end = to_int(row.get("end_ts_ns"), start or 0)
            return start is not None and any(start <= target < end for target in expected)

        def started_before_expected(row: Mapping[str, Any]) -> bool:
            start = to_int(row.get("ts_ns"))
            return start is not None and any(start <= target for target in expected)

        call_durations = [
            max(0, to_int(row.get("dur_ns"), 0) or 0)
            for row in call_in_gap
        ]
        callback_timestamps = [
            to_int(row.get("ts_ns"), 0) for row in callback_in_gap
        ]
        used_callback_indices: set[int] = set()
        ordered_transport_pairs: list[tuple[Mapping[str, Any], Mapping[str, Any]]] = []
        for binder_row in binder_in_gap:
            binder_ts = to_int(binder_row.get("ts_ns"), 0)
            callback_index = bisect.bisect_left(callback_timestamps, binder_ts)
            while callback_index in used_callback_indices:
                callback_index += 1
            if callback_index >= len(callback_in_gap):
                continue
            callback_row = callback_in_gap[callback_index]
            callback_delta = to_int(callback_row.get("ts_ns"), 0) - binder_ts
            if callback_delta < 0 or callback_delta > 50_000_000:
                continue
            used_callback_indices.add(callback_index)
            ordered_transport_pairs.append((binder_row, callback_row))
        for binder_row, callback_row in ordered_transport_pairs:
            transport_deltas.append(
                to_int(callback_row.get("ts_ns"), 0)
                - to_int(binder_row.get("ts_ns"), 0)
            )
        binder_before_expected = bool(
            expected and any(
                to_int(row.get("ts_ns"), current_ts) <= expected[0]
                for row in binder_in_gap
            )
        )
        callback_after_expected = bool(
            expected and any(
                to_int(row.get("ts_ns"), previous_ts) > expected[0]
                for row in callback_in_gap
            )
        )
        ordered_binder_before_callback_after = any(
            expected
            and to_int(binder_row.get("ts_ns"), current_ts) <= expected[0]
            and to_int(callback_row.get("ts_ns"), previous_ts) > expected[0]
            for binder_row, callback_row in ordered_transport_pairs
        )
        observations.append({
            "previous_dispatch_ts_ns": previous_ts,
            "dispatch_ts_ns": current_ts,
            "expected_missing_vsync_trace_timestamps_ns": expected,
            "native_display_count": len(native_in_gap),
            "native_display_tokens": [row.get("token") for row in native_in_gap],
            "native_display_serials": [row.get("serial") for row in native_in_gap],
            "native_display_started_before_expected_count": sum(
                started_before_expected(row) for row in native_in_gap
            ),
            "native_display_crosses_expected_count": sum(
                crosses_expected(row) for row in native_in_gap
            ),
            "native_display_call_count": len(call_in_gap),
            "native_display_call_tokens": [row.get("token") for row in call_in_gap],
            "native_display_call_durations_ns": call_durations,
            "native_display_call_crosses_expected_count": sum(
                crosses_expected(row) for row in call_in_gap
            ),
            "native_display_call_ge_1ms_count": sum(
                duration >= 1_000_000 for duration in call_durations
            ),
            "native_display_call_ge_2_42ms_count": sum(
                duration >= 2_420_000 for duration in call_durations
            ),
            "binder_display_callback_count": len(binder_in_gap),
            "binder_display_callback_before_expected": binder_before_expected,
            "display_callback_count": len(callback_in_gap),
            "display_callback_after_expected": callback_after_expected,
            "ordered_binder_callback_pair_count": len(ordered_transport_pairs),
            "ordered_binder_before_callback_after_expected": ordered_binder_before_callback_after,
            "classification": (
                "NATIVE_DISPLAY_CALL_CROSSES_EXPECTED_VSYNC"
                if any(crosses_expected(row) for row in call_in_gap)
                else "BINDER_CALLBACK_CROSSES_EXPECTED_VSYNC"
                if ordered_binder_before_callback_after
                else "NATIVE_DISPLAY_ENTRY_BEFORE_EXPECTED_VSYNC"
                if any(started_before_expected(row) for row in native_in_gap)
                else "NATIVE_DISPLAY_AFTER_EXPECTED_VSYNC"
                if native_in_gap and expected
                else "NATIVE_DISPLAY_NO_EXPECTED_VSYNC"
                if native_in_gap
                else "NO_NATIVE_DISPLAY_IN_DISPATCH_GAP"
            ),
        })

    all_call_durations = [
        max(0, to_int(row.get("dur_ns"), 0) or 0) for row in call_rows
    ]
    return {
        "status": native_display_summary.get("status", "NO_EVENTS"),
        "source": native_display_summary.get("source"),
        "native_display_count": len(native_rows),
        "native_display_call_count": len(call_rows),
        "binder_display_callback_count": len(binder_rows),
        "display_callback_count": len(callback_rows),
        "native_display_thread_counts": native_display_summary.get("thread_counts", {}),
        "dispatch_gap_count": len(observations),
        "dispatch_gaps_with_native_display": sum(
            item["native_display_count"] > 0 for item in observations
        ),
        "dispatch_gaps_native_display_started_before_expected": sum(
            item["native_display_started_before_expected_count"] > 0
            for item in observations
        ),
        "dispatch_gaps_native_display_crossing_expected": sum(
            item["native_display_crosses_expected_count"] > 0
            for item in observations
        ),
        "dispatch_gaps_native_call_crossing_expected": sum(
            item["native_display_call_crosses_expected_count"] > 0
            for item in observations
        ),
        "dispatch_gaps_native_call_ge_1ms": sum(
            item["native_display_call_ge_1ms_count"] > 0 for item in observations
        ),
        "dispatch_gaps_native_call_ge_2_42ms": sum(
            item["native_display_call_ge_2_42ms_count"] > 0 for item in observations
        ),
        "dispatch_gaps_with_binder_display_callback": sum(
            item["binder_display_callback_count"] > 0 for item in observations
        ),
        "dispatch_gaps_binder_before_expected": sum(
            item["binder_display_callback_before_expected"] for item in observations
        ),
        "dispatch_gaps_display_callback_after_expected": sum(
            item["display_callback_after_expected"] for item in observations
        ),
        "dispatch_gaps_ordered_binder_before_callback_after": sum(
            item["ordered_binder_before_callback_after_expected"]
            for item in observations
        ),
        "ordered_binder_callback_transport": _telemetry_interval_stats(transport_deltas),
        "native_display_call_duration": _telemetry_interval_stats(all_call_durations),
        "gap_classification_counts": dict(
            Counter(item["classification"] for item in observations)
        ),
        "dispatch_gap_observations": observations,
        "identity_boundary": (
            "Native ATrace sections share the Perfetto thread/timestamp domain with Binder and renderer "
            "markers. The serial/PTS annotation remains latest-queue telemetry, not a shared buffer key."
        ),
    }


def analyze_surface_texture_handoff(
    surface_texture_summary: Mapping[str, Any],
    receiver_dispatch_analysis: Mapping[str, Any],
    video_gap_observations: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    """Compare SurfaceTexture frame-ready callbacks with dispatch and video gaps."""
    rows = [
        dict(item)
        for item in surface_texture_summary.get("events", {}).get(
            "surface_texture_callback", []
        )
        if to_int(item.get("ts_ns")) is not None
    ]
    rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    timestamps = [to_int(item.get("ts_ns")) for item in rows]
    timestamps = [timestamp for timestamp in timestamps if timestamp is not None]
    gaps = list(receiver_dispatch_analysis.get("dispatch_gap_observations", []))
    remaining_video_gaps = list(video_gap_observations or [])
    observations: list[dict[str, Any]] = []
    for gap in gaps:
        previous_ts = to_int(gap.get("previous_dispatch_ts_ns"))
        current_ts = to_int(gap.get("dispatch_ts_ns"))
        if previous_ts is None or current_ts is None:
            continue
        callbacks = [
            row for row in rows
            if previous_ts < to_int(row.get("ts_ns"), previous_ts - 1) < current_ts
        ]
        expected = [
            to_int(value)
            for value in gap.get("expected_missing_vsync_trace_timestamps_ns", [])
            if to_int(value) is not None
        ]
        video_gap_match = None
        if remaining_video_gaps:
            candidates = [
                (
                    abs(to_int(item.get("renderer_do_frame_ts_ns"), 0) - current_ts),
                    index,
                    item,
                )
                for index, item in enumerate(remaining_video_gaps)
                if to_int(item.get("renderer_do_frame_ts_ns")) is not None
                and abs(to_int(item.get("renderer_do_frame_ts_ns")) - current_ts)
                <= SURFACE_TEXTURE_VIDEO_GAP_MATCH_LIMIT_NS
            ]
            if candidates:
                _, index, matched_gap = min(candidates, key=lambda item: item[0])
                remaining_video_gaps.pop(index)
                video_gap_match = {
                    "present_event_index": matched_gap.get("present_event_index"),
                    "frame_number": matched_gap.get("frame_number"),
                    "queue_ts_ns": matched_gap.get("queue_ts_ns"),
                    "renderer_do_frame_ts_ns": matched_gap.get("renderer_do_frame_ts_ns"),
                    "renderer_do_frame_gap": matched_gap.get("renderer_do_frame_gap"),
                    "match_delta_ns": to_int(matched_gap.get("renderer_do_frame_ts_ns"), 0)
                    - current_ts,
                }
        observations.append({
            "previous_dispatch_ts_ns": previous_ts,
            "dispatch_ts_ns": current_ts,
            "expected_missing_vsync_trace_timestamps_ns": expected,
            "surface_texture_callback_count": len(callbacks),
            "surface_texture_callback_timestamps_ns": [
                row.get("ts_ns") for row in callbacks
            ],
            "surface_texture_callback_before_expected_count": sum(
                1
                for row in callbacks
                if expected and to_int(row.get("ts_ns"), current_ts) <= expected[0]
            ),
            "surface_texture_callback_after_expected_count": sum(
                1
                for row in callbacks
                if expected and to_int(row.get("ts_ns"), previous_ts) > expected[0]
            ),
            "surface_texture_callback_crosses_expected_count": sum(
                1
                for row in callbacks
                if expected
                and to_int(row.get("ts_ns"), current_ts) <= expected[0]
                < to_int(row.get("end_ts_ns"), to_int(row.get("ts_ns"), 0))
            ),
            "video_gap_match": video_gap_match,
            "classification": (
                "SURFACE_TEXTURE_CALLBACK_AFTER_EXPECTED_VSYNC"
                if expected and any(
                    to_int(row.get("ts_ns"), previous_ts) > expected[0]
                    for row in callbacks
                )
                else "SURFACE_TEXTURE_CALLBACK_BEFORE_EXPECTED_VSYNC"
                if expected and any(
                    to_int(row.get("ts_ns"), current_ts) <= expected[0]
                    for row in callbacks
                )
                else "SURFACE_TEXTURE_CALLBACK_NO_EXPECTED_VSYNC"
                if callbacks
                else "NO_SURFACE_TEXTURE_CALLBACK_IN_DISPATCH_GAP"
            ),
        })
    intervals = [
        current - previous
        for previous, current in zip(timestamps, timestamps[1:])
        if current > previous
    ]
    durations = [max(0, to_int(row.get("dur_ns"), 0) or 0) for row in rows]
    return {
        "status": surface_texture_summary.get("status", "NO_EVENTS"),
        "source": surface_texture_summary.get("source"),
        "surface_texture_callback_count": len(rows),
        "surface_texture_thread_counts": surface_texture_summary.get("thread_counts", {}),
        "dispatch_gap_count": len(observations),
        "dispatch_gaps_with_surface_texture_callback": sum(
            item["surface_texture_callback_count"] > 0 for item in observations
        ),
        "dispatch_gaps_surface_texture_before_expected": sum(
            item["surface_texture_callback_before_expected_count"] > 0
            for item in observations
        ),
        "dispatch_gaps_surface_texture_after_expected": sum(
            item["surface_texture_callback_after_expected_count"] > 0
            for item in observations
        ),
        "dispatch_gaps_surface_texture_crossing_expected": sum(
            item["surface_texture_callback_crosses_expected_count"] > 0
            for item in observations
        ),
        "dispatch_gaps_temporally_matched_to_video_gap": sum(
            item["video_gap_match"] is not None for item in observations
        ),
        "video_gaps_unmatched_to_dispatch_gap": len(remaining_video_gaps),
        "video_gap_matches_surface_texture_before_expected": sum(
            item["video_gap_match"] is not None
            and item["surface_texture_callback_before_expected_count"] > 0
            for item in observations
        ),
        "video_gap_matches_surface_texture_after_expected": sum(
            item["video_gap_match"] is not None
            and item["surface_texture_callback_after_expected_count"] > 0
            for item in observations
        ),
        "video_gap_matches_surface_texture_crossing_expected": sum(
            item["video_gap_match"] is not None
            and item["surface_texture_callback_crosses_expected_count"] > 0
            for item in observations
        ),
        "surface_texture_interval": _telemetry_interval_stats(intervals),
        "surface_texture_callback_duration": _telemetry_interval_stats(durations),
        "gap_classification_counts": dict(
            Counter(item["classification"] for item in observations)
        ),
        "dispatch_gap_observations": observations,
        "identity_boundary": surface_texture_summary.get("identity_boundary"),
    }


def analyze_surface_texture_probe_handoff(
    probe_summary: Mapping[str, Any],
    receiver_dispatch_analysis: Mapping[str, Any],
    video_gap_observations: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    """Use object-tokenized ready/update markers to close the SurfaceTexture seam."""
    ready_rows = [
        dict(item)
        for item in probe_summary.get("events", {}).get("frame_ready", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    update_rows = [
        dict(item)
        for item in probe_summary.get("events", {}).get("update", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    timestamp_rows = [
        dict(item)
        for item in probe_summary.get("events", {}).get("update_timestamp", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    ready_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    update_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    timestamp_rows.sort(key=lambda item: (to_int(item.get("ts_ns"), 0), item.get("slice_id") or 0))
    updates_by_token: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in update_rows:
        token = to_int(row.get("token"))
        if token is not None:
            updates_by_token[token].append(row)
    ready_update_rows: list[dict[str, Any]] = []
    update_indices_by_token: dict[int, int] = defaultdict(int)
    for ready in ready_rows:
        token = to_int(ready.get("token"))
        ready_ts = to_int(ready.get("ts_ns"))
        if token is None or ready_ts is None:
            continue
        token_updates = updates_by_token.get(token, [])
        update_index = update_indices_by_token[token]
        while update_index < len(token_updates):
            candidate_ts = to_int(token_updates[update_index].get("ts_ns"))
            if candidate_ts is not None and candidate_ts >= ready_ts:
                break
            update_index += 1
        first_update = token_updates[update_index] if update_index < len(token_updates) else None
        if first_update is not None:
            update_indices_by_token[token] = update_index + 1
        ready_update_rows.append({
            "token": token,
            "frame_ready_ts_ns": ready_ts,
            "update_ts_ns": None if first_update is None else first_update.get("ts_ns"),
            "update_end_ts_ns": None if first_update is None else first_update.get("end_ts_ns"),
            "ready_to_update_ns": (
                None if first_update is None
                else to_int(first_update.get("ts_ns"), ready_ts) - ready_ts
            ),
            "update_thread_name": None if first_update is None else first_update.get("thread_name"),
        })

    gaps = list(receiver_dispatch_analysis.get("dispatch_gap_observations", []))
    remaining_video_gaps = list(video_gap_observations or [])
    observations: list[dict[str, Any]] = []
    for gap in gaps:
        previous_ts = to_int(gap.get("previous_dispatch_ts_ns"))
        current_ts = to_int(gap.get("dispatch_ts_ns"))
        if previous_ts is None or current_ts is None:
            continue
        expected = [
            to_int(value)
            for value in gap.get("expected_missing_vsync_trace_timestamps_ns", [])
            if to_int(value) is not None
        ]
        ready = [
            row for row in ready_rows
            if previous_ts < to_int(row.get("ts_ns"), previous_ts - 1) < current_ts
        ]
        updates = [
            row for row in update_rows
            if previous_ts < to_int(row.get("ts_ns"), previous_ts - 1) < current_ts
        ]
        update_timestamps = [
            row for row in timestamp_rows
            if previous_ts < to_int(row.get("ts_ns"), previous_ts - 1) < current_ts
        ]
        expected_ts = expected[0] if expected else None
        ready_before = [
            row for row in ready
            if expected_ts is not None and to_int(row.get("ts_ns"), current_ts) <= expected_ts
        ]
        ready_after = [
            row for row in ready
            if expected_ts is not None and to_int(row.get("ts_ns"), previous_ts) > expected_ts
        ]
        update_before = [
            row for row in updates
            if expected_ts is not None and to_int(row.get("ts_ns"), current_ts) <= expected_ts
        ]
        update_after = [
            row for row in updates
            if expected_ts is not None and to_int(row.get("ts_ns"), previous_ts) > expected_ts
        ]
        timestamp_before = [
            row for row in update_timestamps
            if expected_ts is not None and to_int(row.get("ts_ns"), current_ts) <= expected_ts
        ]
        timestamp_after = [
            row for row in update_timestamps
            if expected_ts is not None and to_int(row.get("ts_ns"), previous_ts) > expected_ts
        ]
        ready_update_pairs = [
            row for row in ready_update_rows
            if previous_ts < to_int(row.get("frame_ready_ts_ns"), previous_ts - 1) < current_ts
            and to_int(row.get("update_ts_ns")) is not None
        ]
        paired_update_before = [
            row for row in ready_update_pairs
            if expected_ts is not None
            and to_int(row.get("update_ts_ns"), current_ts) <= expected_ts
        ]
        paired_update_after = [
            row for row in ready_update_pairs
            if expected_ts is not None
            and to_int(row.get("update_ts_ns"), previous_ts) > expected_ts
        ]
        video_gap_match = None
        if remaining_video_gaps:
            candidates = [
                (
                    abs(to_int(item.get("renderer_do_frame_ts_ns"), 0) - current_ts),
                    index,
                    item,
                )
                for index, item in enumerate(remaining_video_gaps)
                if to_int(item.get("renderer_do_frame_ts_ns")) is not None
                and abs(to_int(item.get("renderer_do_frame_ts_ns")) - current_ts)
                <= SURFACE_TEXTURE_VIDEO_GAP_MATCH_LIMIT_NS
            ]
            if candidates:
                _, index, matched_gap = min(candidates, key=lambda item: item[0])
                remaining_video_gaps.pop(index)
                video_gap_match = {
                    "present_event_index": matched_gap.get("present_event_index"),
                    "frame_number": matched_gap.get("frame_number"),
                    "queue_ts_ns": matched_gap.get("queue_ts_ns"),
                    "renderer_do_frame_ts_ns": matched_gap.get("renderer_do_frame_ts_ns"),
                    "renderer_do_frame_gap": matched_gap.get("renderer_do_frame_gap"),
                    "match_delta_ns": to_int(matched_gap.get("renderer_do_frame_ts_ns"), 0)
                    - current_ts,
                }
                if ready_update_pairs:
                    paired = min(
                        ready_update_pairs,
                        key=lambda item: abs(
                            to_int(item.get("update_ts_ns"), current_ts)
                            - to_int(matched_gap.get("queue_ts_ns"), current_ts)
                        ),
                    )
                    update_ts = to_int(paired.get("update_ts_ns"))
                    queue_ts = to_int(matched_gap.get("queue_ts_ns"))
                    do_frame_ts = to_int(matched_gap.get("renderer_do_frame_ts_ns"))
                    video_gap_match.update({
                        "expected_vsync_ts_ns": expected[0] if expected else None,
                        "frame_ready_ts_ns": paired.get("frame_ready_ts_ns"),
                        "ready_update_ts_ns": update_ts,
                        "ready_update_to_queue_ns": (
                            None if update_ts is None or queue_ts is None else queue_ts - update_ts
                        ),
                        "do_frame_to_ready_update_ns": (
                            None
                            if update_ts is None or do_frame_ts is None
                            else update_ts - do_frame_ts
                        ),
                        "expected_to_ready_update_ns": (
                            None
                            if update_ts is None or not expected
                            else update_ts - expected[0]
                        ),
                    })
        observations.append({
            "previous_dispatch_ts_ns": previous_ts,
            "dispatch_ts_ns": current_ts,
            "expected_missing_vsync_trace_timestamps_ns": expected,
            "frame_ready_count": len(ready),
            "frame_ready_tokens": [row.get("token") for row in ready],
            "frame_ready_timestamps_ns": [row.get("ts_ns") for row in ready],
            "update_count": len(updates),
            "update_tokens": [row.get("token") for row in updates],
            "update_timestamps_ns": [row.get("ts_ns") for row in updates],
            "update_timestamp_count": len(update_timestamps),
            "update_timestamp_tokens": [row.get("token") for row in update_timestamps],
            "update_timestamp_values_ns": [
                row.get("producer_timestamp_ns") for row in update_timestamps
            ],
            "frame_ready_before_expected_count": len(ready_before),
            "frame_ready_after_expected_count": len(ready_after),
            "update_before_expected_count": len(update_before),
            "update_after_expected_count": len(update_after),
            "update_timestamp_before_expected_count": len(timestamp_before),
            "update_timestamp_after_expected_count": len(timestamp_after),
            "ready_update_pair_count": len(ready_update_pairs),
            "ready_update_pairs": ready_update_pairs,
            "ready_update_pair_before_expected_count": len(paired_update_before),
            "ready_update_pair_after_expected_count": len(paired_update_after),
            "update_crosses_expected_count": sum(
                1
                for row in updates
                if expected_ts is not None
                and to_int(row.get("ts_ns"), current_ts) <= expected_ts
                < to_int(row.get("end_ts_ns"), to_int(row.get("ts_ns"), 0))
            ),
            "video_gap_match": video_gap_match,
            "classification": (
                "SURFACE_TEXTURE_UPDATE_AFTER_EXPECTED_VSYNC"
                if update_after
                else "SURFACE_TEXTURE_READY_AFTER_EXPECTED_VSYNC"
                if ready_after
                else "SURFACE_TEXTURE_READY_BEFORE_EXPECTED_VSYNC"
                if ready_before
                else "SURFACE_TEXTURE_NO_EXPECTED_VSYNC"
                if ready or updates
                else "NO_SURFACE_TEXTURE_PROBE_IN_DISPATCH_GAP"
            ),
        })
    ready_to_update = [
        to_int(row.get("ready_to_update_ns"))
        for row in ready_update_rows
        if to_int(row.get("ready_to_update_ns")) is not None
        and to_int(row.get("ready_to_update_ns")) >= 0
    ]
    producer_timestamps_by_token: dict[int, list[int]] = defaultdict(list)
    for row in timestamp_rows:
        token = to_int(row.get("token"))
        timestamp = to_int(row.get("producer_timestamp_ns"))
        if token is not None and timestamp is not None:
            producer_timestamps_by_token[token].append(timestamp)
    primary_token = None
    if producer_timestamps_by_token:
        primary_token = max(
            producer_timestamps_by_token,
            key=lambda token: (
                len(set(producer_timestamps_by_token[token])),
                len(producer_timestamps_by_token[token]),
                -token,
            ),
        )
    producer_timestamps = (
        producer_timestamps_by_token.get(primary_token, [])
        if primary_token is not None else []
    )
    producer_intervals = [
        current - previous
        for previous, current in zip(producer_timestamps, producer_timestamps[1:])
        if current > previous
    ]
    producer_timestamp_by_token: dict[str, dict[str, Any]] = {}
    timestamp_groups: dict[int, list[int]] = defaultdict(list)
    for row in timestamp_rows:
        token = to_int(row.get("token"))
        timestamp = to_int(row.get("producer_timestamp_ns"))
        if token is not None and timestamp is not None:
            timestamp_groups[token].append(timestamp)
    for token, values in sorted(timestamp_groups.items()):
        positive_intervals = [
            current - previous
            for previous, current in zip(values, values[1:])
            if current > previous
        ]
        producer_timestamp_by_token[str(token)] = {
            "sample_count": len(values),
            "distinct_count": len(set(values)),
            "first_timestamp_ns": values[0] if values else None,
            "last_timestamp_ns": values[-1] if values else None,
            "interval": _telemetry_interval_stats(positive_intervals),
        }
    matched_video_rows = [
        item["video_gap_match"]
        for item in observations
        if item.get("video_gap_match") is not None
    ]
    return {
        "status": probe_summary.get("status", "NO_EVENTS"),
        "source": probe_summary.get("source"),
        "frame_ready_count": len(ready_rows),
        "update_count": len(update_rows),
        "update_timestamp_count": len(timestamp_rows),
        "producer_timestamp_count": len(producer_timestamps),
        "producer_timestamp_distinct_count": len(set(producer_timestamps)),
        "producer_timestamp_token": primary_token,
        "producer_timestamp_interval": _telemetry_interval_stats(producer_intervals),
        "producer_timestamp_by_token": producer_timestamp_by_token,
        "token_ready_update_match_count": len(ready_to_update),
        "token_ready_without_update_count": len(ready_rows) - len(ready_to_update),
        "ready_to_update_interval": _telemetry_interval_stats(ready_to_update),
        "dispatch_gap_count": len(observations),
        "dispatch_gaps_with_frame_ready": sum(item["frame_ready_count"] > 0 for item in observations),
        "dispatch_gaps_with_update": sum(item["update_count"] > 0 for item in observations),
        "dispatch_gaps_frame_ready_before_expected": sum(
            item["frame_ready_before_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_frame_ready_after_expected": sum(
            item["frame_ready_after_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_update_before_expected": sum(
            item["update_before_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_update_after_expected": sum(
            item["update_after_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_update_timestamp_before_expected": sum(
            item["update_timestamp_before_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_update_timestamp_after_expected": sum(
            item["update_timestamp_after_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_ready_update_pair_before_expected": sum(
            item["ready_update_pair_before_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_ready_update_pair_after_expected": sum(
            item["ready_update_pair_after_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_update_crossing_expected": sum(
            item["update_crosses_expected_count"] > 0 for item in observations
        ),
        "dispatch_gaps_temporally_matched_to_video_gap": sum(
            item["video_gap_match"] is not None for item in observations
        ),
        "video_gaps_unmatched_to_dispatch_gap": len(remaining_video_gaps),
        "video_gap_matches_ready_update_after_expected": sum(
            to_int(item.get("expected_to_ready_update_ns"), 0) > 0
            for item in matched_video_rows
            if item.get("expected_to_ready_update_ns") is not None
        ),
        "video_gap_ready_update_to_queue": _telemetry_interval_stats(
            [
                to_int(item.get("ready_update_to_queue_ns"))
                for item in matched_video_rows
                if to_int(item.get("ready_update_to_queue_ns")) is not None
            ]
        ),
        "video_gap_do_frame_to_ready_update": _telemetry_interval_stats(
            [
                to_int(item.get("do_frame_to_ready_update_ns"))
                for item in matched_video_rows
                if to_int(item.get("do_frame_to_ready_update_ns")) is not None
            ]
        ),
        "video_gap_expected_to_ready_update": _telemetry_interval_stats(
            [
                to_int(item.get("expected_to_ready_update_ns"))
                for item in matched_video_rows
                if to_int(item.get("expected_to_ready_update_ns")) is not None
            ]
        ),
        "gap_classification_counts": dict(
            Counter(item["classification"] for item in observations)
        ),
        "ready_update_observations": ready_update_rows,
        "dispatch_gap_observations": observations,
        "identity_boundary": probe_summary.get("identity_boundary"),
    }


def analyze_renderer_cadence(
    video_frame_timing: Sequence[Mapping[str, Any]],
    interval_rows: Sequence[Mapping[str, Any]],
    renderer_events: Mapping[str, Any],
    reference_period_ns: int | None,
    gap_threshold_periods: float,
    fixed_window: Mapping[str, Any] | None = None,
    receiver_clock_offset_ns: int | None = None,
) -> dict[str, Any]:
    """Compare renderer-thread VSync/render cadence with final video gaps."""
    receiver_dispatch_analysis = analyze_frame_receiver_dispatch(
        renderer_events.get("frame_receiver_events", {}),
        renderer_events,
        reference_period_ns=reference_period_ns,
        gap_threshold_periods=gap_threshold_periods,
        fixed_window=fixed_window,
        clock_offset_ns=receiver_clock_offset_ns,
    )
    do_frame_timestamps = sorted(
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("do_frame", [])
        if to_int(item.get("ts_ns")) is not None
    )
    on_vsync_timestamps = sorted(
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("on_vsync", [])
        if to_int(item.get("ts_ns")) is not None
    )
    egl_timestamps = sorted(
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("egl_swap", [])
        if to_int(item.get("ts_ns")) is not None
    )
    display_callback_summary = renderer_events.get("display_callback_events", {})
    display_callback_rows = [
        dict(item)
        for item in display_callback_summary.get("events", {}).get("display_callback", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    receiver_summary = renderer_events.get("frame_receiver_events", {})
    receiver_dispatch_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("dispatch_vsync", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    receiver_on_vsync_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("on_vsync", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    receiver_run_rows = [
        dict(item)
        for item in receiver_summary.get("events", {}).get("run", [])
        if to_int(item.get("ts_ns")) is not None
    ]
    app_on_vsync_timestamps = sorted(
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("app_on_vsync", [])
        if to_int(item.get("ts_ns")) is not None
    )
    vsync_schedule_timestamps = sorted(
        to_int(item.get("ts_ns"))
        for item in renderer_events.get("events", {}).get("vsync_schedule", [])
        if to_int(item.get("ts_ns")) is not None
    )
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int(
        (fixed_window or {}).get("end_ts_ns_exclusive",
                                (fixed_window or {}).get("end_ts_exclusive"))
    )
    if start_ns is not None:
        do_frame_timestamps = [
            timestamp
            for timestamp in do_frame_timestamps
            if start_ns - 100_000_000 <= timestamp < (end_ns or start_ns) + 100_000_000
        ]
        on_vsync_timestamps = [
            timestamp
            for timestamp in on_vsync_timestamps
            if start_ns - 100_000_000 <= timestamp < (end_ns or start_ns) + 100_000_000
        ]
        egl_timestamps = [
            timestamp
            for timestamp in egl_timestamps
            if start_ns - 100_000_000 <= timestamp < (end_ns or start_ns) + 100_000_000
        ]
        display_callback_rows = [
            item
            for item in display_callback_rows
            if start_ns - 100_000_000 <= to_int(item.get("ts_ns"), 0)
            < (end_ns or start_ns) + 100_000_000
        ]
        receiver_on_vsync_rows = [
            item
            for item in receiver_on_vsync_rows
            if start_ns - 100_000_000 <= to_int(item.get("ts_ns"), 0)
            < (end_ns or start_ns) + 100_000_000
        ]
        receiver_dispatch_rows = [
            item
            for item in receiver_dispatch_rows
            if start_ns - 100_000_000 <= to_int(item.get("ts_ns"), 0)
            < (end_ns or start_ns) + 100_000_000
        ]
        receiver_run_rows = [
            item
            for item in receiver_run_rows
            if start_ns - 100_000_000 <= to_int(item.get("ts_ns"), 0)
            < (end_ns or start_ns) + 100_000_000
        ]
        app_on_vsync_timestamps = [
            timestamp
            for timestamp in app_on_vsync_timestamps
            if start_ns - 100_000_000 <= timestamp < (end_ns or start_ns) + 100_000_000
        ]
        vsync_schedule_timestamps = [
            timestamp
            for timestamp in vsync_schedule_timestamps
            if start_ns - 100_000_000 <= timestamp < (end_ns or start_ns) + 100_000_000
        ]
    timing_by_index = {
        to_int(item.get("frame_index")): item
        for item in video_frame_timing
        if to_int(item.get("frame_index")) is not None
    }
    threshold_ns = None if reference_period_ns is None else reference_period_ns * gap_threshold_periods
    renderer_gap_observations: list[dict[str, Any]] = []
    renderer_gap_by_current_index: dict[int, dict[str, Any]] = {}
    renderer_on_vsync_gap_observations: list[dict[str, Any]] = []

    def callbacks_between(start: int, end: int) -> list[dict[str, Any]]:
        return [
            item for item in display_callback_rows
            if start <= to_int(item.get("ts_ns"), start - 1) < end
        ]

    in_scope_do_frames = [
        timestamp
        for timestamp in do_frame_timestamps
        if start_ns is None or (timestamp >= start_ns and (end_ns is None or timestamp < end_ns))
    ]
    in_scope_on_vsync = [
        timestamp
        for timestamp in on_vsync_timestamps
        if start_ns is None or (timestamp >= start_ns and (end_ns is None or timestamp < end_ns))
    ]
    in_scope_app_on_vsync = [
        timestamp
        for timestamp in app_on_vsync_timestamps
        if start_ns is None or (timestamp >= start_ns and (end_ns is None or timestamp < end_ns))
    ]
    in_scope_vsync_schedule = [
        timestamp
        for timestamp in vsync_schedule_timestamps
        if start_ns is None or (timestamp >= start_ns and (end_ns is None or timestamp < end_ns))
    ]
    in_scope_receiver_on_vsync_rows = [
        item
        for item in receiver_on_vsync_rows
        if start_ns is None
        or (to_int(item.get("ts_ns"), 0) >= start_ns
            and (end_ns is None or to_int(item.get("ts_ns"), 0) < end_ns))
    ]
    in_scope_receiver_dispatch_rows = [
        item
        for item in receiver_dispatch_rows
        if start_ns is None
        or (to_int(item.get("ts_ns"), 0) >= start_ns
            and (end_ns is None or to_int(item.get("ts_ns"), 0) < end_ns))
    ]
    in_scope_receiver_run_rows = [
        item
        for item in receiver_run_rows
        if start_ns is None
        or (to_int(item.get("ts_ns"), 0) >= start_ns
            and (end_ns is None or to_int(item.get("ts_ns"), 0) < end_ns))
    ]
    on_vsync_threshold_ns = threshold_ns
    if on_vsync_threshold_ns is not None:
        for previous_ts, current_ts in zip(in_scope_on_vsync, in_scope_on_vsync[1:]):
            interval_ns = current_ts - previous_ts
            if interval_ns <= on_vsync_threshold_ns:
                continue
            app_ticks = [
                timestamp for timestamp in in_scope_app_on_vsync
                if previous_ts <= timestamp < current_ts
            ]
            schedule_requests = [
                timestamp for timestamp in in_scope_vsync_schedule
                if previous_ts <= timestamp < current_ts
            ]
            nearest_schedule_ts = min(
                schedule_requests,
                key=lambda value: min((abs(value - app_ts) for app_ts in app_ticks), default=0),
                default=None,
            )
            nearest_app_ts = (
                None
                if nearest_schedule_ts is None
                else min(
                    app_ticks,
                    key=lambda value: abs(value - nearest_schedule_ts),
                    default=None,
                )
            )
            schedule_minus_app_ns = (
                None
                if nearest_app_ts is None or nearest_schedule_ts is None
                else nearest_schedule_ts - nearest_app_ts
            )
            receiver_on_ticks = [
                item for item in in_scope_receiver_on_vsync_rows
                if previous_ts <= to_int(item.get("ts_ns"), previous_ts - 1) < current_ts
            ]
            boundary_exclusion_ns = min(1_000_000, max(1, interval_ns // 4))
            receiver_dispatch_all_ticks = [
                item for item in in_scope_receiver_dispatch_rows
                if previous_ts <= to_int(item.get("ts_ns"), previous_ts - 1) < current_ts
                and str(item.get("thread_name", "")) == "ijk_ext_render"
            ]
            receiver_dispatch_ticks = [
                item for item in receiver_dispatch_all_ticks
                if previous_ts + boundary_exclusion_ns
                <= to_int(item.get("ts_ns"), previous_ts - 1)
                < current_ts - boundary_exclusion_ns
            ]
            receiver_run_all_ticks = [
                item for item in in_scope_receiver_run_rows
                if previous_ts <= to_int(item.get("ts_ns"), previous_ts - 1) < current_ts
            ]
            receiver_run_ticks = [
                item for item in receiver_run_all_ticks
                if previous_ts + boundary_exclusion_ns
                <= to_int(item.get("ts_ns"), previous_ts - 1)
                < current_ts - boundary_exclusion_ns
            ]
            renderer_on_vsync_gap_observations.append({
                "renderer_on_vsync_previous_ts_ns": previous_ts,
                "renderer_on_vsync_ts_ns": current_ts,
                "renderer_on_vsync_interval_ns": interval_ns,
                "is_large_gap": interval_ns > LARGE_GAP_MS * 1e6,
                "receiver_boundary_exclusion_ns": boundary_exclusion_ns,
                "app_on_vsync_count": len(app_ticks),
                "app_on_vsync_timestamps_ns": app_ticks,
                "renderer_vsync_schedule_count": len(schedule_requests),
                "renderer_vsync_schedule_timestamps_ns": schedule_requests,
                "nearest_app_on_vsync_ts_ns": nearest_app_ts,
                "nearest_renderer_vsync_schedule_ts_ns": nearest_schedule_ts,
                "renderer_schedule_minus_app_on_vsync_ns": schedule_minus_app_ns,
                "renderer_schedule_relative_to_app_on_vsync": (
                    None
                    if schedule_minus_app_ns is None
                    else "BEFORE_APP_ONVSYNC"
                    if schedule_minus_app_ns < 0
                    else "AT_OR_AFTER_APP_ONVSYNC"
                ),
                "receiver_on_vsync_count": len(receiver_on_ticks),
                "receiver_dispatch_vsync_count": len(receiver_dispatch_ticks),
                "receiver_dispatch_vsync_boundary_count": len(receiver_dispatch_all_ticks)
                - len(receiver_dispatch_ticks),
                "receiver_run_count": len(receiver_run_ticks),
                "receiver_run_boundary_count": len(receiver_run_all_ticks) - len(receiver_run_ticks),
                "receiver_dispatch_thread_names": sorted({
                    str(item.get("thread_name"))
                    for item in receiver_dispatch_ticks
                    if item.get("thread_name") is not None
                }),
                "receiver_on_vsync_thread_names": sorted({
                    str(item.get("thread_name"))
                    for item in receiver_on_ticks
                    if item.get("thread_name") is not None
                }),
                "receiver_run_thread_names": sorted({
                    str(item.get("thread_name"))
                    for item in receiver_run_ticks
                    if item.get("thread_name") is not None
                }),
                "receiver_dispatch_classification": (
                    "DISPATCH_INTERIOR_AND_RUN_INTERIOR_PRESENT"
                    if receiver_dispatch_ticks and receiver_run_ticks
                    else "DISPATCH_INTERIOR_PRESENT_RUN_INTERIOR_MISSING"
                    if receiver_dispatch_ticks
                    else "DISPATCH_BOUNDARY_ONLY_OR_MISSING"
                ),
                "classification": (
                    "RENDERER_VSYNC_GAP_WITH_APP_VSYNC_AND_SCHEDULE"
                    if app_ticks and schedule_requests
                    else "RENDERER_VSYNC_GAP_WITH_APP_VSYNC"
                    if app_ticks
                    else "RENDERER_VSYNC_GAP_WITH_SCHEDULE"
                    if schedule_requests
                    else "RENDERER_VSYNC_GAP_NO_WITNESS"
                ),
            })
    for current_index, (previous_ts, current_ts) in enumerate(
        zip(in_scope_do_frames, in_scope_do_frames[1:]),
        start=1,
    ):
        interval_ns = current_ts - previous_ts
        if threshold_ns is None or interval_ns <= threshold_ns:
            continue
        callbacks = callbacks_between(previous_ts, current_ts)
        latest_callback = max(callbacks, key=lambda item: to_int(item.get("ts_ns"), 0), default=None)
        observation = {
            "renderer_do_frame_previous_ts_ns": previous_ts,
            "renderer_do_frame_ts_ns": current_ts,
            "renderer_do_frame_interval_ns": interval_ns,
            "is_large_gap": interval_ns > LARGE_GAP_MS * 1e6,
            "display_callback_count": len(callbacks),
            "display_callback_tokens": [item.get("token") for item in callbacks],
            "display_callback_thread_names": sorted({
                str(item.get("thread_name"))
                for item in callbacks
                if item.get("thread_name") is not None
            }),
            "display_callback_process_names": sorted({
                str(item.get("process_name"))
                for item in callbacks
                if item.get("process_name") is not None
            }),
            "display_callback_timestamps_ns": [item.get("ts_ns") for item in callbacks],
            "latest_display_callback_ts_ns": (
                None if latest_callback is None else latest_callback.get("ts_ns")
            ),
            "display_callback_to_next_do_frame_ns": (
                None
                if latest_callback is None
                else current_ts - to_int(latest_callback.get("ts_ns"), current_ts)
            ),
            "display_callback_duration_ns": [
                item.get("duration_ns") for item in callbacks
                if item.get("duration_ns") is not None
            ],
            "display_callback_classification": (
                "CALLBACK_PRESENT_RENDERER_SLOT_MISSING"
                if callbacks
                else "NO_CALLBACK_RENDERER_SLOT_MISSING"
            ),
        }
        renderer_gap_observations.append(observation)
        renderer_gap_by_current_index[current_index] = observation

    per_frame: dict[int, dict[str, Any]] = {}
    temporal_match_limit_ns = 5_000_000
    for interval in interval_rows:
        if not interval.get("is_gap_over_threshold") or interval.get("is_large_gap"):
            continue
        present_index = to_int(interval.get("present_event_index"))
        current = timing_by_index.get(present_index) if present_index is not None else None
        current_ts = None if current is None else to_int(current.get("present_ts_ns"))
        if present_index is None or current is None or current_ts is None:
            continue
        if start_ns is not None and current_ts < start_ns:
            continue
        if end_ns is not None and current_ts >= end_ns:
            continue
        queue_ts = to_int(current.get("stage_join", {}).get("stage_ts_ns", {}).get("Queue"))
        do_index = None if queue_ts is None else bisect.bisect_right(do_frame_timestamps, queue_ts) - 1
        matched = (
            do_index is not None
            and do_index >= 1
            and queue_ts is not None
            and queue_ts - do_frame_timestamps[do_index] <= temporal_match_limit_ns
        )
        do_interval_ns = (
            None
            if not matched
            else do_frame_timestamps[do_index] - do_frame_timestamps[do_index - 1]
        )
        renderer_gap = (
            None
            if not matched or do_index is None
            else renderer_gap_by_current_index.get(
                bisect.bisect_left(in_scope_do_frames, do_frame_timestamps[do_index])
            )
        )
        observation = {
            "present_event_index": present_index,
            "frame_number": to_int(current.get("frame_number")),
            "queue_ts_ns": queue_ts,
            "renderer_do_frame_ts_ns": None if not matched else do_frame_timestamps[do_index],
            "renderer_do_frame_previous_ts_ns": (
                None if not matched else do_frame_timestamps[do_index - 1]
            ),
            "queue_minus_renderer_do_frame_ns": (
                None if not matched else queue_ts - do_frame_timestamps[do_index]
            ),
            "renderer_do_frame_interval_ns": do_interval_ns,
            "renderer_do_frame_gap": (
                None if do_interval_ns is None or threshold_ns is None else do_interval_ns > threshold_ns
            ),
            "display_callback_count": (
                None if renderer_gap is None else renderer_gap["display_callback_count"]
            ),
            "display_callback_classification": (
                None if renderer_gap is None else renderer_gap["display_callback_classification"]
            ),
            "latest_display_callback_ts_ns": (
                None if renderer_gap is None else renderer_gap["latest_display_callback_ts_ns"]
            ),
            "display_callback_to_next_do_frame_ns": (
                None if renderer_gap is None else renderer_gap["display_callback_to_next_do_frame_ns"]
            ),
            "display_callback_thread_names": (
                [] if renderer_gap is None else renderer_gap["display_callback_thread_names"]
            ),
            "match_method": "nearest_prior_renderer_doFrame_within_5ms" if matched else "UNMATCHED",
            "identity_boundary": (
                "Temporal renderer-thread correlation only; no shared codec or buffer identity."
            ),
        }
        renderer_gap_observations.append(observation)
        per_frame[present_index] = observation
    video_gap_observations = [
        item for item in renderer_gap_observations
        if "present_event_index" in item
    ]
    renderer_gap_only_observations = [
        item for item in renderer_gap_observations
        if "present_event_index" not in item
    ]
    callback_interval_summary = interval_summary(
        [to_int(item.get("ts_ns")) for item in display_callback_rows if to_int(item.get("ts_ns")) is not None],
        reference_period_ns=reference_period_ns,
        gap_threshold_periods=gap_threshold_periods,
    )
    receiver_dispatch_timestamps = [
        to_int(item.get("ts_ns"))
        for item in in_scope_receiver_dispatch_rows
        if str(item.get("thread_name", "")) == "ijk_ext_render"
        and to_int(item.get("ts_ns")) is not None
    ]
    receiver_on_vsync_timestamps = [
        to_int(item.get("ts_ns"))
        for item in in_scope_receiver_on_vsync_rows
        if to_int(item.get("ts_ns")) is not None
    ]
    receiver_run_timestamps = [
        to_int(item.get("ts_ns"))
        for item in in_scope_receiver_run_rows
        if to_int(item.get("ts_ns")) is not None
    ]
    receiver_on_vsync_thread_counts = dict(Counter(
        str(item.get("thread_name"))
        for item in in_scope_receiver_on_vsync_rows
        if item.get("thread_name") is not None
    ))
    receiver_dispatch_thread_counts = dict(Counter(
        str(item.get("thread_name"))
        for item in in_scope_receiver_dispatch_rows
        if item.get("thread_name") is not None
    ))
    receiver_run_thread_counts = dict(Counter(
        str(item.get("thread_name"))
        for item in in_scope_receiver_run_rows
        if item.get("thread_name") is not None
    ))
    renderer_gap_count = len(renderer_gap_only_observations)
    renderer_gaps_with_callback = sum(
        item["display_callback_count"] > 0 for item in renderer_gap_only_observations
    )
    video_gaps_with_callback = sum(
        (item.get("display_callback_count") or 0) > 0 for item in video_gap_observations
    )
    return {
        "status": renderer_events.get("status", "NO_EVENTS"),
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "do_frame_count": len(in_scope_do_frames),
        "on_vsync_count": sum(
            start_ns is None or (timestamp >= start_ns and (end_ns is None or timestamp < end_ns))
            for timestamp in on_vsync_timestamps
        ),
        "egl_swap_count": sum(
            start_ns is None or (timestamp >= start_ns and (end_ns is None or timestamp < end_ns))
            for timestamp in egl_timestamps
        ),
        "app_on_vsync_count": len(in_scope_app_on_vsync),
        "renderer_vsync_schedule_count": len(in_scope_vsync_schedule),
        "receiver_on_vsync_count": len(receiver_on_vsync_timestamps),
        "receiver_dispatch_vsync_count": len(receiver_dispatch_timestamps),
        "receiver_run_count": len(receiver_run_timestamps),
        "receiver_dispatch_vsync_thread_counts": receiver_dispatch_thread_counts,
        "receiver_on_vsync_thread_counts": receiver_on_vsync_thread_counts,
        "receiver_run_thread_counts": receiver_run_thread_counts,
        "receiver_on_vsync_interval_summary": interval_summary(
            receiver_on_vsync_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "receiver_dispatch_vsync_interval_summary": interval_summary(
            receiver_dispatch_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "receiver_run_interval_summary": interval_summary(
            receiver_run_timestamps,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "on_vsync_interval_summary": interval_summary(
            in_scope_on_vsync,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "renderer_on_vsync_gap_count": len(renderer_on_vsync_gap_observations),
        "renderer_on_vsync_gaps_with_app_vsync": sum(
            item["app_on_vsync_count"] > 0 for item in renderer_on_vsync_gap_observations
        ),
        "renderer_on_vsync_gaps_with_schedule": sum(
            item["renderer_vsync_schedule_count"] > 0
            for item in renderer_on_vsync_gap_observations
        ),
        "renderer_on_vsync_gaps_schedule_before_app_on_vsync": sum(
            item.get("renderer_schedule_relative_to_app_on_vsync") == "BEFORE_APP_ONVSYNC"
            for item in renderer_on_vsync_gap_observations
        ),
        "renderer_on_vsync_gaps_schedule_at_or_after_app_on_vsync": sum(
            item.get("renderer_schedule_relative_to_app_on_vsync") == "AT_OR_AFTER_APP_ONVSYNC"
            for item in renderer_on_vsync_gap_observations
        ),
        "renderer_schedule_minus_app_on_vsync": _telemetry_interval_stats(
            [
                item["renderer_schedule_minus_app_on_vsync_ns"]
                for item in renderer_on_vsync_gap_observations
                if item.get("renderer_schedule_minus_app_on_vsync_ns") is not None
            ]
        ),
        "renderer_on_vsync_gap_classification_counts": dict(
            Counter(item["classification"] for item in renderer_on_vsync_gap_observations)
        ),
        "renderer_on_vsync_gap_observations": renderer_on_vsync_gap_observations,
        "receiver_dispatch_analysis": receiver_dispatch_analysis,
        "do_frame_interval_summary": interval_summary(
            in_scope_do_frames,
            reference_period_ns=reference_period_ns,
            gap_threshold_periods=gap_threshold_periods,
        ),
        "renderer_do_frame_gap_count": (
            renderer_gap_count
        ),
        "renderer_gaps_with_display_callback": renderer_gaps_with_callback,
        "renderer_gaps_without_display_callback": renderer_gap_count - renderer_gaps_with_callback,
        "video_gaps_with_display_callback": video_gaps_with_callback,
        "display_callback_count": len(display_callback_rows),
        "display_callback_paired_count": display_callback_summary.get("paired_count", 0),
        "display_callback_unpaired_start_count": display_callback_summary.get("unpaired_start_count", 0),
        "display_callback_interval_summary": callback_interval_summary,
        "video_gap_count": len(video_gap_observations),
        "video_gaps_temporally_matched_to_renderer": sum(
            item["match_method"] != "UNMATCHED" for item in video_gap_observations
        ),
        "video_gaps_with_renderer_do_frame_gap": sum(
            item["renderer_do_frame_gap"] is True for item in video_gap_observations
        ),
        "gap_observations": video_gap_observations,
        "video_gap_observations": video_gap_observations,
        "renderer_gap_observations": renderer_gap_only_observations,
        "temporal_match_limit_ns": temporal_match_limit_ns,
        "note": (
            "A renderer doFrame gap near a video Queue is stronger evidence that the missing slot occurred "
            "before SurfaceFlinger latch, but it remains temporal correlation rather than buffer identity."
        ),
        "_by_frame_index": per_frame,
    }


def classify_present_intervals(
    present_rows: Sequence[Mapping[str, Any]],
    reference_period_ns: int | None,
    nominal_period_ns: int,
    gap_threshold_periods: float,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    ordered = sorted(
        [dict(row) for row in present_rows],
        key=lambda item: (to_int(item.get("ts"), 0), to_int(item.get("id"), 0)),
    )
    frame_occurrences: Counter[int] = Counter()
    for row in ordered:
        number = to_int(row.get("frame_number"))
        if number is not None:
            frame_occurrences[number] += 1
    duplicate_numbers = {number for number, count in frame_occurrences.items() if count > 1}
    intervals_ns: list[int] = []
    interval_rows: list[dict[str, Any]] = []
    reference = reference_period_ns or median_reference_period_ns(
        [to_int(row.get("ts")) for row in ordered if to_int(row.get("ts")) is not None]
    )
    for index, row in enumerate(ordered):
        current_ts = to_int(row.get("ts"))
        previous = ordered[index - 1] if index else None
        previous_ts = None if previous is None else to_int(previous.get("ts"))
        delta_ns = None if current_ts is None or previous_ts is None else current_ts - previous_ts
        frame_number = to_int(row.get("frame_number"))
        previous_frame_number = None if previous is None else to_int(previous.get("frame_number"))
        frame_delta = (
            None
            if frame_number is None or previous_frame_number is None
            else frame_number - previous_frame_number
        )
        slot_count = None
        residual_ns = None
        if delta_ns is not None and delta_ns > 0 and reference:
            slot_count = max(1, int(math.floor(delta_ns / reference + 0.5)))
            residual_ns = delta_ns - slot_count * reference
            intervals_ns.append(delta_ns)
        large_gap = delta_ns is not None and delta_ns > LARGE_GAP_MS * 1e6
        gap_threshold = reference is not None and delta_ns is not None and delta_ns > reference * gap_threshold_periods
        if delta_ns is None:
            classification = "first_or_invalid"
        elif delta_ns <= nominal_period_ns:
            classification = "at_or_below_nominal_source_period"
        elif large_gap:
            classification = "large_gap_over_100ms"
        elif gap_threshold:
            classification = "whole_slot_or_cadence_gap"
        else:
            classification = "between_nominal_and_gap_threshold"
        interval_rows.append(
            {
                "present_event_index": index,
                "frame_number": frame_number,
                "previous_frame_number": previous_frame_number,
                "frame_number_delta": frame_delta,
                "previous_present_ts_ns": previous_ts,
                "present_ts_ns": current_ts,
                "interval_ns": delta_ns,
                "interval_ms": None if delta_ns is None else delta_ns / 1e6,
                "whole_vsync_slots_rounded": slot_count,
                "slot_residual_ns": residual_ns,
                "classification": classification,
                "is_above_nominal_source_period": delta_ns is not None and delta_ns > nominal_period_ns,
                "is_gap_over_threshold": bool(gap_threshold),
                "is_large_gap": bool(large_gap),
            }
        )

    anomaly_indices = [
        index
        for index, item in enumerate(interval_rows)
        if item["is_gap_over_threshold"] and not item["is_large_gap"]
    ]
    clusters: list[dict[str, Any]] = []
    if anomaly_indices:
        cluster = [anomaly_indices[0]]
        for index in anomaly_indices[1:]:
            if index - cluster[-1] <= 3:
                cluster.append(index)
            else:
                clusters.append(cluster)
                cluster = [index]
        clusters.append(cluster)
    for cluster_id, cluster in enumerate(clusters, start=1):
        for index in cluster:
            interval_rows[index]["cadence_anomaly_cluster_id"] = cluster_id
    counts = Counter(item["classification"] for item in interval_rows)
    summary = {
        "present_count": len(ordered),
        "unique_frame_number_count": len(frame_occurrences),
        "duplicate_frame_number_count": sum(frame_occurrences[number] - 1 for number in duplicate_numbers),
        "missing_frame_number_count": sum(to_int(row.get("frame_number")) is None for row in ordered),
        "reference_period_ms": None if reference is None else reference / 1e6,
        "nominal_source_period_ms": nominal_period_ns / 1e6,
        "nominal_source_fps": 1e9 / nominal_period_ns,
        "interval_counts": dict(counts),
        "interval_gt_nominal_source_period_count": sum(
            item["is_above_nominal_source_period"] for item in interval_rows
        ),
        "interval_gt_gap_threshold_count": sum(item["is_gap_over_threshold"] for item in interval_rows),
        "interval_gt_100ms_count": sum(item["is_large_gap"] for item in interval_rows),
        "small_cadence_gap_count": sum(
            item["is_gap_over_threshold"] and not item["is_large_gap"] for item in interval_rows
        ),
        "cadence_anomaly_cluster_count": len(clusters),
        "largest_cadence_anomaly_cluster_size": max((len(cluster) for cluster in clusters), default=0),
        "frame_number_jump_count": sum(
            item["frame_number_delta"] is not None and item["frame_number_delta"] != 1
            for item in interval_rows
        ),
        "one_percent_low_fps_all_intervals": one_percent_low_fps(intervals_ns),
        "one_percent_low_fps_excluding_over_100ms": one_percent_low_fps(
            intervals_ns, exclude_over_ms=LARGE_GAP_MS
        ),
        "median_cadence_fps": (
            None
            if not intervals_ns
            else 1e9 / statistics.median(intervals_ns)
        ),
        "effective_present_fps": (
            None
            if len(ordered) < 2 or to_int(ordered[-1].get("ts")) == to_int(ordered[0].get("ts"))
            else (len(ordered) - 1)
            / ((to_int(ordered[-1].get("ts")) - to_int(ordered[0].get("ts"))) / 1e9)
        ),
    }
    summary["interval_metrics"] = {
        "count": len(intervals_ns),
        "median_ms": None if not intervals_ns else statistics.median(intervals_ns) / 1e6,
        "p01_ms": None if not intervals_ns else percentile(intervals_ns, 0.01) / 1e6,
        "p99_ms": None if not intervals_ns else percentile(intervals_ns, 0.99) / 1e6,
        "max_ms": None if not intervals_ns else max(intervals_ns) / 1e6,
        "reference_period_ms": None if reference is None else reference / 1e6,
        "nominal_source_period_ms": nominal_period_ns / 1e6,
    }
    summary["classification_note"] = (
        "Intervals above the nominal 120 fps period are retained as observations. "
        "A whole-slot interval is a cadence event candidate, not proof of a dropped source frame. "
        "Frame identity and the residual against the measured reference period are required for interpretation."
    )
    return interval_rows, summary


def phase_against_expected(timestamp_ns: int | None, expected_timestamps: Sequence[int], reference_period_ns: int | None) -> dict[str, Any]:
    if timestamp_ns is None or not expected_timestamps:
        return {"confidence": "unknown", "reason": "missing_timestamp_or_expected_stream"}
    index = bisect.bisect_left(expected_timestamps, timestamp_ns)
    previous_index = index - 1
    next_index = index
    previous_ts = expected_timestamps[previous_index] if previous_index >= 0 else None
    next_ts = expected_timestamps[next_index] if next_index < len(expected_timestamps) else None
    neighbor_interval = None
    if previous_ts is not None and next_ts is not None:
        neighbor_interval = next_ts - previous_ts
    boundary = previous_ts is None or next_ts is None
    invalid_gap = (
        reference_period_ns is not None
        and neighbor_interval is not None
        and neighbor_interval > reference_period_ns * 1.5
    )
    nearest = min(
        (candidate for candidate in (previous_ts, next_ts) if candidate is not None),
        key=lambda candidate: abs(timestamp_ns - candidate),
    )
    return {
        "previous_expected_display_timeline_ts_ns": previous_ts,
        "next_expected_display_timeline_ts_ns": next_ts,
        "neighbor_interval_ms": None if neighbor_interval is None else neighbor_interval / 1e6,
        "nearest_expected_display_timeline_ts_ns": nearest,
        "offset_from_nearest_expected_display_timeline_ms": (timestamp_ns - nearest) / 1e6,
        "confidence": (
            "boundary"
            if boundary
            else "invalid_due_to_expected_gap"
            if invalid_gap
            else "predicted_display_timeline_only"
        ),
        "source": "expected_frame_timeline_slice.ts",
        "source_kind": "predicted_display_timeline",
        "not_a_measured_hwc_vsync": True,
        "physical_scanout_measured": False,
    }


def nearest_display_actual(timestamp_ns: int | None, actual_rows: Sequence[Mapping[str, Any]], reference_period_ns: int | None) -> dict[str, Any]:
    if timestamp_ns is None or not actual_rows:
        return {"join_method": "temporal_only", "quality": "unknown"}
    actual_events = []
    for row in actual_rows:
        start = to_int(row.get("ts"))
        dur = to_int(row.get("dur"), 0)
        if start is not None:
            actual_events.append((start + max(0, dur), row))
    if not actual_events:
        return {"join_method": "temporal_only", "quality": "unknown"}
    timestamps = [event[0] for event in actual_events]
    index = bisect.bisect_left(timestamps, timestamp_ns)
    choices = [item for item in (index - 1, index) if 0 <= item < len(actual_events)]
    best_index = min(choices, key=lambda item: abs(actual_events[item][0] - timestamp_ns))
    actual_ts, row = actual_events[best_index]
    delta = timestamp_ns - actual_ts
    strong_limit = None if reference_period_ns is None else reference_period_ns // 2
    return {
        "join_method": "temporal_nearest_same_trace_clock",
        "quality": "strong_temporal_correlation" if strong_limit is not None and abs(delta) <= strong_limit else "weak_temporal_correlation",
        "display_actual_present_ts_ns": actual_ts,
        "display_actual_delta_ns": delta,
        "display_frame_token_temporal_only": to_int(row.get("display_frame_token")),
        "physical_scanout_measured": False,
    }


def display_pairs(expected_rows: Sequence[Mapping[str, Any]], actual_rows: Sequence[Mapping[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    expected_groups: dict[int | None, list[Mapping[str, Any]]] = defaultdict(list)
    actual_groups: dict[int | None, list[Mapping[str, Any]]] = defaultdict(list)
    for row in expected_rows:
        expected_groups[to_int(row.get("display_frame_token"))].append(row)
    for row in actual_rows:
        actual_groups[to_int(row.get("display_frame_token"))].append(row)
    pairs: list[dict[str, Any]] = []
    unmatched_expected = 0
    unmatched_actual = 0
    for token in sorted(set(expected_groups) | set(actual_groups), key=lambda value: (-1 if value is None else value)):
        expected_group = sorted(expected_groups.get(token, []), key=lambda row: (to_int(row.get("ts"), 0), to_int(row.get("id"), 0)))
        actual_group = sorted(actual_groups.get(token, []), key=lambda row: (to_int(row.get("ts"), 0), to_int(row.get("id"), 0)))
        count = min(len(expected_group), len(actual_group))
        unmatched_expected += len(expected_group) - count
        unmatched_actual += len(actual_group) - count
        for ordinal in range(count):
            expected = expected_group[ordinal]
            actual = actual_group[ordinal]
            expected_start = to_int(expected.get("ts"))
            expected_deadline = None if expected_start is None else expected_start + max(0, to_int(expected.get("dur"), 0))
            actual_start = to_int(actual.get("ts"))
            actual_present = None if actual_start is None else actual_start + max(0, to_int(actual.get("dur"), 0))
            pairs.append(
                {
                    "display_frame_token": token,
                    "token_ordinal": ordinal,
                    "pair_method": "display_frame_token_plus_ordinal",
                    "expected_start_ns": expected_start,
                    "expected_deadline_ns": expected_deadline,
                    "actual_start_ns": actual_start,
                    "actual_present_ns": actual_present,
                    "start_delay_ms": None
                    if expected_start is None or actual_start is None
                    else (actual_start - expected_start) / 1e6,
                    "display_present_minus_expected_deadline_ms": None
                    if expected_deadline is None or actual_present is None
                    else (actual_present - expected_deadline) / 1e6,
                    "present_type": actual.get("present_type"),
                    "on_time_finish": actual.get("on_time_finish"),
                    "jank_type": actual.get("jank_type"),
                    "jank_severity_type": actual.get("jank_severity_type"),
                    "prediction_type": actual.get("prediction_type"),
                    "jank_tag": actual.get("jank_tag"),
                    "latched_fence_state": actual.get("latched_fence_state"),
                }
            )
    pairs.sort(key=lambda item: (item["actual_present_ns"] or 0, item["display_frame_token"] or -1, item["token_ordinal"]))
    start_delays = [item["start_delay_ms"] for item in pairs if item["start_delay_ms"] is not None]
    deadline_deltas = [
        item["display_present_minus_expected_deadline_ms"]
        for item in pairs
        if item["display_present_minus_expected_deadline_ms"] is not None
    ]
    return pairs, {
        "paired_count": len(pairs),
        "unmatched_expected_count": unmatched_expected,
        "unmatched_actual_count": unmatched_actual,
        "start_delay_ms": {
            "count": len(start_delays),
            "median": None if not start_delays else statistics.median(start_delays),
            "p99": percentile(start_delays, 0.99),
            "max": None if not start_delays else max(start_delays),
        },
        "display_present_minus_expected_deadline_ms": {
            "count": len(deadline_deltas),
            "median": None if not deadline_deltas else statistics.median(deadline_deltas),
            "p99": percentile(deadline_deltas, 0.99),
            "max": None if not deadline_deltas else max(deadline_deltas),
        },
        "semantic_note": (
            "expected_deadline is the prediction end/deadline and actual_present is the DisplayFrame "
            "onPresent fence end in the trace. Neither field is a panel scanline timestamp."
        ),
    }


def stats_summary(tp: Any) -> dict[str, Any]:
    rows = query_rows(tp, "select name,idx,severity,source,value,description from stats")
    selected = []
    for row in rows:
        name = str(row_value(row, "name", ""))
        if any(term in name for term in ("packet_loss", "overrun_delta", "systrace_parse_failure")):
            selected.append(
                {
                    "name": name,
                    "idx": to_int(row_value(row, "idx")),
                    "severity": row_value(row, "severity"),
                    "source": row_value(row, "source"),
                    "value": to_int(row_value(row, "value")),
                    "description": row_value(row, "description"),
                }
            )
    packet_loss = [item for item in selected if "packet_loss" in item["name"]]
    overrun_delta = [item for item in selected if "overrun_delta" in item["name"]]
    parse_failure = [item for item in selected if item["name"] == "systrace_parse_failure"]
    return {
        "selected_rows": selected,
        "packet_loss_total": sum(item["value"] or 0 for item in packet_loss),
        "nonzero_packet_loss_rows": [item for item in packet_loss if (item["value"] or 0) != 0],
        "ftrace_overrun_delta_total": sum(item["value"] or 0 for item in overrun_delta),
        "nonzero_ftrace_overrun_delta_rows": [item for item in overrun_delta if (item["value"] or 0) != 0],
        "systrace_parse_failure_total": sum(item["value"] or 0 for item in parse_failure),
        "interpretation": (
            "ftrace overrun_begin/end counters are retained as context; only overrun_delta and packet-loss "
            "counters are used as loss evidence. systrace_parse_failure is reported separately and does not "
            "automatically invalidate the graphics streams."
        ),
    }


def trace_bounds(tp: Any) -> dict[str, Any]:
    rows = query_rows(tp, "select start_ts,end_ts from trace_bounds limit 1")
    if not rows:
        return {"start_ts_ns": None, "end_ts_ns": None, "duration_ms": None}
    start = to_int(row_value(rows[0], "start_ts"))
    end = to_int(row_value(rows[0], "end_ts"))
    return {
        "start_ts_ns": start,
        "end_ts_ns": end,
        "duration_ms": None if start is None or end is None else (end - start) / 1e6,
    }


def raw_vsync_slice_candidates(tp: Any) -> list[dict[str, Any]]:
    rows = query_rows(
        tp,
        "select name,count(*) as count,min(ts) as first_ts_ns,max(ts) as last_ts_ns " 
        "from slice where lower(name) like '%vsync%' group by name order by count desc limit 40",
    )
    return [
        {
            "name": row_value(row, "name"),
            "count": to_int(row_value(row, "count")),
            "first_ts_ns": to_int(row_value(row, "first_ts_ns")),
            "last_ts_ns": to_int(row_value(row, "last_ts_ns")),
        }
        for row in rows
    ]


def clock_snapshot_calibration(tp: Any) -> dict[str, Any]:
    """Return the BOOTTIME minus MONOTONIC offset available in the trace.

    Media3 telemetry uses ``System.nanoTime`` (CLOCK_MONOTONIC), while the
    default Perfetto trace clock for these graphics tables is BOOTTIME.  A
    clock snapshot is the only supported bridge between those domains.  The
    result is deliberately a calibration record, not a claim that the two
    clocks are physically identical between snapshots.
    """
    rows = query_rows(
        tp,
        "select snapshot_id,clock_name,clock_value,ts from clock_snapshot "
        "where clock_name in ('BOOTTIME','MONOTONIC') order by snapshot_id,clock_name",
    )
    grouped: dict[int, dict[str, int]] = defaultdict(dict)
    snapshot_ts: dict[int, int] = {}
    for row in rows:
        snapshot_id = to_int(row_value(row, "snapshot_id"))
        clock_name = str(row_value(row, "clock_name", ""))
        clock_value = to_int(row_value(row, "clock_value"))
        trace_ts = to_int(row_value(row, "ts"))
        if snapshot_id is None or clock_value is None or clock_name not in {"BOOTTIME", "MONOTONIC"}:
            continue
        grouped[snapshot_id][clock_name] = clock_value
        if trace_ts is not None:
            snapshot_ts[snapshot_id] = trace_ts

    offsets: list[dict[str, Any]] = []
    for snapshot_id, values in sorted(grouped.items()):
        if "BOOTTIME" not in values or "MONOTONIC" not in values:
            continue
        offsets.append(
            {
                "snapshot_id": snapshot_id,
                "trace_ts_ns": snapshot_ts.get(snapshot_id),
                "offset_boottime_minus_monotonic_ns": values["BOOTTIME"] - values["MONOTONIC"],
            }
        )
    offset_values = [item["offset_boottime_minus_monotonic_ns"] for item in offsets]
    return {
        "status": "AVAILABLE" if offset_values else "UNAVAILABLE",
        "source": "clock_snapshot BOOTTIME and MONOTONIC",
        "monotonic_clock": "CLOCK_MONOTONIC / System.nanoTime",
        "perfetto_clock": "BOOTTIME",
        "offset_boottime_minus_monotonic_ns": (
            int(round(statistics.median(offset_values))) if offset_values else None
        ),
        "snapshot_count": len(offsets),
        "snapshots": offsets[:20],
        "note": (
            "Telemetry monotonic timestamps are converted by the median snapshot offset; "
            "the conversion is not a panel scanline measurement."
        ),
    }


def convert_monotonic_to_trace_ns(value: int | None, calibration: Mapping[str, Any]) -> int | None:
    if value is None or value <= 0:
        return None
    offset = to_int(calibration.get("offset_boottime_minus_monotonic_ns"))
    return None if offset is None else value + offset


def parse_detail_fields(detail: Any) -> dict[str, str]:
    fields: dict[str, str] = {}
    for item in str(detail or "").split(";"):
        if "=" not in item:
            continue
        key, value = item.split("=", 1)
        if key:
            fields[key] = value
    return fields


def telemetry_detail_int(detail: Mapping[str, str], key: str) -> int | None:
    value = to_int(detail.get(key))
    return None if value is None or value <= 0 else value


def _telemetry_interval_stats(values: Sequence[int]) -> dict[str, Any]:
    # These lists contain signed deltas, for which zero is a valid observation
    # rather than an unset sentinel.  Keep it so exact-on-anchor samples do not
    # disappear from counts or matching ratios.
    observed = list(values)
    return {
        "count": len(observed),
        "median_ms": None if not observed else statistics.median(observed) / 1e6,
        "p99_ms": None if not observed else percentile(observed, 0.99) / 1e6,
        "min_ms": None if not observed else min(observed) / 1e6,
        "max_ms": None if not observed else max(observed) / 1e6,
        "signed_values": True,
    }


def _pts_cadence_metrics(pts_values: Sequence[int], unit_ns: int) -> dict[str, Any]:
    """Summarize timestamp cadence without mistaking resets or quantization for FPS."""
    ordered = [int(value) for value in pts_values]
    deltas = [current - previous for previous, current in zip(ordered, ordered[1:])]
    positive = [delta for delta in deltas if delta > 0]
    segments: list[list[int]] = []
    current_segment: list[int] = []
    for value in ordered:
        if current_segment and value <= current_segment[-1]:
            segments.append(current_segment)
            current_segment = []
        current_segment.append(value)
    if current_segment:
        segments.append(current_segment)
    valid_segments = [segment for segment in segments if len(segment) >= 2]
    primary_segment = max(
        valid_segments,
        key=lambda segment: ((segment[-1] - segment[0]) * unit_ns, len(segment)),
        default=[],
    )
    span_ns = (
        (primary_segment[-1] - primary_segment[0]) * unit_ns
        if len(primary_segment) >= 2 else None
    )
    slope_fps = (
        (len(primary_segment) - 1) * 1_000_000_000.0 / span_ns
        if span_ns is not None and span_ns > 0
        else None
    )
    histogram = Counter(str(delta) for delta in positive)
    return {
        "source_fps": slope_fps,
        "pts_count": len(ordered),
        "positive_pts_delta_count": len(positive),
        "first_pts": primary_segment[0] if primary_segment else None,
        "last_pts": primary_segment[-1] if primary_segment else None,
        "pts_span_ms": None if span_ns is None else span_ns / 1e6,
        "pts_delta_median_ms": (
            None if not positive else statistics.median(positive) * unit_ns / 1e6
        ),
        "pts_delta_mean_ms": (
            None if not positive else statistics.mean(positive) * unit_ns / 1e6
        ),
        "pts_delta_histogram": dict(histogram),
        "nonpositive_pts_delta_count": sum(delta <= 0 for delta in deltas),
        "pts_reset_count": sum(delta <= 0 for delta in deltas),
        "monotonic_segment_count": len(valid_segments),
        "selected_segment_pts_count": len(primary_segment),
        "quantized_timestamp_note": (
            "FPS uses endpoint slope across ordered PTS values; delta histogram is retained because "
            "millisecond-quantized timestamps can alternate 8/9 ms around a 120 fps cadence. "
            "Nonpositive deltas split seek/loop reset segments; the longest monotonic segment is used "
            "for the source-FPS slope."
        ),
    }


def load_media3_telemetry(
    telemetry_path: str | Path | None,
    trace_bounds_info: Mapping[str, Any],
    calibration: Mapping[str, Any],
) -> dict[str, Any]:
    """Read the bounded on-device Media3 CSV without treating it as a trace identity.

    The CSV has codec/frame/PTS identity, but no SurfaceFlinger frame number.
    It can therefore provide source and scheduler telemetry and a temporal
    cross-check, never an exact codec-buffer-to-present join by itself.
    """
    base: dict[str, Any] = {
        "status": "NOT_PROVIDED",
        "path": None if telemetry_path is None else str(telemetry_path),
        "rows_read": 0,
        "rows_in_trace_window": 0,
        "rows_in_trace_margin_only": 0,
        "rows_outside_trace_window": 0,
        "trace_window_margin_ms": 1000.0,
        "session_ids": [],
        "source_mode": {
            "status": "UNKNOWN",
            "pts_status": "NOT_AVAILABLE",
            "source_fps": None,
            "pts_count": 0,
            "frame_index_count": 0,
            "positive_pts_delta_count": 0,
            "nonpositive_pts_delta_count": 0,
            "pts_delta_histogram": {},
            "codec_names": [],
            "codec_format_status": "UNKNOWN",
            "queue_pts_status": "NOT_AVAILABLE_IN_PER_FRAME_TELEMETRY",
            "render_route_status": "UNKNOWN",
        },
        "scheduler": {
            "status": "UNKNOWN",
            "frame_event_count": 0,
            "scheduled_timed_release_count": 0,
            "render_callback_count": 0,
            "render_callback_exact_match_count": 0,
            "render_callback_unmatched_count": 0,
            "render_callback_burst_count": 0,
            "release_call_timestamp_count": 0,
            "scheduled_release_timestamp_count": 0,
            "target_vsync_timestamp_count": 0,
            "runtime_target_vsync_timestamp_count": 0,
            "expected_presentation_timestamp_count": 0,
            "sampled_vsync_id_count": 0,
            "sampled_vsync_period_count": 0,
            "release_to_expected_presentation": _telemetry_interval_stats([]),
            "release_to_sampled_vsync": _telemetry_interval_stats([]),
        },
        "clock_calibration": dict(calibration),
        "note": "No per-frame codec telemetry was supplied for this trace.",
    }
    if telemetry_path is None:
        return base
    path = Path(telemetry_path)
    if not path.is_file():
        base["status"] = "MISSING"
        base["note"] = "The requested telemetry CSV does not exist."
        return base

    start_ns = to_int(trace_bounds_info.get("start_ts_ns"))
    end_ns = to_int(trace_bounds_info.get("end_ts_ns"))
    base["_trace_bounds"] = {
        "start_ts_ns": start_ns,
        "end_ts_ns": end_ns,
    }
    margin_ns = 1_000_000_000
    rows: list[dict[str, Any]] = []
    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            for raw in reader:
                base["rows_read"] += 1
                event_ns_mono = to_int(raw.get("eventNs"))
                event_ns_trace = convert_monotonic_to_trace_ns(event_ns_mono, calibration)
                if event_ns_trace is None:
                    continue
                in_extended_window = (
                    start_ns is None
                    or end_ns is None
                    or start_ns - margin_ns <= event_ns_trace <= end_ns + margin_ns
                )
                if not in_extended_window:
                    base["rows_outside_trace_window"] += 1
                    continue
                in_strict_window = (
                    start_ns is None
                    or end_ns is None
                    or start_ns <= event_ns_trace <= end_ns
                )
                if not in_strict_window:
                    base["rows_in_trace_margin_only"] += 1
                    continue
                base["rows_in_trace_window"] += 1
                detail = parse_detail_fields(raw.get("detail"))
                rows.append(
                    {
                        "session_id": raw.get("sessionId") or None,
                        "event": raw.get("event") or "",
                        "action": raw.get("action") or "",
                        "codec": raw.get("codec") or "",
                        "event_ns_mono": event_ns_mono,
                        "event_ts_ns": event_ns_trace,
                        "buffer_index": to_int(raw.get("bufferIndex")),
                        "pts_us": to_int(raw.get("ptsUs")),
                        "frame_index": to_int(raw.get("frameIndex")),
                        "dequeued_at_ns": convert_monotonic_to_trace_ns(
                            to_int(raw.get("dequeuedAtNs")), calibration
                        ),
                        "release_call_ts_ns": event_ns_trace if raw.get("event") == "FRAME" else None,
                        "release_time_ns": convert_monotonic_to_trace_ns(
                            to_int(raw.get("releaseTimeNs")), calibration
                        ),
                        "runtime_decision_now_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "decisionNowNs"), calibration
                        ),
                        "runtime_clock_release_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "clockReleaseTimeNs"), calibration
                        ),
                        "runtime_scheduled_release_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "scheduledReleaseNs"), calibration
                        ),
                        "runtime_target_vsync_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "chosenTargetVsyncNs"), calibration
                        ),
                        "runtime_clock_release_minus_decision_ns": to_int(
                            detail.get("clockReleaseMinusDecisionNs")
                        ),
                        "runtime_scheduled_release_minus_decision_ns": to_int(
                            detail.get("scheduledReleaseMinusDecisionNs")
                        ),
                        "runtime_scheduled_release_minus_clock_release_ns": to_int(
                            detail.get("scheduledReleaseMinusClockReleaseNs")
                        ),
                        "runtime_sampled_vsync_age_ns": to_int(
                            detail.get("sampledVsyncAgeNs")
                        ),
                        "runtime_playback_position_available": (
                            detail.get("playbackPositionAvailable")
                        ),
                        "runtime_playback_position_age_ms": to_int(
                            detail.get("playbackPositionAgeMs")
                        ),
                        "runtime_cadence_anchor_frame_index": to_int(
                            detail.get("cadenceAnchorFrameIndex")
                        ),
                        "runtime_cadence_anchor_pts_us": to_int(
                            detail.get("cadenceAnchorPtsUs")
                        ),
                        "runtime_cadence_anchor_vsync_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "cadenceAnchorVsyncNs"), calibration
                        ),
                        "runtime_cadence_frame_delta": to_int(
                            detail.get("cadenceFrameDelta")
                        ),
                        "runtime_cadence_frame_slot": to_int(
                            detail.get("cadenceFrameSlot")
                        ),
                        "runtime_cadence_drift_ns": to_int(detail.get("cadenceDriftNs")),
                        "runtime_cadence_phase_error_ns": to_int(
                            detail.get("cadencePhaseErrorNs")
                        ),
                        "runtime_cadence_reanchored": detail.get("cadenceReanchored"),
                        "runtime_cadence_reanchor_reason": detail.get(
                            "cadenceReanchorReason"
                        ),
                        "sampled_vsync_id": telemetry_detail_int(detail, "sampledVsyncId"),
                        "sampled_vsync_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "sampledVsyncNs"), calibration
                        ),
                        "vsync_duration_ns": telemetry_detail_int(detail, "vsyncDurationNs"),
                        "expected_presentation_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "expectedPresentationNs"), calibration
                        ),
                        "frame_deadline_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "frameDeadlineNs"), calibration
                        ),
                        "rendered_nano_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "callbackNanoTimeNs"), calibration
                        ),
                        "render_callback_observed_ts_ns": convert_monotonic_to_trace_ns(
                            telemetry_detail_int(detail, "callbackObservedNs"), calibration
                        ),
                        "render_callback_dispatch_delay_ns": to_int(
                            detail.get("callbackDispatchDelayNs")
                        ),
                        "render_match": detail.get("renderMatch"),
                        "render_listener_generation": to_int(
                            detail.get("renderListenerGeneration")
                        ),
                        "render_callback_burst_count": to_int(
                            detail.get("renderCallbackBurstCount")
                        ),
                        "detail": detail,
                    }
                )
    except (OSError, UnicodeError, csv.Error) as exc:
        base["status"] = "INVALID"
        base["note"] = f"Unable to parse telemetry CSV: {type(exc).__name__}: {exc}"
        return base

    session_ids = sorted({row["session_id"] for row in rows if row.get("session_id")})
    base["status"] = "AVAILABLE" if rows else "EMPTY_IN_TRACE_WINDOW"
    base["session_ids"] = session_ids

    codec_names = sorted({row["codec"] for row in rows if row.get("codec")})
    lifecycle_rows = [row for row in rows if row["event"] == "CODEC_LIFECYCLE"]
    lifecycle_surfaces = sorted(
        {
            row["detail"].get("surface", "")
            for row in lifecycle_rows
            if row["detail"].get("surface")
        }
    )
    source_rows = [row for row in rows if row.get("action") == "DEQUEUED"]
    if not source_rows:
        source_rows = list(rows)
    ordered_pts = sorted(
        (
            row for row in source_rows
            if row.get("frame_index") is not None
            and row.get("pts_us") is not None
            and row.get("pts_us") >= 0
        ),
        key=lambda row: (
            row.get("event_ts_ns") or 0,
            row.get("dequeued_at_ns") or 0,
            row.get("frame_index") or 0,
        ),
    )
    pts_values = [row["pts_us"] for row in ordered_pts]
    pts_deltas = [current - previous for previous, current in zip(pts_values, pts_values[1:])]
    positive_pts_deltas = [delta for delta in pts_deltas if delta > 0]
    pts_metrics = _pts_cadence_metrics(pts_values, unit_ns=1_000)
    source_fps = pts_metrics["source_fps"]
    source_status = "VERIFIED_PTS_AVERAGE" if len(positive_pts_deltas) >= 15 and source_fps is not None else "UNKNOWN"
    base["source_mode"] = {
        "status": source_status,
        "pts_status": "VERIFIED" if positive_pts_deltas else "UNKNOWN",
        "source_fps": source_fps,
        "pts_count": len(pts_values),
        "frame_index_count": len({row["frame_index"] for row in ordered_pts}),
        "first_pts_us": pts_metrics["first_pts"],
        "last_pts_us": pts_metrics["last_pts"],
        "pts_span_ms": pts_metrics["pts_span_ms"],
        "pts_delta_median_ms": pts_metrics["pts_delta_median_ms"],
        "pts_delta_mean_ms": pts_metrics["pts_delta_mean_ms"],
        "positive_pts_delta_count": pts_metrics["positive_pts_delta_count"],
        "pts_delta_histogram": pts_metrics["pts_delta_histogram"],
        "nonpositive_pts_delta_count": pts_metrics["nonpositive_pts_delta_count"],
        "pts_reset_count": pts_metrics["pts_reset_count"],
        "monotonic_segment_count": pts_metrics["monotonic_segment_count"],
        "selected_segment_pts_count": pts_metrics["selected_segment_pts_count"],
        "codec_names": codec_names,
        "codec_format_status": "UNKNOWN_NO_MEDIAFORMAT_FIELDS",
        "queue_pts_status": "SUMMARY_ONLY_NATIVE_LOG_NOT_PER_FRAME",
        "render_route_status": "CODEC_SURFACE_OBSERVED" if lifecycle_surfaces else "UNKNOWN",
        "codec_lifecycle_surface_classes": lifecycle_surfaces,
        "quantized_timestamp_note": pts_metrics["quantized_timestamp_note"],
        "note": (
            "PTS/frameIndex comes from MediaCodec dequeue telemetry ordered by dequeue time. It verifies "
            "codec output cadence, not one-to-one presentation of every source frame. Seek/loop PTS resets "
            "are retained as reset counts and excluded from the selected monotonic slope."
        ),
    }

    scheduled_rows = [
        row
        for row in rows
        if row["event"] == "FRAME" and row["action"] == "SCHEDULED_TIMED_RELEASE"
    ]
    scheduled_rows.sort(key=lambda row: row.get("release_call_ts_ns") or row.get("event_ts_ns") or 0)
    _annotate_pts_generations(scheduled_rows, "pts_us", scope_key="session_id")
    rendered_rows = [
        row
        for row in rows
        if row["event"] == "FRAME"
        and row["action"] == "FRAME_RENDERED"
        and row.get("pts_us") is not None
    ]
    rendered_rows.sort(key=lambda row: row.get("event_ts_ns") or 0)
    _annotate_pts_generations(rendered_rows, "pts_us", scope_key="session_id")
    release_to_expected: list[int] = []
    release_to_sampled: list[int] = []
    target_count = 0
    runtime_target_count = 0
    expected_count = 0
    sampled_id_count = 0
    sampled_period_count = 0
    for row in scheduled_rows:
        release_ns = row.get("release_time_ns")
        expected_ns = row.get("expected_presentation_ts_ns")
        sampled_ns = row.get("sampled_vsync_ts_ns")
        if row.get("sampled_vsync_id") is not None:
            sampled_id_count += 1
        if row.get("vsync_duration_ns") is not None:
            sampled_period_count += 1
        if release_ns is not None:
            base["scheduler"]["scheduled_release_timestamp_count"] += 1
        if row.get("runtime_target_vsync_ts_ns") is not None:
            runtime_target_count += 1
        if expected_ns is not None:
            expected_count += 1
            if release_ns is not None:
                release_to_expected.append(release_ns - expected_ns)
        if sampled_ns is not None:
            target_count += 1
            if release_ns is not None:
                release_to_sampled.append(release_ns - sampled_ns)
    base["scheduler"] = {
        "status": "AVAILABLE" if scheduled_rows else "UNKNOWN",
        "frame_event_count": sum(row["event"] == "FRAME" for row in rows),
        "scheduled_timed_release_count": len(scheduled_rows),
        "render_callback_count": len(rendered_rows),
        "render_callback_exact_match_count": sum(
            row.get("render_match") == "PTS_EXACT" for row in rendered_rows
        ),
        "render_callback_unmatched_count": sum(
            row.get("render_match") == "PTS_UNMATCHED" for row in rendered_rows
        ),
        "render_callback_burst_count": sum(
            row.get("render_callback_burst_count") or 0 for row in rendered_rows
        ),
        "render_callback_dispatch_delay": _telemetry_interval_stats(
            [
                row["render_callback_dispatch_delay_ns"]
                for row in rendered_rows
                if row.get("render_callback_dispatch_delay_ns") is not None
            ]
        ),
        "release_call_timestamp_count": sum(row.get("release_call_ts_ns") is not None for row in scheduled_rows),
        "scheduled_release_timestamp_count": sum(row.get("release_time_ns") is not None for row in scheduled_rows),
        "target_vsync_timestamp_count": target_count,
        "runtime_target_vsync_timestamp_count": runtime_target_count,
        "expected_presentation_timestamp_count": expected_count,
        "sampled_vsync_id_count": sampled_id_count,
        "sampled_vsync_period_count": sampled_period_count,
        "release_to_expected_presentation": _telemetry_interval_stats(release_to_expected),
        "release_to_sampled_vsync": _telemetry_interval_stats(release_to_sampled),
        "target_vsync_source": (
            "Media3 runtime chosenTargetVsyncNs"
            if runtime_target_count
            else "Media3 telemetry detail sampledVsyncNs/expectedPresentationNs"
        ),
        "note": (
            "These are scheduler decisions in CLOCK_MONOTONIC converted through the trace snapshot. "
            "They are not an exact SurfaceFlinger buffer identity join."
        ),
    }
    base["_scheduled_rows"] = scheduled_rows
    base["_rendered_rows"] = rendered_rows
    base["_all_rows"] = rows
    base["note"] = (
        "Only rows strictly inside the trace bounds feed source/scheduler pairing. Rows within the +/-1 s "
        "calibration margin are counted separately and never used as measurement-window evidence."
    )
    return base


def load_native_telemetry(
    telemetry_path: str | Path | None,
    trace_bounds_info: Mapping[str, Any],
    calibration: Mapping[str, Any],
) -> dict[str, Any]:
    """Read the native IJK queue/display handoff CSV.

    ``queue_picture`` and ``SDL_VoutDisplayYUVOverlay`` run in the IJK/native
    path and are intentionally kept separate from MediaCodec and
    SurfaceFlinger identities.  Their timestamps are CLOCK_MONOTONIC and are
    converted to the Perfetto trace clock only through clock_snapshot.  The
    resulting PTS/present association is temporal-only; neither event carries
    the SurfaceFlinger frame number or a codec buffer identity.

    Older builds wrote two comment lines but no CSV header.  The parser accepts
    both that format and the header emitted by the current native writer.
    """
    base: dict[str, Any] = {
        "status": "NOT_PROVIDED",
        "path": None if telemetry_path is None else str(telemetry_path),
        "native_telemetry_build": None,
        "rows_read": 0,
        "invalid_row_count": 0,
        "rows_in_trace_window": 0,
        "rows_in_trace_margin_only": 0,
        "rows_outside_trace_window": 0,
        "trace_window_margin_ms": 1000.0,
        "event_counts": {},
        "queue_picture_count": 0,
        "display_picture_count": 0,
        "scheduler_event_counts": {},
        "vp_duration_count": 0,
        "vp_duration_context_count": 0,
        "source_mode": {
            "status": "UNKNOWN",
            "pts_status": "NOT_AVAILABLE",
            "source_fps": None,
            "pts_count": 0,
            "positive_pts_delta_count": 0,
            "nonpositive_pts_delta_count": 0,
            "pts_delta_median_ms": None,
            "queue_picture_count": 0,
            "display_picture_count": 0,
            "codec_format_status": "UNKNOWN_NO_MEDIAFORMAT_FIELDS",
            "render_route_status": "UNKNOWN",
            "evidence_source": "native_refresh_samples.csv",
        },
        "clock_calibration": dict(calibration),
        "note": "No native queue/display telemetry was supplied for this trace.",
    }
    if telemetry_path is None:
        return base
    path = Path(telemetry_path)
    if not path.is_file():
        base["status"] = "MISSING"
        base["note"] = "The requested native telemetry CSV does not exist."
        return base

    start_ns = to_int(trace_bounds_info.get("start_ts_ns"))
    end_ns = to_int(trace_bounds_info.get("end_ts_ns"))
    margin_ns = 1_000_000_000
    queue_rows: list[dict[str, Any]] = []
    display_rows: list[dict[str, Any]] = []
    scheduler_rows: list[dict[str, Any]] = []
    vp_duration_rows: list[dict[str, Any]] = []
    vp_duration_context_rows: list[dict[str, Any]] = []

    def data_lines(handle: Any) -> Iterable[str]:
        for line in handle:
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.startswith("#"):
                if stripped.startswith("# nativeTelemetryBuild="):
                    base["native_telemetry_build"] = stripped.split("=", 1)[1].strip()
                continue
            yield line

    def consume_values(values: Sequence[str]) -> None:
        if len(values) < 4:
            base["invalid_row_count"] += 1
            return
        timestamp_ns_mono = to_int(values[0].strip())
        event = values[1].strip()
        value_ns = to_int(values[2].strip())
        auxiliary_ns = to_int(values[3].strip())
        if timestamp_ns_mono is None or not event or value_ns is None or auxiliary_ns is None:
            base["invalid_row_count"] += 1
            return
        base["rows_read"] += 1
        event_ts_ns = convert_monotonic_to_trace_ns(timestamp_ns_mono, calibration)
        if event_ts_ns is None:
            base["invalid_row_count"] += 1
            return
        in_extended_window = (
            start_ns is None
            or end_ns is None
            or start_ns - margin_ns <= event_ts_ns <= end_ns + margin_ns
        )
        if not in_extended_window:
            base["rows_outside_trace_window"] += 1
            return
        in_strict_window = (
            start_ns is None
            or end_ns is None
            or start_ns <= event_ts_ns <= end_ns
        )
        if not in_strict_window:
            base["rows_in_trace_margin_only"] += 1
            return
        base["rows_in_trace_window"] += 1
        base["event_counts"][event] = base["event_counts"].get(event, 0) + 1
        row = {
            "event": event,
            "timestamp_ns_mono": timestamp_ns_mono,
            "event_ts_ns": event_ts_ns,
            "value_ns": value_ns,
            "auxiliary_ns": auxiliary_ns,
            "pts_ns": value_ns if event in {"ijk_queue_picture", "ijk_display_picture"} else None,
            "pts_us": value_ns // 1000 if event in {"ijk_queue_picture", "ijk_display_picture"} else None,
            "serial": auxiliary_ns if event in {"ijk_queue_picture", "ijk_display_picture"} else None,
        }
        if event == "ijk_queue_picture":
            queue_rows.append(row)
        elif event == "ijk_display_picture":
            display_rows.append(row)
        elif event in {"frame_queue_wait", "compute_target"}:
            scheduler_rows.append(row)
        elif event == "vp_duration":
            vp_duration_rows.append(row)
        elif event == "vp_duration_context":
            vp_duration_context_rows.append(row)

    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.reader(data_lines(handle))
            first = next(reader, None)
            if first is not None:
                normalized = [value.strip().lower() for value in first]
                if not (
                    len(normalized) >= 4
                    and normalized[0] == "timestamp_ns"
                    and normalized[1] == "event"
                ):
                    consume_values(first)
            for values in reader:
                consume_values(values)
    except (OSError, UnicodeError, csv.Error) as exc:
        base["status"] = "INVALID"
        base["note"] = f"Unable to parse native telemetry CSV: {type(exc).__name__}: {exc}"
        return base

    queue_rows.sort(key=lambda row: (row["event_ts_ns"], row["timestamp_ns_mono"]))
    display_rows.sort(key=lambda row: (row["event_ts_ns"], row["timestamp_ns_mono"]))
    _annotate_pts_generations(queue_rows, "pts_us")
    _annotate_pts_generations(display_rows, "pts_us")
    base["queue_picture_count"] = len(queue_rows)
    base["display_picture_count"] = len(display_rows)
    base["scheduler_event_counts"] = {
        event: sum(1 for row in scheduler_rows if row["event"] == event)
        for event in ("frame_queue_wait", "compute_target")
    }
    base["vp_duration_count"] = len(vp_duration_rows)
    base["vp_duration_context_count"] = len(vp_duration_context_rows)
    base["status"] = "AVAILABLE" if base["rows_in_trace_window"] else "EMPTY_IN_TRACE_WINDOW"

    pts_values = [row["pts_ns"] for row in queue_rows if row["pts_ns"] is not None]
    pts_deltas = [current - previous for previous, current in zip(pts_values, pts_values[1:])]
    positive_pts_deltas = [delta for delta in pts_deltas if delta > 0]
    pts_metrics = _pts_cadence_metrics(pts_values, unit_ns=1)
    source_fps = pts_metrics["source_fps"]
    source_status = (
        "VERIFIED_IJK_QUEUE_PTS_AVERAGE"
        if len(positive_pts_deltas) >= 15 and source_fps is not None
        else "UNKNOWN"
    )
    route_status = (
        "IJK_QUEUE_TO_EXTERNAL_DISPLAY_CALLBACK_OBSERVED"
        if display_rows
        else "IJK_QUEUE_PICTURE_ONLY"
        if queue_rows
        else "UNKNOWN"
    )
    base["source_mode"] = {
        "status": source_status,
        "pts_status": "VERIFIED" if positive_pts_deltas else "UNKNOWN",
        "source_fps": source_fps,
        "pts_count": len(pts_values),
        "positive_pts_delta_count": len(positive_pts_deltas),
        "nonpositive_pts_delta_count": sum(delta <= 0 for delta in pts_deltas),
        "first_pts_ns": pts_values[0] if pts_values else None,
        "last_pts_ns": pts_values[-1] if pts_values else None,
        "pts_span_ms": pts_metrics["pts_span_ms"],
        "pts_delta_median_ms": pts_metrics["pts_delta_median_ms"],
        "pts_delta_mean_ms": pts_metrics["pts_delta_mean_ms"],
        "positive_pts_delta_count": pts_metrics["positive_pts_delta_count"],
        "pts_delta_histogram": pts_metrics["pts_delta_histogram"],
        "queue_picture_count": len(queue_rows),
        "display_picture_count": len(display_rows),
        "codec_format_status": "UNKNOWN_NO_MEDIAFORMAT_FIELDS",
        "render_route_status": route_status,
        "evidence_source": "native_refresh_samples.csv:ijk_queue_picture.value_ns",
        "quantized_timestamp_note": pts_metrics["quantized_timestamp_note"],
        "note": (
            "PTS is read from IJK queue_picture before the external-renderer callback. "
            "It verifies the observed IJK queue cadence, not codec format metadata or a "
            "one-to-one SurfaceFlinger presentation of every source frame."
        ),
    }
    base["note"] = (
        "Only native rows strictly inside the trace bounds feed source/handoff pairing. "
        "Rows within the +/-1 s calibration margin are counted separately and never used "
        "as measurement-window evidence."
    )
    base["_queue_rows"] = queue_rows
    base["_display_rows"] = display_rows
    base["_scheduler_rows"] = scheduler_rows
    base["_vp_duration_rows"] = vp_duration_rows
    base["_vp_duration_context_rows"] = vp_duration_context_rows
    return base


def _annotate_pts_generations(
    rows: list[dict[str, Any]],
    pts_key: str,
    scope_key: str | None = None,
) -> None:
    """Annotate source-local PTS epochs without treating them as cross-source IDs."""
    states: dict[Any, dict[str, Any]] = {}
    ordered_rows = sorted(
        rows,
        key=lambda row: (
            to_int(row.get("event_ts_ns"), 0),
            to_int(row.get("event_ns_mono"), 0),
            to_int(row.get("timestamp_ns_mono"), 0),
        ),
    )
    for row in ordered_rows:
        scope = row.get(scope_key) if scope_key else None
        state = states.setdefault(scope, {"last_pts": None, "generation": 0})
        pts = to_int(row.get(pts_key))
        if pts is not None and state["last_pts"] is not None and pts < state["last_pts"]:
            state["generation"] += 1
        row["pts_generation"] = state["generation"]
        if pts is not None:
            state["last_pts"] = pts


def match_rows_by_identity(
    expected_rows: Sequence[Mapping[str, Any]],
    actual_rows: Sequence[Mapping[str, Any]],
    *,
    limit_ns: int = NATIVE_PTS_MATCH_WINDOW_NS,
    expected_pts_key: str = "pts_us",
    actual_pts_key: str = "pts_us",
    expected_time_key: str = "event_ts_ns",
    actual_time_key: str = "event_ts_ns",
    expected_serial_key: str = "serial",
    actual_serial_key: str = "serial",
    require_serial_match: bool = False,
    require_generation_match: bool = False,
) -> dict[str, Any]:
    """Match rows by PTS first, then use a bounded temporal fallback.

    Exact PTS matching is still not a SurfaceFlinger identity join.  It is a
    source-to-source occurrence join, guarded by a time window and one-to-one
    consumption.  Serial and source-local PTS generation are optional guards
    for rows that share those identity domains, such as IJK queue/display.
    """
    ordered_expected = sorted(
        enumerate(expected_rows),
        key=lambda item: (
            to_int(item[1].get("event_ts_ns"), 0),
            to_int(item[1].get("event_ns_mono"), 0),
            to_int(item[1].get("timestamp_ns_mono"), 0),
            item[0],
        ),
    )
    ordered_actual = sorted(
        enumerate(actual_rows),
        key=lambda item: (
            to_int(item[1].get("event_ts_ns"), 0),
            to_int(item[1].get("event_ns_mono"), 0),
            to_int(item[1].get("timestamp_ns_mono"), 0),
            item[0],
        ),
    )
    used_actual: set[int] = set()
    records: list[dict[str, Any]] = []
    ambiguous_exact_count = 0
    exact_pts_count = 0
    temporal_only_fallback_count = 0

    def compatible(expected: Mapping[str, Any], actual: Mapping[str, Any]) -> bool:
        if require_serial_match:
            expected_serial = expected.get(expected_serial_key)
            actual_serial = actual.get(actual_serial_key)
            if (
                expected_serial is not None
                and actual_serial is not None
                and expected_serial != actual_serial
            ):
                return False
        if require_generation_match:
            expected_generation = expected.get("pts_generation")
            actual_generation = actual.get("pts_generation")
            if (
                expected_generation is not None
                and actual_generation is not None
                and expected_generation != actual_generation
            ):
                return False
        return True

    def row_time(row: Mapping[str, Any], key: str) -> int | None:
        value = to_int(row.get(key))
        if value is None and key != "event_ts_ns":
            return to_int(row.get("event_ts_ns"))
        return value

    exact_assignments: dict[int, tuple[int, int]] = {}
    for expected_position, (expected_index, expected) in enumerate(ordered_expected):
        expected_ts = row_time(expected, expected_time_key)
        expected_pts = to_int(expected.get(expected_pts_key))
        exact_candidates: list[tuple[int, int, int]] = []
        if expected_pts is not None:
            for actual_position, (actual_index, actual) in enumerate(ordered_actual):
                if actual_index in used_actual or not compatible(expected, actual):
                    continue
                actual_pts = to_int(actual.get(actual_pts_key))
                actual_ts = row_time(actual, actual_time_key)
                if actual_pts != expected_pts or expected_ts is None or actual_ts is None:
                    continue
                delta = abs(actual_ts - expected_ts)
                if delta <= limit_ns:
                    exact_candidates.append((delta, actual_position, actual_index))
        if exact_candidates:
            if len(exact_candidates) > 1:
                ambiguous_exact_count += 1
            _, actual_position, actual_index = min(
                exact_candidates,
                key=lambda candidate: (candidate[0], candidate[1], candidate[2]),
            )
            exact_assignments[expected_position] = (actual_position, actual_index)
            used_actual.add(actual_index)
            exact_pts_count += 1

    def make_record(
        expected_position: int,
        actual_position: int,
        actual_index: int,
        link: str,
    ) -> dict[str, Any]:
        expected_index, expected = ordered_expected[expected_position]
        actual = dict(actual_rows[actual_index])
        expected_ts = row_time(expected, expected_time_key)
        expected_pts = to_int(expected.get(expected_pts_key))
        actual_ts = row_time(actual, actual_time_key)
        actual_pts = to_int(actual.get(actual_pts_key))
        return {
            "expected_position": expected_position,
            "actual_position": actual_position,
            "expected_index": expected_index,
            "actual_index": actual_index,
            "expected": expected,
            "actual": actual,
            "link": link,
            "event_delta_ns": (
                None
                if expected_ts is None or actual_ts is None
                else actual_ts - expected_ts
            ),
            "pts_mismatch": (
                expected_pts is None
                or actual_pts is None
                or expected_pts != actual_pts
            ),
            "generation_match": (
                expected.get("pts_generation") is None
                or actual.get("pts_generation") is None
                or expected.get("pts_generation") == actual.get("pts_generation")
            ),
        }

    for expected_position, exact_assignment in exact_assignments.items():
        actual_position, actual_index = exact_assignment
        records.append(make_record(expected_position, actual_position, actual_index, "PTS_EXACT"))

    for expected_position, (expected_index, expected) in enumerate(ordered_expected):
        if expected_position in exact_assignments:
            continue
        expected_ts = row_time(expected, expected_time_key)
        temporal_candidates: list[tuple[int, int, int]] = []
        if expected_ts is not None:
            for actual_position, (actual_index, actual) in enumerate(ordered_actual):
                if actual_index in used_actual or not compatible(expected, actual):
                    continue
                actual_ts = row_time(actual, actual_time_key)
                if actual_ts is None:
                    continue
                delta = abs(actual_ts - expected_ts)
                if delta <= limit_ns:
                    temporal_candidates.append((delta, actual_position, actual_index))
        if not temporal_candidates:
            continue
        _, actual_position, actual_index = min(
            temporal_candidates,
            key=lambda candidate: (candidate[0], candidate[1], candidate[2]),
        )
        used_actual.add(actual_index)
        records.append(
            make_record(
                expected_position,
                actual_position,
                actual_index,
                "TEMPORAL_ONLY_FALLBACK",
            )
        )
        temporal_only_fallback_count += 1

    records.sort(key=lambda record: (record["expected_position"], record["actual_position"]))

    expected_count = len(ordered_expected)
    matched_count = len(records)
    return {
        "records": records,
        "expected_count": expected_count,
        "actual_count": len(ordered_actual),
        "matched_count": matched_count,
        "exact_pts_count": exact_pts_count,
        "temporal_only_fallback_count": temporal_only_fallback_count,
        "unmatched_count": expected_count - matched_count,
        "unmatched_actual_count": len(ordered_actual) - len(used_actual),
        "ambiguous_exact_count": ambiguous_exact_count,
        "exact_pts_ratio": exact_pts_count / expected_count if expected_count else None,
        "fallback_ratio": temporal_only_fallback_count / expected_count if expected_count else None,
        "limit_ms": limit_ns / 1e6,
    }


def _temporal_match_rows_to_presents(
    video_frame_timing: Sequence[Mapping[str, Any]],
    rows: Sequence[Mapping[str, Any]],
    limit_ns: int,
) -> dict[int, Mapping[str, Any]]:
    ordered_rows = sorted(rows, key=lambda row: (row.get("event_ts_ns", 0), row.get("timestamp_ns_mono", 0)))
    anchors = [to_int(row.get("event_ts_ns"), 0) for row in ordered_rows]
    used: set[int] = set()
    matches: dict[int, Mapping[str, Any]] = {}
    for item_index, item in enumerate(video_frame_timing):
        present_ts = to_int(item.get("present_ts_ns"))
        if present_ts is None:
            continue
        position = bisect.bisect_left(anchors, present_ts)
        choices = [
            index
            for index in (position - 1, position, position + 1)
            if 0 <= index < len(ordered_rows) and index not in used
        ]
        if not choices:
            continue
        match_index = min(choices, key=lambda index: abs(anchors[index] - present_ts))
        if abs(anchors[match_index] - present_ts) > limit_ns:
            continue
        used.add(match_index)
        matches[item_index] = ordered_rows[match_index]
    return matches


def attach_scheduler_native_telemetry(
    telemetry: Mapping[str, Any],
    native_telemetry: Mapping[str, Any],
) -> dict[str, Any]:
    """Join codec scheduler rows to native IJK observations by PTS occurrence.

    This is a source-to-source handoff check.  It deliberately does not claim
    that the matched IJK row became a particular SurfaceFlinger buffer.
    """
    scheduled_rows = [
        row for row in telemetry.get("_scheduled_rows", [])
        if row.get("pts_us") is not None
    ]
    queue_rows = list(native_telemetry.get("_queue_rows", []))
    display_rows = list(native_telemetry.get("_display_rows", []))
    if not scheduled_rows:
        return {
            "status": "UNKNOWN",
            "evidence_level": "NONE",
            "queue": {
                "status": "UNKNOWN",
                "evidence_level": "NONE",
                "expected_count": 0,
                "exact_pts_count": 0,
                "temporal_only_fallback_count": 0,
                "unmatched_count": 0,
            },
            "display": {
                "status": "UNKNOWN",
                "evidence_level": "NONE",
                "expected_count": 0,
                "exact_pts_count": 0,
                "temporal_only_fallback_count": 0,
                "unmatched_count": 0,
            },
            "session_ids": [],
            "match_window_ms": NATIVE_PTS_MATCH_WINDOW_NS / 1e6,
            "note": "No scheduled codec rows with PTS were available in the trace window.",
        }

    def summarize(
        native_rows: Sequence[Mapping[str, Any]],
        evidence_level: str,
    ) -> dict[str, Any]:
        result = match_rows_by_identity(
            scheduled_rows,
            native_rows,
            limit_ns=NATIVE_PTS_MATCH_WINDOW_NS,
            require_serial_match=False,
            require_generation_match=False,
        )
        mismatch_count = sum(
            bool(record["pts_mismatch"])
            for record in result["records"]
            if record["link"] == "TEMPORAL_ONLY_FALLBACK"
        )
        exact_generation_mismatch_count = sum(
            not record["generation_match"]
            for record in result["records"]
            if record["link"] == "PTS_EXACT"
        )
        if result["exact_pts_count"] and not result["temporal_only_fallback_count"]:
            status = "PTS_EXACT"
        elif result["exact_pts_count"]:
            status = "PTS_EXACT_WITH_TEMPORAL_FALLBACK"
        elif result["temporal_only_fallback_count"]:
            status = "TEMPORAL_ONLY_FALLBACK"
        else:
            status = "UNMATCHED"
        return {
            "status": status,
            "evidence_level": evidence_level,
            "expected_count": result["expected_count"],
            "actual_count": result["actual_count"],
            "matched_count": result["matched_count"],
            "exact_pts_count": result["exact_pts_count"],
            "exact_pts_ratio": result["exact_pts_ratio"],
            "temporal_only_fallback_count": result["temporal_only_fallback_count"],
            "fallback_ratio": result["fallback_ratio"],
            "unmatched_count": result["unmatched_count"],
            "unmatched_actual_count": result["unmatched_actual_count"],
            "ambiguous_exact_count": result["ambiguous_exact_count"],
            "fallback_pts_mismatch_count": mismatch_count,
            "exact_generation_mismatch_count": exact_generation_mismatch_count,
            "match_window_ms": result["limit_ms"],
            "one_to_one_consumption": True,
            "note": (
                "PTS exact means the codec scheduler PTS equals the native observation PTS within a bounded "
                "occurrence window. It is not a codec-buffer-to-SurfaceFlinger identity link."
            ),
        }

    queue_summary = summarize(queue_rows, "CODEC_TO_NATIVE_QUEUE_PTS_EXACT")
    display_summary = summarize(display_rows, "NATIVE_DISPLAY_PTS_INFERRED")
    session_ids = sorted({row.get("session_id") for row in scheduled_rows if row.get("session_id")})
    evidence_levels = {queue_summary["evidence_level"], display_summary["evidence_level"]}
    exact_count = queue_summary["exact_pts_count"] + display_summary["exact_pts_count"]
    fallback_count = (
        queue_summary["temporal_only_fallback_count"]
        + display_summary["temporal_only_fallback_count"]
    )
    status = "PTS_EXACT" if exact_count and not fallback_count else (
        "PTS_EXACT_WITH_TEMPORAL_FALLBACK" if exact_count else "TEMPORAL_ONLY_FALLBACK"
        if fallback_count else "UNMATCHED"
    )
    return {
        "status": status,
        "evidence_level": sorted(evidence_levels),
        "queue": queue_summary,
        "display": display_summary,
        "session_ids": session_ids,
        "scheduled_session_count": len(session_ids),
        "match_window_ms": NATIVE_PTS_MATCH_WINDOW_NS / 1e6,
        "one_to_one_consumption": True,
        "note": (
            "Scheduler/native PTS occurrences are now matched before any temporal fallback. Native display "
            "PTS remains an inferred callback value, and neither branch proves final SurfaceFlinger identity."
        ),
    }


def attach_scheduler_render_telemetry(
    telemetry: Mapping[str, Any],
) -> dict[str, Any]:
    """Match scheduler rows to MediaCodec render callbacks with frame identity first.

    ``OnFrameRenderedListener.nanoTime`` is not treated as an independent present
    timestamp here.  On the IJK path it can equal the requested release time,
    including a requested time that is already stale.  The codec-frame identity
    is therefore the primary join; rendered nano time is retained only as a
    signed timing field and as a bounded fallback key.
    """
    scheduled_rows = [
        row for row in telemetry.get("_scheduled_rows", [])
        if row.get("pts_us") is not None
    ]
    rendered_rows = [
        row for row in telemetry.get("_rendered_rows", [])
        if row.get("pts_us") is not None
    ]
    if not scheduled_rows:
        return {
            "status": "UNKNOWN",
            "evidence_level": "NONE",
            "expected_count": 0,
            "render_callback_count": len(rendered_rows),
            "exact_pts_count": 0,
            "exact_identity_count": 0,
            "temporal_only_fallback_count": 0,
            "unmatched_count": 0,
            "raw_unmatched_count": 0,
            "match_time_key": "rendered_nano_ts_ns",
            "match_window_ms": RENDER_CALLBACK_MATCH_WINDOW_NS / 1e6,
            "note": "No scheduled codec rows with PTS were available in the trace window.",
        }

    # The callback may be dispatched now while reporting a nanoTime from an
    # already-expired requested release.  A time-window-first join therefore
    # turns valid callbacks into false unmatched rows.  Consume each callback
    # by the complete codec-frame identity before attempting temporal fallback.
    identity_fields = (
        "session_id",
        "codec",
        "frame_index",
        "dequeued_at_ns",
        "pts_us",
        "buffer_index",
    )

    def frame_identity(row: Mapping[str, Any]) -> tuple[Any, ...] | None:
        values = tuple(row.get(field) for field in identity_fields)
        return None if any(value is None for value in values) else values

    callbacks_by_identity: dict[tuple[Any, ...], deque[int]] = defaultdict(deque)
    for actual_index, actual in enumerate(rendered_rows):
        identity = frame_identity(actual)
        if identity is not None:
            callbacks_by_identity[identity].append(actual_index)

    used_expected: set[int] = set()
    used_actual: set[int] = set()
    matched_pairs: list[tuple[Mapping[str, Any], Mapping[str, Any], str]] = []
    for expected_index, expected in enumerate(scheduled_rows):
        identity = frame_identity(expected)
        candidates = callbacks_by_identity.get(identity) if identity is not None else None
        if not candidates:
            continue
        actual_index = candidates.popleft()
        used_expected.add(expected_index)
        used_actual.add(actual_index)
        matched_pairs.append((expected, rendered_rows[actual_index], "COMPOSITE_IDENTITY"))

    remaining_expected = [
        row for index, row in enumerate(scheduled_rows) if index not in used_expected
    ]
    remaining_actual = [
        row for index, row in enumerate(rendered_rows) if index not in used_actual
    ]
    fallback = match_rows_by_identity(
        remaining_expected,
        remaining_actual,
        limit_ns=RENDER_CALLBACK_MATCH_WINDOW_NS,
        actual_time_key="rendered_nano_ts_ns",
        require_generation_match=True,
    )
    for record in fallback["records"]:
        matched_pairs.append((record["expected"], record["actual"], record["link"]))

    exact_identity_count = sum(link == "COMPOSITE_IDENTITY" for _, _, link in matched_pairs)
    fallback_exact_pts_count = fallback["exact_pts_count"]
    fallback_temporal_count = fallback["temporal_only_fallback_count"]
    matched_count = len(matched_pairs)
    raw_unmatched_count = len(scheduled_rows) - matched_count
    raw_unmatched_callback_count = len(rendered_rows) - len(used_actual) - len(fallback["records"])

    trace_bounds = telemetry.get("_trace_bounds", {})
    trace_start_ns = to_int(trace_bounds.get("start_ts_ns"))
    trace_end_ns = to_int(trace_bounds.get("end_ts_ns"))
    unmatched_callback_rows = [
        row
        for index, row in enumerate(remaining_actual)
        if index not in {
            record["actual_index"] for record in fallback["records"]
        }
    ]

    def is_trace_boundary(row: Mapping[str, Any]) -> bool:
        rendered_ns = to_int(row.get("rendered_nano_ts_ns"))
        if rendered_ns is None or trace_start_ns is None or trace_end_ns is None:
            return False
        return rendered_ns < trace_start_ns or rendered_ns > trace_end_ns

    trace_boundary_callbacks = [
        row for row in unmatched_callback_rows if is_trace_boundary(row)
    ]
    true_unmatched_callbacks = [
        row for row in unmatched_callback_rows if not is_trace_boundary(row)
    ]

    exact_matches = sum(
        row.get("render_match") == "PTS_EXACT" for row in rendered_rows
    )
    unmatched_callbacks = len(true_unmatched_callbacks)
    burst_count = sum(row.get("render_callback_burst_count") or 0 for row in rendered_rows)
    dispatch_delays = [
        row["render_callback_dispatch_delay_ns"]
        for row in rendered_rows
        if row.get("render_callback_dispatch_delay_ns") is not None
    ]
    if exact_identity_count and not fallback_temporal_count and not raw_unmatched_count:
        status = "COMPOSITE_IDENTITY_EXACT"
    elif exact_identity_count:
        status = "COMPOSITE_IDENTITY_WITH_FALLBACK"
    elif fallback["exact_pts_count"]:
        status = "PTS_EXACT_WITH_TEMPORAL_FALLBACK"
    elif fallback_temporal_count:
        status = "TEMPORAL_ONLY_FALLBACK"
    else:
        status = "UNMATCHED"

    requested_release_minus_release_call = []
    callback_observed_minus_release_call = []
    callback_nano_minus_requested_release = []
    stale_requested_release_samples = []
    stale_threshold_ns = 1_000_000_000
    for expected, actual, _ in matched_pairs:
        release_call_ns = to_int(expected.get("release_call_ts_ns"))
        if release_call_ns is None:
            release_call_ns = to_int(expected.get("event_ts_ns"))
        requested_release_ns = to_int(expected.get("release_time_ns"))
        callback_observed_ns = to_int(actual.get("render_callback_observed_ts_ns"))
        callback_nano_ns = to_int(actual.get("rendered_nano_ts_ns"))
        if requested_release_ns is not None and release_call_ns is not None:
            delta = requested_release_ns - release_call_ns
            requested_release_minus_release_call.append(delta)
            if delta <= -stale_threshold_ns:
                stale_requested_release_samples.append({
                    "frame_index": expected.get("frame_index"),
                    "pts_us": expected.get("pts_us"),
                    "delta_ms": delta / 1e6,
                })
        if callback_observed_ns is not None and release_call_ns is not None:
            callback_observed_minus_release_call.append(callback_observed_ns - release_call_ns)
        if callback_nano_ns is not None and requested_release_ns is not None:
            callback_nano_minus_requested_release.append(callback_nano_ns - requested_release_ns)

    return {
        "status": status,
        "evidence_level": "CODEC_TO_RENDER_CALLBACK_COMPOSITE_IDENTITY",
        "expected_count": len(scheduled_rows),
        "render_callback_count": len(rendered_rows),
        "matched_count": matched_count,
        "exact_pts_count": exact_identity_count + fallback_exact_pts_count,
        "exact_identity_count": exact_identity_count,
        "exact_identity_ratio": (
            exact_identity_count / len(scheduled_rows) if scheduled_rows else None
        ),
        "exact_pts_ratio": (
            (exact_identity_count + fallback_exact_pts_count) / len(scheduled_rows)
            if scheduled_rows else None
        ),
        "temporal_only_fallback_count": fallback_temporal_count,
        "unmatched_count": raw_unmatched_count,
        "raw_unmatched_count": raw_unmatched_count,
        "unmatched_callback_count": unmatched_callbacks,
        "raw_unmatched_callback_count": raw_unmatched_callback_count,
        "trace_boundary_callback_count": len(trace_boundary_callbacks),
        "ambiguous_exact_count": fallback["ambiguous_exact_count"],
        "render_match_exact_count": exact_matches,
        "render_callback_unmatched_count": unmatched_callbacks,
        "render_callback_burst_count": burst_count,
        "render_callback_dispatch_delay": _telemetry_interval_stats(dispatch_delays),
        "requested_release_minus_release_call": _telemetry_interval_stats(
            requested_release_minus_release_call
        ),
        "callback_observed_minus_release_call": _telemetry_interval_stats(
            callback_observed_minus_release_call
        ),
        "callback_nano_minus_requested_release": _telemetry_interval_stats(
            callback_nano_minus_requested_release
        ),
        "stale_requested_release_threshold_ms": stale_threshold_ns / 1e6,
        "stale_requested_release_count": len(stale_requested_release_samples),
        "stale_requested_release_samples": stale_requested_release_samples[:32],
        "session_ids": sorted({
            row.get("session_id") for row in scheduled_rows if row.get("session_id")
        }),
        "identity_key_fields": list(identity_fields),
        "match_time_key": "codec_frame_identity_then_rendered_nano_ts_ns",
        "match_window_ms": fallback["limit_ms"],
        "one_to_one_consumption": True,
        "note": (
            "Scheduler/render rows are matched by complete codec-frame identity first. The callback's "
            "rendered nanoTime is retained as a codec-reported timestamp and bounded temporal fallback, "
            "not an independently measured Surface or panel-present timestamp."
        ),
    }


def attach_native_telemetry(
    video_frame_timing: list[dict[str, Any]],
    native_telemetry: Mapping[str, Any],
) -> dict[str, Any]:
    """Attach native observations while keeping source identity separate from PresentFence."""
    queue_rows = list(native_telemetry.get("_queue_rows", []))
    display_rows = list(native_telemetry.get("_display_rows", []))
    queue_display_identity = match_rows_by_identity(
        queue_rows,
        display_rows,
        limit_ns=NATIVE_PTS_MATCH_WINDOW_NS,
        require_serial_match=True,
        require_generation_match=True,
    )
    ordered_queue = [row for _, row in sorted(
        enumerate(queue_rows),
        key=lambda item: (
            to_int(item[1].get("event_ts_ns"), 0),
            to_int(item[1].get("timestamp_ns_mono"), 0),
            item[0],
        ),
    )]
    ordered_display = [row for _, row in sorted(
        enumerate(display_rows),
        key=lambda item: (
            to_int(item[1].get("event_ts_ns"), 0),
            to_int(item[1].get("timestamp_ns_mono"), 0),
            item[0],
        ),
    )]
    matched_queue_indices = {
        record["expected_position"] for record in queue_display_identity["records"]
    }
    matched_display_indices = {
        record["actual_position"] for record in queue_display_identity["records"]
    }
    native_units: list[dict[str, Any]] = []
    for record in queue_display_identity["records"]:
        queue_row = ordered_queue[record["expected_position"]]
        display_row = ordered_display[record["actual_position"]]
        anchor_row = display_row if display_row.get("event_ts_ns") is not None else queue_row
        native_units.append(
            {
                "event_ts_ns": anchor_row.get("event_ts_ns"),
                "timestamp_ns_mono": anchor_row.get("timestamp_ns_mono"),
                "pts_us": queue_row.get("pts_us"),
                "serial": queue_row.get("serial"),
                "_queue_row": queue_row,
                "_display_row": display_row,
                "_native_identity_link": record["link"],
                "_native_identity_record": record,
            }
        )
    for index, queue_row in enumerate(ordered_queue):
        if index in matched_queue_indices:
            continue
        native_units.append(
            {
                "event_ts_ns": queue_row.get("event_ts_ns"),
                "timestamp_ns_mono": queue_row.get("timestamp_ns_mono"),
                "pts_us": queue_row.get("pts_us"),
                "serial": queue_row.get("serial"),
                "_queue_row": queue_row,
                "_display_row": None,
                "_native_identity_link": "QUEUE_ONLY",
                "_native_identity_record": None,
            }
        )
    for index, display_row in enumerate(ordered_display):
        if index in matched_display_indices:
            continue
        native_units.append(
            {
                "event_ts_ns": display_row.get("event_ts_ns"),
                "timestamp_ns_mono": display_row.get("timestamp_ns_mono"),
                "pts_us": display_row.get("pts_us"),
                "serial": display_row.get("serial"),
                "_queue_row": None,
                "_display_row": display_row,
                "_native_identity_link": "DISPLAY_ONLY",
                "_native_identity_record": None,
            }
        )
    native_units.sort(
        key=lambda row: (
            to_int(row.get("event_ts_ns"), 0),
            to_int(row.get("timestamp_ns_mono"), 0),
        )
    )
    present_matches = _temporal_match_rows_to_presents(
        video_frame_timing, native_units, NATIVE_HANDOFF_TEMPORAL_MATCH_LIMIT_NS
    )
    for index, item in enumerate(video_frame_timing):
        native_unit = present_matches.get(index)
        queue_row = None if native_unit is None else native_unit.get("_queue_row")
        display_row = None if native_unit is None else native_unit.get("_display_row")
        selected_row = display_row or queue_row
        queue_event_ts = None if queue_row is None else queue_row.get("event_ts_ns")
        display_event_ts = None if display_row is None else display_row.get("event_ts_ns")
        item.update(
            {
                "native_pts_us": None if selected_row is None else selected_row.get("pts_us"),
                "native_serial": None if selected_row is None else selected_row.get("serial"),
                "native_queue_picture_ts_ns": queue_event_ts,
                "native_display_picture_ts_ns": display_event_ts,
                "native_handoff_event": None if selected_row is None else selected_row.get("event"),
                "native_handoff_link": "TEMPORAL_ONLY" if selected_row is not None else "UNMATCHED",
                "native_identity_link": (
                    "UNMATCHED" if native_unit is None else native_unit.get("_native_identity_link", "UNKNOWN")
                ),
                "native_handoff_temporal_match": (
                    None
                    if selected_row is None
                    else {
                        "present_minus_queue_picture_ns": (
                            None if queue_event_ts is None else item["present_ts_ns"] - queue_event_ts
                        ),
                        "present_minus_display_picture_ns": (
                            None if display_event_ts is None else item["present_ts_ns"] - display_event_ts
                        ),
                        "queue_pts_ns": None if queue_row is None else queue_row.get("pts_ns"),
                        "display_pts_ns": None if display_row is None else display_row.get("pts_ns"),
                        "queue_serial": None if queue_row is None else queue_row.get("serial"),
                        "display_serial": None if display_row is None else display_row.get("serial"),
                        "native_identity_link": native_unit.get("_native_identity_link"),
                        "native_identity_pts_exact": (
                            native_unit.get("_native_identity_link") == "PTS_EXACT"
                        ),
                        "note": (
                            "PresentFence is matched temporally to a native identity unit. Queue/display identity "
                            "uses exact PTS, serial, generation, and one-to-one consumption when available; "
                            "PresentFence still has no shared codec or renderer buffer identity."
                        ),
                    }
                ),
            }
        )
    total = len(video_frame_timing)
    queue_present_deltas = [
        item["native_handoff_temporal_match"]["present_minus_queue_picture_ns"]
        for item in video_frame_timing
        if item.get("native_handoff_temporal_match") is not None
        and item["native_handoff_temporal_match"].get("present_minus_queue_picture_ns") is not None
    ]
    display_present_deltas = [
        item["native_handoff_temporal_match"]["present_minus_display_picture_ns"]
        for item in video_frame_timing
        if item.get("native_handoff_temporal_match") is not None
        and item["native_handoff_temporal_match"].get("present_minus_display_picture_ns") is not None
    ]
    matched = len(present_matches)
    identity_records = queue_display_identity["records"]
    return {
        "status": "TEMPORAL_ONLY" if matched else "UNKNOWN",
        "exact_identity_count": 0,
        "temporal_only_count": matched,
        "unmatched_count": total - matched,
        "exact_identity_ratio": 0.0 if total else None,
        "temporal_only_ratio": matched / total if total else None,
        "unmatched_ratio": (total - matched) / total if total else None,
        "queue_temporal_match_count": sum(
            1 for unit in present_matches.values() if unit.get("_queue_row") is not None
        ),
        "display_temporal_match_count": sum(
            1 for unit in present_matches.values() if unit.get("_display_row") is not None
        ),
        "native_identity_exact_pts_count": queue_display_identity["exact_pts_count"],
        "native_identity_temporal_only_fallback_count": queue_display_identity[
            "temporal_only_fallback_count"
        ],
        "native_identity_unmatched_queue_count": queue_display_identity["unmatched_count"],
        "native_identity_unmatched_display_count": queue_display_identity[
            "unmatched_actual_count"
        ],
        "native_identity_ambiguous_exact_count": queue_display_identity[
            "ambiguous_exact_count"
        ],
        "present_minus_queue_picture": _telemetry_interval_stats(queue_present_deltas),
        "present_minus_display_picture": _telemetry_interval_stats(display_present_deltas),
        "queue_display_pair_count": len(identity_records),
        "queue_display_same_pts_count": sum(
            not record["pts_mismatch"] for record in identity_records
        ),
        "queue_display_pts_mismatch_count": sum(
            record["pts_mismatch"] for record in identity_records
        ),
        "queue_display_pts_same_ratio": (
            sum(not record["pts_mismatch"] for record in identity_records) / len(identity_records)
            if identity_records else None
        ),
        "native_identity_match_window_ms": NATIVE_PTS_MATCH_WINDOW_NS / 1e6,
        "native_identity_one_to_one_consumption": True,
        "temporal_match_limit_ms": NATIVE_HANDOFF_TEMPORAL_MATCH_LIMIT_NS / 1e6,
        "source": "native_refresh_samples.csv:ijk_queue_picture/ijk_display_picture",
        "note": (
            "Queue/display rows are paired by exact PTS, native serial, source-local generation, and one-to-one "
            "consumption before PresentFence temporal matching. The PresentFence association remains temporal-only "
            "because no codec, external-renderer, or SurfaceFlinger frame identity is shared."
        ),
    }


def analyze_native_scheduler_phase(
    native_telemetry: Mapping[str, Any],
    stage_groups: Mapping[int, Mapping[str, Sequence[Mapping[str, Any]]]],
    fixed_window: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Compare IJK scheduler samples before native queue units that do/don't present.

    Native queue/display identity is exact only inside the IJK telemetry domain. The
    association to a SurfaceFlinger PresentFence remains temporal-only, so this
    function is a phase discriminator rather than a cross-layer frame identity join.
    """
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int(
        (fixed_window or {}).get(
            "end_ts_ns_exclusive", (fixed_window or {}).get("end_ts_exclusive")
        )
    )

    def in_scope(timestamp: int | None) -> bool:
        return (
            timestamp is not None
            and (start_ns is None or timestamp >= start_ns)
            and (end_ns is None or timestamp < end_ns)
        )

    def values(items: Sequence[Mapping[str, Any]], key: str) -> list[int]:
        return [
            to_int(item.get(key))
            for item in items
            if to_int(item.get(key)) is not None
        ]

    def stats(items: Sequence[int | None]) -> dict[str, Any]:
        ordered = sorted(int(value) for value in items if value is not None)
        return {
            "count": len(ordered),
            "median_ns": None if not ordered else int(statistics.median(ordered)),
            "p10_ns": None if not ordered else int(percentile(ordered, 0.10)),
            "p90_ns": None if not ordered else int(percentile(ordered, 0.90)),
            "min_ns": None if not ordered else min(ordered),
            "max_ns": None if not ordered else max(ordered),
        }

    queue_rows = sorted(
        [dict(row) for row in native_telemetry.get("_queue_rows", [])],
        key=lambda row: to_int(row.get("event_ts_ns"), 0),
    )
    scheduler_rows = sorted(
        [dict(row) for row in native_telemetry.get("_scheduler_rows", [])],
        key=lambda row: to_int(row.get("event_ts_ns"), 0),
    )
    queue_timestamps = [to_int(row.get("event_ts_ns")) for row in queue_rows]
    queue_timestamps = [timestamp for timestamp in queue_timestamps if timestamp is not None]
    surface_frames = []
    for frame_number, stages in sorted(stage_groups.items()):
        queue_ts = min(
            (
                to_int(item.get("ts"))
                for item in stages.get("Queue", [])
                if to_int(item.get("ts")) is not None
            ),
            default=None,
        )
        if not in_scope(queue_ts):
            continue
        surface_frames.append(
            {
                "frame_number": frame_number,
                "queue_ts_ns": queue_ts,
                "presented": "PresentFenceSignaled" in stages,
            }
        )
    surface_frames.sort(key=lambda item: to_int(item.get("queue_ts_ns"), 0))
    native_queue_timestamps = [
        to_int(row.get("event_ts_ns"))
        for row in queue_rows
        if to_int(row.get("event_ts_ns")) is not None
    ]
    matched_native_indices: set[int] = set()

    def nearest_native_queue(queue_ts: int) -> int | None:
        position = bisect.bisect_left(native_queue_timestamps, queue_ts)
        candidates = [
            index
            for index in (position - 1, position)
            if 0 <= index < len(native_queue_timestamps)
            and index not in matched_native_indices
        ]
        if not candidates:
            return None
        selected = min(candidates, key=lambda index: abs(native_queue_timestamps[index] - queue_ts))
        if abs(native_queue_timestamps[selected] - queue_ts) > 5_000_000:
            return None
        matched_native_indices.add(selected)
        return selected

    observations: list[dict[str, Any]] = []
    for surface_frame in surface_frames:
        surface_queue_ts = to_int(surface_frame.get("queue_ts_ns"))
        if surface_queue_ts is None:
            continue
        native_index = nearest_native_queue(surface_queue_ts)
        if native_index is None:
            continue
        queue_ts = native_queue_timestamps[native_index]
        previous_queue_ts = (
            native_queue_timestamps[native_index - 1]
            if native_index > 0
            else None
        )
        lower_bound = previous_queue_ts if previous_queue_ts is not None else queue_ts - 20_000_000
        rows = [
            row
            for row in scheduler_rows
            if lower_bound < to_int(row.get("event_ts_ns"), 0) <= queue_ts
        ]
        waits = [row for row in rows if row.get("event") == "frame_queue_wait"]
        computes = [row for row in rows if row.get("event") == "compute_target"]
        observations.append(
            {
                "frame_number": to_int(surface_frame.get("frame_number")),
                "present_ts_ns": None,
                "native_queue_ts_ns": queue_ts,
                "presented": bool(surface_frame.get("presented")),
                "queue_interval_ns": (
                    None if previous_queue_ts is None else queue_ts - previous_queue_ts
                ),
                "frame_queue_wait_total_ns": sum(
                    values(waits, "value_ns")
                ),
                "frame_queue_wait_max_ns": max(
                    values(waits, "value_ns"), default=None
                ),
                "frame_queue_wait_count": len(waits),
                "compute_target_max_ns": max(
                    values(computes, "value_ns"), default=None
                ),
                "compute_target_last_ns": (
                    None if not computes else to_int(computes[-1].get("value_ns"))
                ),
            }
        )

    presented = [item for item in observations if item["presented"]]
    missing = [item for item in observations if not item["presented"]]
    phase_delta = (
        None
        if not presented or not missing
        else stats(values(missing, "queue_interval_ns"))["median_ns"]
        - stats(values(presented, "queue_interval_ns"))["median_ns"]
    )
    wait_delta = (
        None
        if not presented or not missing
        else stats(values(missing, "frame_queue_wait_total_ns"))["median_ns"]
        - stats(values(presented, "frame_queue_wait_total_ns"))["median_ns"]
    )
    compute_delta = (
        None
        if not presented or not missing
        else stats(values(missing, "compute_target_max_ns"))["median_ns"]
        - stats(values(presented, "compute_target_max_ns"))["median_ns"]
    )
    if phase_delta is not None and phase_delta > 500_000 and (
        wait_delta is None or abs(wait_delta) <= 1_000_000
    ) and (compute_delta is None or abs(compute_delta) <= 1_000_000):
        conclusion = "PRODUCER_CADENCE_PHASE_WITHOUT_SCHEDULER_SEPARATION"
    elif (
        wait_delta is not None and abs(wait_delta) > 1_000_000
    ) or (compute_delta is not None and abs(compute_delta) > 1_000_000):
        conclusion = "IJK_SCHEDULER_SAMPLE_SEPARATION"
    else:
        conclusion = "NO_NATIVE_SCHEDULER_SEPARATION"
    return {
        "status": "VALID" if observations else "INSUFFICIENT_DATA",
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "observation_count": len(observations),
        "presented_count": len(presented),
        "missing_present_count": len(missing),
        "presented_queue_interval": stats(values(presented, "queue_interval_ns")),
        "missing_present_queue_interval": stats(values(missing, "queue_interval_ns")),
        "presented_wait_total": stats(values(presented, "frame_queue_wait_total_ns")),
        "missing_present_wait_total": stats(values(missing, "frame_queue_wait_total_ns")),
        "presented_wait_max": stats(values(presented, "frame_queue_wait_max_ns")),
        "missing_present_wait_max": stats(values(missing, "frame_queue_wait_max_ns")),
        "presented_compute_max": stats(values(presented, "compute_target_max_ns")),
        "missing_present_compute_max": stats(values(missing, "compute_target_max_ns")),
        "missing_minus_present_queue_interval_median_ns": phase_delta,
        "missing_minus_present_wait_total_median_ns": wait_delta,
        "missing_minus_present_compute_max_median_ns": compute_delta,
        "conclusion": conclusion,
        "observations": observations,
        "evidence_quality": "TEMPORAL_ONLY",
        "cross_layer_identity_available": False,
        "note": (
            "Native scheduler rows are grouped before the temporally matched IJK queue unit. "
            "They can distinguish a scheduler-duration signal from a cadence phase signal, "
            "but do not create MediaCodec or SurfaceFlinger buffer identity."
        ),
    }


def analyze_native_vp_duration_phase(
    native_telemetry: Mapping[str, Any],
    stage_groups: Mapping[int, Mapping[str, Sequence[Mapping[str, Any]]]],
    fixed_window: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Compare vp_duration decisions before presented and non-presented SF frames.

    The vp_duration call and selected SurfaceFlinger Queue are matched by trace
    time through the native queue cadence. This is intentionally temporal-only;
    it does not identify a shared codec/native/SF buffer.
    """
    start_ns = to_int((fixed_window or {}).get("start_ts_ns"))
    end_ns = to_int(
        (fixed_window or {}).get(
            "end_ts_ns_exclusive", (fixed_window or {}).get("end_ts_exclusive")
        )
    )

    def in_scope(timestamp: int | None) -> bool:
        return (
            timestamp is not None
            and (start_ns is None or timestamp >= start_ns)
            and (end_ns is None or timestamp < end_ns)
        )

    def stats(items: Sequence[int | None]) -> dict[str, Any]:
        ordered = sorted(int(value) for value in items if value is not None)
        return {
            "count": len(ordered),
            "median_ns": None if not ordered else int(statistics.median(ordered)),
            "p10_ns": None if not ordered else int(percentile(ordered, 0.10)),
            "p90_ns": None if not ordered else int(percentile(ordered, 0.90)),
            "min_ns": None if not ordered else min(ordered),
            "max_ns": None if not ordered else max(ordered),
        }

    def values(items: Sequence[Mapping[str, Any]], key: str) -> list[int]:
        return [
            to_int(item.get(key))
            for item in items
            if to_int(item.get(key)) is not None
        ]

    queue_rows = sorted(
        [dict(row) for row in native_telemetry.get("_queue_rows", [])],
        key=lambda row: to_int(row.get("event_ts_ns"), 0),
    )
    vp_rows = sorted(
        [dict(row) for row in native_telemetry.get("_vp_duration_rows", [])],
        key=lambda row: to_int(row.get("event_ts_ns"), 0),
    )
    context_rows = sorted(
        [dict(row) for row in native_telemetry.get("_vp_duration_context_rows", [])],
        key=lambda row: to_int(row.get("event_ts_ns"), 0),
    )
    native_queue_timestamps = [
        to_int(row.get("event_ts_ns"))
        for row in queue_rows
        if to_int(row.get("event_ts_ns")) is not None
    ]
    matched_native_indices: set[int] = set()

    def nearest_native_queue(queue_ts: int) -> int | None:
        position = bisect.bisect_left(native_queue_timestamps, queue_ts)
        candidates = [
            index
            for index in (position - 1, position)
            if 0 <= index < len(native_queue_timestamps)
            and index not in matched_native_indices
        ]
        if not candidates:
            return None
        selected = min(candidates, key=lambda index: abs(native_queue_timestamps[index] - queue_ts))
        if abs(native_queue_timestamps[selected] - queue_ts) > 5_000_000:
            return None
        matched_native_indices.add(selected)
        return selected

    surface_frames = []
    for frame_number, stages in sorted(stage_groups.items()):
        queue_ts = min(
            (
                to_int(item.get("ts"))
                for item in stages.get("Queue", [])
                if to_int(item.get("ts")) is not None
            ),
            default=None,
        )
        if in_scope(queue_ts):
            surface_frames.append(
                {
                    "frame_number": frame_number,
                    "queue_ts_ns": queue_ts,
                    "presented": "PresentFenceSignaled" in stages,
                }
            )
    surface_frames.sort(key=lambda item: to_int(item.get("queue_ts_ns"), 0))

    observations: list[dict[str, Any]] = []
    for surface_frame in surface_frames:
        surface_queue_ts = to_int(surface_frame.get("queue_ts_ns"))
        if surface_queue_ts is None:
            continue
        native_index = nearest_native_queue(surface_queue_ts)
        if native_index is None:
            continue
        queue_ts = native_queue_timestamps[native_index]
        previous_queue_ts = (
            native_queue_timestamps[native_index - 1]
            if native_index > 0
            else None
        )
        lower_bound = previous_queue_ts if previous_queue_ts is not None else queue_ts - 20_000_000
        candidates = [
            row
            for row in vp_rows
            if lower_bound < to_int(row.get("event_ts_ns"), 0) <= queue_ts
        ]
        if not candidates:
            continue
        vp_row = candidates[-1]
        vp_ts = to_int(vp_row.get("event_ts_ns"))
        context = min(
            context_rows,
            key=lambda row: abs(to_int(row.get("event_ts_ns"), 0) - (vp_ts or 0)),
            default=None,
        )
        context_ts = None if context is None else to_int(context.get("event_ts_ns"))
        if context is None or vp_ts is None or context_ts is None or abs(context_ts - vp_ts) > 1_000_000:
            context = None
        observations.append(
            {
                "frame_number": to_int(surface_frame.get("frame_number")),
                "native_queue_ts_ns": queue_ts,
                "vp_duration_ts_ns": vp_ts,
                "presented": bool(surface_frame.get("presented")),
                "returned_duration_ns": to_int(vp_row.get("value_ns")),
                "pts_delta_ns": to_int(vp_row.get("auxiliary_ns")),
                "max_duration_ns": None if context is None else to_int(context.get("value_ns")),
                "fallback_flags": 0 if context is None else to_int(context.get("auxiliary_ns"), 0),
                "queue_interval_ns": None
                if previous_queue_ts is None
                else queue_ts - previous_queue_ts,
            }
        )

    presented = [item for item in observations if item["presented"]]
    missing = [item for item in observations if not item["presented"]]
    returned_delta = (
        None
        if not presented or not missing
        else stats(values(missing, "returned_duration_ns"))["median_ns"]
        - stats(values(presented, "returned_duration_ns"))["median_ns"]
    )
    pts_delta = (
        None
        if not presented or not missing
        else stats(values(missing, "pts_delta_ns"))["median_ns"]
        - stats(values(presented, "pts_delta_ns"))["median_ns"]
    )
    max_duration_delta = (
        None
        if not presented or not missing
        else stats(values(missing, "max_duration_ns"))["median_ns"]
        - stats(values(presented, "max_duration_ns"))["median_ns"]
    )
    fallback_present = sum(to_int(item.get("fallback_flags"), 0) != 0 for item in presented)
    fallback_missing = sum(to_int(item.get("fallback_flags"), 0) != 0 for item in missing)
    fallback_rate_delta = (
        None
        if not presented or not missing
        else fallback_missing / len(missing) - fallback_present / len(presented)
    )
    has_separation = any(
        delta is not None and abs(delta) > 250_000
        for delta in (returned_delta, pts_delta, max_duration_delta)
    ) or (fallback_rate_delta is not None and abs(fallback_rate_delta) > 0.10)
    return {
        "status": "VALID" if observations else "INSUFFICIENT_DATA",
        "scope": "fixed 30-second analysis window" if fixed_window else "full selected video stream",
        "observation_count": len(observations),
        "presented_count": len(presented),
        "missing_present_count": len(missing),
        "vp_duration_source_count": len(vp_rows),
        "vp_duration_context_source_count": len(context_rows),
        "presented_returned_duration": stats(values(presented, "returned_duration_ns")),
        "missing_present_returned_duration": stats(values(missing, "returned_duration_ns")),
        "presented_pts_delta": stats(values(presented, "pts_delta_ns")),
        "missing_present_pts_delta": stats(values(missing, "pts_delta_ns")),
        "presented_max_duration": stats(values(presented, "max_duration_ns")),
        "missing_present_max_duration": stats(values(missing, "max_duration_ns")),
        "presented_fallback_count": fallback_present,
        "missing_present_fallback_count": fallback_missing,
        "missing_minus_present_returned_duration_median_ns": returned_delta,
        "missing_minus_present_pts_delta_median_ns": pts_delta,
        "missing_minus_present_max_duration_median_ns": max_duration_delta,
        "missing_minus_present_fallback_rate": fallback_rate_delta,
        "conclusion": "VP_DURATION_SEPARATION" if has_separation else "NO_VP_DURATION_SEPARATION",
        "observations": observations,
        "evidence_quality": "TEMPORAL_ONLY",
        "cross_layer_identity_available": False,
        "note": (
            "vp_duration rows are matched to the selected SurfaceFlinger Queue through native "
            "queue cadence. They distinguish duration/fallback behavior from phase timing but "
            "do not create a shared codec/native/SurfaceFlinger frame identity."
        ),
    }


def analyze_vp_duration_queue_phase_cross_tab(
    vp_duration_analysis: Mapping[str, Any],
    queue_phase_analysis: Mapping[str, Any],
) -> dict[str, Any]:
    """Cross-tab vp_duration values against the derived SurfaceFlinger queue phase.

    Both inputs are temporal-nearest joins. This reports phase separation by
    duration value without claiming that a native observation is the same
    buffer as a SurfaceFlinger frame.
    """

    def stats(items: Sequence[int | None]) -> dict[str, Any]:
        ordered = sorted(int(value) for value in items if value is not None)
        return {
            "count": len(ordered),
            "median_ns": None if not ordered else int(statistics.median(ordered)),
            "p10_ns": None if not ordered else int(percentile(ordered, 0.10)),
            "p90_ns": None if not ordered else int(percentile(ordered, 0.90)),
            "min_ns": None if not ordered else min(ordered),
            "max_ns": None if not ordered else max(ordered),
        }

    vp_rows = [
        row
        for row in vp_duration_analysis.get("observations", [])
        if to_int(row.get("frame_number")) is not None
    ]
    queue_rows = [
        row
        for row in queue_phase_analysis.get("observations", [])
        if to_int(row.get("frame_number")) is not None
    ]
    queue_by_frame = {
        to_int(row.get("frame_number")): row
        for row in queue_rows
        if to_int(row.get("frame_number")) is not None
    }
    groups: dict[tuple[int, bool], list[Mapping[str, Any]]] = defaultdict(list)
    matched_rows = []
    for vp_row in vp_rows:
        frame_number = to_int(vp_row.get("frame_number"))
        queue_row = queue_by_frame.get(frame_number)
        duration_ns = to_int(vp_row.get("returned_duration_ns"))
        if queue_row is None or duration_ns is None:
            continue
        row = {
            "frame_number": frame_number,
            "returned_duration_ns": duration_ns,
            "presented": bool(vp_row.get("presented")),
            "queue_phase_ns": to_int(queue_row.get("queue_since_previous_commit_ns")),
            "next_commit_minus_queue_ns": to_int(
                queue_row.get("next_commit_minus_queue_ns")
            ),
        }
        matched_rows.append(row)
        groups[(duration_ns, row["presented"])].append(row)

    duration_values = sorted({row["returned_duration_ns"] for row in matched_rows})
    duration_groups = []
    for duration_ns in duration_values:
        presented_rows = groups.get((duration_ns, True), [])
        missing_rows = groups.get((duration_ns, False), [])

        def group_values(rows: Sequence[Mapping[str, Any]], key: str) -> list[int]:
            return [
                value
                for row in rows
                for value in [to_int(row.get(key))]
                if value is not None
            ]

        presented_phase = group_values(presented_rows, "queue_phase_ns")
        missing_phase = group_values(missing_rows, "queue_phase_ns")
        presented_next_gap = group_values(
            presented_rows, "next_commit_minus_queue_ns"
        )
        missing_next_gap = group_values(missing_rows, "next_commit_minus_queue_ns")
        phase_delta = (
            None
            if not presented_phase or not missing_phase
            else stats(missing_phase)["median_ns"] - stats(presented_phase)["median_ns"]
        )
        next_gap_delta = (
            None
            if not presented_next_gap or not missing_next_gap
            else stats(missing_next_gap)["median_ns"]
            - stats(presented_next_gap)["median_ns"]
        )
        total = len(presented_rows) + len(missing_rows)
        duration_groups.append(
            {
                "returned_duration_ns": duration_ns,
                "presented_count": len(presented_rows),
                "missing_present_count": len(missing_rows),
                "missing_rate": None if total == 0 else len(missing_rows) / total,
                "presented_queue_phase": stats(presented_phase),
                "missing_present_queue_phase": stats(missing_phase),
                "missing_minus_present_queue_phase_median_ns": phase_delta,
                "presented_next_commit_minus_queue": stats(presented_next_gap),
                "missing_present_next_commit_minus_queue": stats(missing_next_gap),
                "missing_minus_present_next_commit_minus_queue_median_ns": next_gap_delta,
            }
        )

    return {
        "status": "VALID" if matched_rows else "INSUFFICIENT_DATA",
        "matched_observation_count": len(matched_rows),
        "duration_groups": duration_groups,
        "evidence_quality": "TEMPORAL_ONLY",
        "cross_layer_identity_available": False,
        "conclusion": (
            "DURATION_PHASE_CROSSTAB_AVAILABLE" if matched_rows else "INSUFFICIENT_DATA"
        ),
        "note": (
            "vp_duration observations and SurfaceFlinger queue phase are joined by the "
            "existing temporal frame association. A phase difference supports late producer "
            "arrival, but this table does not establish MediaCodec/native/SurfaceFlinger "
            "buffer identity."
        ),
    }


def attach_scheduler_telemetry(
    video_frame_timing: list[dict[str, Any]],
    telemetry: Mapping[str, Any],
) -> dict[str, Any]:
    """Attach a causal temporal scheduler cross-check to per-present rows.

    A scheduled release may only be associated with a later PresentFence event.
    Matching against the scheduled timestamp alone can select a scheduler row
    whose release call happened after the present event, which is physically
    impossible and invalidates the comparison.
    """
    scheduled_rows = list(telemetry.get("_scheduled_rows", []))
    for item in video_frame_timing:
        item.update(
            {
                "codec_frame_index": None,
                "codec_pts_us": None,
                "ijk_ready_ts_ns": None,
                "release_call_ts_ns": None,
                "decision_now_ts_ns": None,
                "scheduled_release_ts_ns": None,
                "scheduled_release_minus_decision_ns": None,
                "target_vsync_ts_ns": None,
                "target_vsync_source": None,
                "runtime_target_vsync_ts_ns": None,
                "runtime_cadence_anchor_frame_index": None,
                "runtime_cadence_anchor_pts_us": None,
                "runtime_cadence_anchor_vsync_ts_ns": None,
                "runtime_cadence_frame_delta": None,
                "runtime_cadence_frame_slot": None,
                "runtime_cadence_drift_ns": None,
                "runtime_cadence_phase_error_ns": None,
                "runtime_cadence_reanchored": None,
                "runtime_cadence_reanchor_reason": None,
                "runtime_sampled_vsync_age_ns": None,
                "runtime_playback_position_available": None,
                "runtime_playback_position_age_ms": None,
                "sampled_vsync_id": None,
                "sampled_vsync_ts_ns": None,
                "vsync_duration_ns": None,
                "expected_presentation_ts_ns": None,
                "frame_deadline_ts_ns": None,
                "scheduler_release_minus_target_ns": None,
                "scheduler_present_minus_target_ns": None,
                "scheduler_present_minus_sampled_vsync_ns": None,
                "scheduler_identity_link": "UNMATCHED",
                "scheduler_temporal_match": None,
                "scheduler_temporal_order": "UNKNOWN",
            }
        )
    empty_result = {
        "exact_identity_count": 0,
        "temporal_only_count": 0,
        "unmatched_count": len(video_frame_timing),
        "exact_identity_ratio": 0.0 if video_frame_timing else None,
        "temporal_only_ratio": 0.0 if video_frame_timing else None,
        "unmatched_ratio": 1.0 if video_frame_timing else None,
        "temporal_match_limit_ms": TELEMETRY_TEMPORAL_MATCH_LIMIT_NS / 1e6,
        "present_minus_target": _telemetry_interval_stats([]),
        "present_minus_sampled_vsync": _telemetry_interval_stats([]),
        "release_minus_target": _telemetry_interval_stats([]),
        "scheduled_release_minus_decision": _telemetry_interval_stats([]),
        "expired_release_count": 0,
        "expired_release_ratio": None,
        "ordered_match_reuse_count": 0,
        "unique_scheduler_row_count": 0,
        "runtime_target_vsync_count": 0,
        "reconstructed_target_vsync_count": 0,
        "target_semantics": (
            "expectedPresentationNs is a sampled callback anchor. An explicit chosen target is "
            "available only when runtime telemetry records it; it is not a panel scanout timestamp."
        ),
        "temporal_order_rule": "release_call_ts_ns <= present_ts_ns",
    }
    if not scheduled_rows:
        return {"status": "UNKNOWN", **empty_result,
                "note": "No SCHEDULED_TIMED_RELEASE telemetry was available in the trace window."}

    scheduled_rows.sort(key=lambda row: row.get("release_call_ts_ns") or row.get("event_ts_ns") or 0)
    call_anchors = [row.get("release_call_ts_ns") or row.get("event_ts_ns") for row in scheduled_rows]
    used_counts: Counter[int] = Counter()
    temporal_count = 0
    present_minus_target: list[int] = []
    present_minus_sampled: list[int] = []
    release_minus_target: list[int] = []
    scheduled_minus_decision: list[int] = []
    expired_release_count = 0
    runtime_target_count = 0
    reconstructed_target_count = 0
    for item in video_frame_timing:
        present_ts = to_int(item.get("present_ts_ns"))
        if present_ts is None:
            continue
        position = bisect.bisect_right(call_anchors, present_ts) - 1
        if position < 0:
            continue
        match_index = position
        call_ns = call_anchors[match_index]
        delta_ns = present_ts - call_ns
        if delta_ns < 0 or delta_ns > TELEMETRY_TEMPORAL_MATCH_LIMIT_NS:
            continue
        used_counts[match_index] += 1
        row = scheduled_rows[match_index]
        detail = row.get("detail", {})
        expected_ns = row.get("expected_presentation_ts_ns")
        sampled_ns = row.get("sampled_vsync_ts_ns")
        deadline_ns = row.get("frame_deadline_ts_ns")
        scheduled_ns = row.get("runtime_scheduled_release_ts_ns") or row.get("release_time_ns")
        runtime_decision_ns = row.get("runtime_decision_now_ts_ns") or call_ns
        period_ns = to_int(detail.get("cadenceVsyncDurationNs"))
        fixed_cadence = detail.get("fixedCadence") == "active"
        runtime_target_ns = row.get("runtime_target_vsync_ts_ns")
        if runtime_target_ns is not None:
            target_ns = runtime_target_ns
            target_source = "runtime_chosen_target_vsync_ns"
            runtime_target_count += 1
        else:
            target_ns = (
                scheduled_ns + (period_ns * 80 // 100)
                if fixed_cadence and scheduled_ns is not None and period_ns is not None
                else None
            )
            target_source = (
                "fixed_cadence_release_plus_80pct_period_reconstructed"
                if target_ns is not None
                else None
            )
            if target_ns is not None:
                reconstructed_target_count += 1
        item["codec_frame_index"] = row.get("frame_index")
        item["codec_pts_us"] = row.get("pts_us")
        item["ijk_ready_ts_ns"] = row.get("dequeued_at_ns")
        item["release_call_ts_ns"] = call_ns
        item["decision_now_ts_ns"] = runtime_decision_ns
        item["scheduled_release_ts_ns"] = scheduled_ns
        item["scheduled_release_minus_decision_ns"] = (
            row.get("runtime_scheduled_release_minus_decision_ns")
            if row.get("runtime_scheduled_release_minus_decision_ns") is not None
            else scheduled_ns - runtime_decision_ns if scheduled_ns is not None else None
        )
        item["target_vsync_ts_ns"] = target_ns
        item["target_vsync_source"] = target_source
        item["runtime_target_vsync_ts_ns"] = runtime_target_ns
        item["runtime_cadence_anchor_frame_index"] = row.get(
            "runtime_cadence_anchor_frame_index"
        )
        item["runtime_cadence_anchor_pts_us"] = row.get("runtime_cadence_anchor_pts_us")
        item["runtime_cadence_anchor_vsync_ts_ns"] = row.get(
            "runtime_cadence_anchor_vsync_ts_ns"
        )
        item["runtime_cadence_frame_delta"] = row.get("runtime_cadence_frame_delta")
        item["runtime_cadence_frame_slot"] = row.get("runtime_cadence_frame_slot")
        item["runtime_cadence_drift_ns"] = row.get("runtime_cadence_drift_ns")
        item["runtime_cadence_phase_error_ns"] = row.get("runtime_cadence_phase_error_ns")
        item["runtime_cadence_reanchored"] = row.get("runtime_cadence_reanchored")
        item["runtime_cadence_reanchor_reason"] = row.get(
            "runtime_cadence_reanchor_reason"
        )
        item["runtime_sampled_vsync_age_ns"] = row.get("runtime_sampled_vsync_age_ns")
        item["runtime_playback_position_available"] = row.get(
            "runtime_playback_position_available"
        )
        item["runtime_playback_position_age_ms"] = row.get(
            "runtime_playback_position_age_ms"
        )
        item["sampled_vsync_id"] = row.get("sampled_vsync_id")
        item["sampled_vsync_ts_ns"] = sampled_ns
        item["vsync_duration_ns"] = row.get("vsync_duration_ns")
        item["expected_presentation_ts_ns"] = expected_ns
        item["frame_deadline_ts_ns"] = deadline_ns
        item["scheduler_release_minus_target_ns"] = (
            scheduled_ns - target_ns if scheduled_ns is not None and target_ns is not None else None
        )
        item["scheduler_present_minus_target_ns"] = present_ts - target_ns if target_ns is not None else None
        item["scheduler_present_minus_sampled_vsync_ns"] = present_ts - sampled_ns if sampled_ns is not None else None
        item["scheduler_identity_link"] = "TEMPORAL_ONLY"
        item["scheduler_temporal_order"] = "ORDERED" if call_ns <= present_ts else "INVALID"
        item["scheduler_temporal_match"] = {
            "match_anchor": "release_call_ts_ns",
            "present_minus_anchor_ns": delta_ns,
            "present_minus_target_ns": item["scheduler_present_minus_target_ns"],
            "present_minus_sampled_vsync_ns": item["scheduler_present_minus_sampled_vsync_ns"],
            "release_minus_target_ns": item["scheduler_release_minus_target_ns"],
            "target_vsync_source": target_source,
            "runtime_scheduled_release_minus_decision_ns": row.get(
                "runtime_scheduled_release_minus_decision_ns"
            ),
            "runtime_sampled_vsync_age_ns": row.get("runtime_sampled_vsync_age_ns"),
            "runtime_cadence_frame_slot": row.get("runtime_cadence_frame_slot"),
            "runtime_cadence_reanchor_reason": row.get(
                "runtime_cadence_reanchor_reason"
            ),
            "sampled_vsync_id": row.get("sampled_vsync_id"),
            "sampled_vsync_ts_ns": sampled_ns,
            "expected_presentation_ts_ns": expected_ns,
            "frame_deadline_ts_ns": deadline_ns,
            "vsync_duration_ns": row.get("vsync_duration_ns"),
            "codec_frame_index": row.get("frame_index"),
            "codec_pts_us": row.get("pts_us"),
            "reused_scheduler_row": used_counts[match_index] > 1,
            "note": "Nearest timestamp only; no shared codec-to-SurfaceFlinger frame identity was present.",
        }
        if target_ns is not None:
            present_minus_target.append(present_ts - target_ns)
            release_minus_target.append(scheduled_ns - target_ns)
        if sampled_ns is not None:
            present_minus_sampled.append(present_ts - sampled_ns)
        if scheduled_ns is not None:
            scheduled_minus_decision.append(scheduled_ns - call_ns)
            if scheduled_ns < call_ns:
                expired_release_count += 1
        temporal_count += 1

    total = len(video_frame_timing)
    unique_count = len(used_counts)
    reuse_count = sum(count - 1 for count in used_counts.values() if count > 1)
    return {
        "status": "TEMPORAL_ONLY_ORDERED" if temporal_count else "UNMATCHED",
        "exact_identity_count": 0,
        "temporal_only_count": temporal_count,
        "unmatched_count": total - temporal_count,
        "exact_identity_ratio": 0.0 if total else None,
        "temporal_only_ratio": temporal_count / total if total else None,
        "unmatched_ratio": (total - temporal_count) / total if total else None,
        "temporal_match_limit_ms": TELEMETRY_TEMPORAL_MATCH_LIMIT_NS / 1e6,
        "present_minus_target": _telemetry_interval_stats(present_minus_target),
        "present_minus_sampled_vsync": _telemetry_interval_stats(present_minus_sampled),
        "release_minus_target": _telemetry_interval_stats(release_minus_target),
        "scheduled_release_minus_decision": _telemetry_interval_stats(scheduled_minus_decision),
        "expired_release_count": expired_release_count,
        "expired_release_ratio": (
            expired_release_count / len(scheduled_minus_decision)
            if scheduled_minus_decision else None
        ),
        "ordered_match_reuse_count": reuse_count,
        "unique_scheduler_row_count": unique_count,
        "runtime_target_vsync_count": runtime_target_count,
        "reconstructed_target_vsync_count": reconstructed_target_count,
        "target_semantics": (
            "Use the runtime chosenTargetVsyncNs when present. Older telemetry reconstructs the "
            "fixed cadence target from scheduledRelease + 80% of the cadence period. "
            "expectedPresentationNs and sampledVsyncNs remain callback anchors, not panel scanout."
        ),
        "temporal_order_rule": "release_call_ts_ns <= present_ts_ns",
        "note": (
            "Matches are causal temporal associations only. A reused scheduler row indicates that no shared "
            "codec-to-SurfaceFlinger identity exists and the association is ambiguous."
        ),
    }


def fixed_analysis_window(
    present_rows: Sequence[Mapping[str, Any]],
    video_frame_timing: Sequence[Mapping[str, Any]],
    reference_period_ns: int | None,
    nominal_period_ns: int,
    gap_threshold_periods: float,
) -> dict[str, Any]:
    complete_index = None
    for index, timing in enumerate(video_frame_timing):
        if timing.get("present_ts_ns") is not None and not timing.get("stage_join", {}).get("invalid_reasons"):
            complete_index = index
            break
    timestamps = [to_int(row.get("ts")) for row in present_rows]
    timestamps = [timestamp for timestamp in timestamps if timestamp is not None]
    if complete_index is None or not timestamps:
        return {
            "rule_id": "first_complete_video_present_plus_30s",
            "status": "INSUFFICIENT_DATA",
            "requested_duration_ms": FIXED_ANALYSIS_WINDOW_DURATION_NS / 1e6,
            "reason": "No complete video PresentFenceSignaled event was available.",
            "present_count": 0,
            "interval_count": 0,
        }
    start_ns = to_int(video_frame_timing[complete_index].get("present_ts_ns"))
    if start_ns is None:
        return {
            "rule_id": "first_complete_video_present_plus_30s",
            "status": "INSUFFICIENT_DATA",
            "requested_duration_ms": FIXED_ANALYSIS_WINDOW_DURATION_NS / 1e6,
            "reason": "The first complete event has no timestamp.",
            "present_count": 0,
            "interval_count": 0,
        }
    end_ns = start_ns + FIXED_ANALYSIS_WINDOW_DURATION_NS
    rows = [
        dict(row)
        for row in present_rows
        if (to_int(row.get("ts")) is not None and start_ns <= to_int(row.get("ts")) < end_ns)
    ]
    timing_rows = [
        dict(item)
        for item in video_frame_timing
        if (to_int(item.get("present_ts_ns")) is not None and start_ns <= to_int(item.get("present_ts_ns")) < end_ns)
    ]
    boundary_after = next((timestamp for timestamp in timestamps if timestamp >= end_ns), None)
    observed_last = to_int(rows[-1].get("ts")) if rows else None
    observed_duration_ms = None if observed_last is None else (observed_last - start_ns) / 1e6
    interval_rows, summary = classify_present_intervals(
        rows,
        reference_period_ns=reference_period_ns,
        nominal_period_ns=nominal_period_ns,
        gap_threshold_periods=gap_threshold_periods,
    )
    valid = boundary_after is not None and len(rows) >= 100 and observed_duration_ms is not None
    return {
        "rule_id": "first_complete_video_present_plus_30s",
        "status": "VALID" if valid else "INSUFFICIENT_DATA",
        "requested_duration_ms": FIXED_ANALYSIS_WINDOW_DURATION_NS / 1e6,
        "start_ts_ns": start_ns,
        "end_ts_ns_exclusive": end_ns,
        "first_complete_present_frame_number": video_frame_timing[complete_index].get("frame_number"),
        "present_count": len(rows),
        "interval_count": len(interval_rows),
        "observed_duration_ms": observed_duration_ms,
        "boundary_after_window_present_ts_ns": boundary_after,
        "boundary_after_window_available": boundary_after is not None,
        "all_events_in_window_retained": True,
        "tail_after_window_present_count": sum(timestamp >= end_ns for timestamp in timestamps),
        "video_present_summary": summary,
        "video_interval_observations": interval_rows,
        "note": (
            "The fixed window begins at the first complete identity-bearing video present and ends 30 seconds later. "
            "It is not selected by excluding anomalous intervals; every selected event, including multi-slot gaps, is retained."
        ),
    }


def summarize_frame_link_alignment(
    video_frame_timing: Sequence[Mapping[str, Any]],
    fixed_window: Mapping[str, Any],
    link_field: str,
) -> dict[str, Any]:
    """Summarize exact/temporal/unmatched links inside the fixed window."""
    start_ns = to_int(fixed_window.get("start_ts_ns"))
    end_ns = to_int(fixed_window.get("end_ts_ns_exclusive"))
    if fixed_window.get("status") != "VALID" or start_ns is None or end_ns is None:
        return {
            "status": "INSUFFICIENT_DATA",
            "present_count": 0,
            "exact_identity_count": 0,
            "temporal_only_count": 0,
            "unmatched_count": 0,
            "exact_identity_ratio": None,
            "temporal_only_ratio": None,
            "unmatched_ratio": None,
            "scope": "fixed 30-second analysis window",
        }
    rows = [
        item
        for item in video_frame_timing
        if to_int(item.get("present_ts_ns")) is not None
        and (start_ns is None or to_int(item.get("present_ts_ns")) >= start_ns)
        and (end_ns is None or to_int(item.get("present_ts_ns")) < end_ns)
    ]
    exact = sum(item.get(link_field) == "EXACT" for item in rows)
    temporal = sum(item.get(link_field) == "TEMPORAL_ONLY" for item in rows)
    unmatched = len(rows) - exact - temporal
    ordered = sum(
        item.get("scheduler_temporal_order") == "ORDERED"
        for item in rows
    ) if link_field == "scheduler_identity_link" else 0
    runtime_targets = sum(
        item.get("target_vsync_source") == "runtime_chosen_target_vsync_ns"
        for item in rows
    ) if link_field == "scheduler_identity_link" else 0
    if not rows:
        status = "UNKNOWN"
    elif unmatched == 0 and temporal > 0 and exact == 0:
        status = (
            "TEMPORAL_ONLY_ORDERED"
            if link_field == "scheduler_identity_link" and ordered == temporal
            else "TEMPORAL_ONLY"
        )
    elif temporal > 0 or exact > 0:
        status = "PARTIAL"
    else:
        status = "UNMATCHED"
    return {
        "status": status,
        "present_count": len(rows),
        "exact_identity_count": exact,
        "temporal_only_count": temporal,
        "unmatched_count": unmatched,
        "exact_identity_ratio": exact / len(rows) if rows else None,
        "temporal_only_ratio": temporal / len(rows) if rows else None,
        "unmatched_ratio": unmatched / len(rows) if rows else None,
        "temporal_order_count": ordered if link_field == "scheduler_identity_link" else None,
        "temporal_order_ratio": ordered / len(rows) if rows and link_field == "scheduler_identity_link" else None,
        "runtime_target_vsync_count": (
            runtime_targets if link_field == "scheduler_identity_link" else None
        ),
        "runtime_target_vsync_ratio": (
            runtime_targets / len(rows)
            if rows and link_field == "scheduler_identity_link" else None
        ),
        "scope": "fixed 30-second analysis window",
    }


def timing_examples(
    video_frame_timing: Sequence[Mapping[str, Any]],
    interval_rows: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    by_index = {
        to_int(item.get("frame_index")): item
        for item in video_frame_timing
        if to_int(item.get("frame_index")) is not None
    }

    def make_example(label: str, interval: Mapping[str, Any] | None) -> dict[str, Any]:
        if interval is None:
            return {"label": label, "status": "NOT_FOUND"}
        index = to_int(interval.get("present_event_index"))
        timing = by_index.get(index) if index is not None else None
        if timing is None:
            return {"label": label, "status": "NOT_FOUND", "interval": dict(interval)}
        return {
            "label": label,
            "status": "AVAILABLE",
            "frame_index": timing.get("frame_index"),
            "frame_number": timing.get("frame_number"),
            "previous_frame_number": interval.get("previous_frame_number"),
            "present_ts_ns": timing.get("present_ts_ns"),
            "interval": {
                "interval_ms": interval.get("interval_ms"),
                "whole_vsync_slots_rounded": interval.get("whole_vsync_slots_rounded"),
                "slot_residual_ns": interval.get("slot_residual_ns"),
                "classification": interval.get("classification"),
                "cadence_anomaly_cluster_id": interval.get("cadence_anomaly_cluster_id"),
                "cadence_anomaly_cluster_size": (
                    cluster_counts.get(interval.get("cadence_anomaly_cluster_id"))
                    if interval.get("cadence_anomaly_cluster_id") is not None
                    else None
                ),
            },
            "stage_ts_ns": timing.get("stage_join", {}).get("stage_ts_ns"),
            "stage_latency_ns": timing.get("stage_join", {}).get("stage_latency_ns"),
            "identity_link": timing.get("scheduler_identity_link", "UNMATCHED"),
            "codec_frame_index": timing.get("codec_frame_index"),
            "codec_pts_us": timing.get("codec_pts_us"),
            "ijk_ready_ts_ns": timing.get("ijk_ready_ts_ns"),
            "release_call_ts_ns": timing.get("release_call_ts_ns"),
            "scheduled_release_ts_ns": timing.get("scheduled_release_ts_ns"),
            "target_vsync_ts_ns": timing.get("target_vsync_ts_ns"),
            "target_vsync_source": timing.get("target_vsync_source"),
            "sampled_vsync_id": timing.get("sampled_vsync_id"),
            "sampled_vsync_ts_ns": timing.get("sampled_vsync_ts_ns"),
            "vsync_duration_ns": timing.get("vsync_duration_ns"),
            "expected_presentation_ts_ns": timing.get("expected_presentation_ts_ns"),
            "frame_deadline_ts_ns": timing.get("frame_deadline_ts_ns"),
            "scheduler_release_minus_target_ns": timing.get("scheduler_release_minus_target_ns"),
            "scheduler_present_minus_target_ns": timing.get("scheduler_present_minus_target_ns"),
            "scheduler_present_minus_sampled_vsync_ns": timing.get("scheduler_present_minus_sampled_vsync_ns"),
            "target_alignment_status": "UNKNOWN" if timing.get("scheduler_identity_link") != "TEMPORAL_ONLY" else "TEMPORAL_ONLY",
            "native_pts_us": timing.get("native_pts_us"),
            "native_serial": timing.get("native_serial"),
            "native_queue_picture_ts_ns": timing.get("native_queue_picture_ts_ns"),
            "native_display_picture_ts_ns": timing.get("native_display_picture_ts_ns"),
            "native_handoff_event": timing.get("native_handoff_event"),
            "native_handoff_link": timing.get("native_handoff_link", "UNMATCHED"),
            "native_identity_link": timing.get("native_identity_link", "UNMATCHED"),
            "native_handoff_temporal_match": timing.get("native_handoff_temporal_match"),
            "temporal_display_actual": timing.get("nearest_display_actual_temporal_only"),
            "interpretation": (
                "Measured PresentFence interval and buffer stages; native queue/display may have exact PTS source identity, "
                "but the PresentFence association remains temporal-only without a shared codec-to-buffer link."
            ),
        }

    valid_normal = [
        item for item in interval_rows
        if item.get("interval_ns") is not None
        and item.get("classification") == "at_or_below_nominal_source_period"
    ]
    two_slot = next(
        (
            item for item in interval_rows
            if item.get("whole_vsync_slots_rounded") == 2
            and item.get("is_gap_over_threshold")
            and not item.get("is_large_gap")
        ),
        None,
    )
    cluster_counts = Counter(
        item.get("cadence_anomaly_cluster_id")
        for item in interval_rows
        if item.get("cadence_anomaly_cluster_id") is not None
    )
    chain_cluster = next(
        (
            cluster
            for cluster, count in sorted(
                cluster_counts.items(), key=lambda pair: (-pair[1], pair[0])
            )
            if count >= 2
        ),
        None,
    )
    chain_intervals = [
        item for item in interval_rows
        if item.get("cadence_anomaly_cluster_id") == chain_cluster
    ]
    # Prefer the second event in a cluster so the example represents a chain,
    # while still exposing a two-event cluster when that is all the trace has.
    chain_interval = chain_intervals[1] if len(chain_intervals) >= 2 else (
        chain_intervals[0] if chain_intervals else None
    )
    return {
        "normal": [make_example(f"normal_{index + 1}", item) for index, item in enumerate(valid_normal[:3])],
        "two_slot": make_example("two_slot", two_slot),
        "chained_anomaly": make_example("chained_anomaly", chain_interval),
        "note": "Examples are compact evidence samples; all per-frame rows remain in video_present_timing and interval observations.",
    }


def analyze_trace(
    trace_path: str | Path,
    package_name: str,
    gap_threshold_periods: float = 1.5,
    display_deadline_threshold_ms: float = 2.42,
    nominal_source_fps: float = NOMINAL_SOURCE_FPS,
    telemetry_path: str | Path | None = None,
    native_telemetry_path: str | Path | None = None,
) -> dict[str, Any]:
    from perfetto.trace_processor import TraceProcessor

    trace_path = Path(trace_path)
    nominal_period_ns = int(round(1e9 / nominal_source_fps))
    tp = TraceProcessor(file_path=str(trace_path))
    try:
        expected_all = query_timeline_rows(tp, "expected_frame_timeline_slice", actual=False)
        actual_all = query_timeline_rows(tp, "actual_frame_timeline_slice", actual=True)
        expected_rows, expected_selection = select_display_stream(expected_all, "expected")
        actual_rows, actual_selection = select_display_stream(actual_all, "actual")
        frame_rows = frame_slice_rows(tp)
        video_layer, video_rows, video_selection = select_video_layer(frame_rows, package_name)
        surface_flinger_events = query_surface_flinger_latch_events(tp, video_layer)
        renderer_events = query_renderer_cadence_events(tp)
        renderer_events["thread_wake_events"] = query_renderer_thread_wake_events(tp)
        renderer_events["display_callback_events"] = query_display_callback_events(tp)
        renderer_events["native_display_events"] = query_native_display_events(tp)
        renderer_events["frame_receiver_events"] = query_frame_receiver_events(tp)
        renderer_events["surface_texture_events"] = query_surface_texture_callback_events(tp)
        renderer_events["surface_texture_probe_events"] = query_surface_texture_probe_events(tp)
        set_buffer_secondary = query_set_buffer_events(tp, package_name)
        producer_queue_events = query_producer_queue_events(tp, package_name)
        decoder_release_ts = [
            to_int(row_value(row, "ts"))
            for row in query_rows(
                tp,
                "select ts from slice where name='MediaCodec::renderOutputBufferAndRelease#native' order by ts",
            )
        ]
        decoder_release_ts = [timestamp for timestamp in decoder_release_ts if timestamp is not None]
        vsync_callback_ts = [
            to_int(row_value(row, "ts"))
            for row in query_rows(tp, "select ts from slice where name='VSyncPredictor::onFrameBegin' order by ts")
        ]
        vsync_callback_ts = [timestamp for timestamp in vsync_callback_ts if timestamp is not None]
        stats = stats_summary(tp)
        bounds = trace_bounds(tp)
        clock_calibration = clock_snapshot_calibration(tp)
        raw_vsync_candidates = raw_vsync_slice_candidates(tp)
    finally:
        tp.close()

    telemetry = load_media3_telemetry(telemetry_path, bounds, clock_calibration)
    native_telemetry = load_native_telemetry(
        native_telemetry_path, bounds, clock_calibration
    )

    expected_ts = [to_int(row.get("ts")) for row in expected_rows]
    expected_ts = [timestamp for timestamp in expected_ts if timestamp is not None]
    actual_present_ts = [
        to_int(row.get("ts")) + max(0, to_int(row.get("dur"), 0))
        for row in actual_rows
        if to_int(row.get("ts")) is not None
    ]
    display_timeline_period_ns = median_reference_period_ns(expected_ts)
    expected_metrics = interval_summary(
        expected_ts,
        reference_period_ns=display_timeline_period_ns,
        gap_threshold_periods=gap_threshold_periods,
        nominal_period_ns=nominal_period_ns,
    )
    actual_present_metrics = interval_summary(
        actual_present_ts,
        reference_period_ns=display_timeline_period_ns,
        gap_threshold_periods=gap_threshold_periods,
        nominal_period_ns=nominal_period_ns,
    )
    display_pairs_rows, display_pair_summary = display_pairs(expected_rows, actual_rows)
    display_jank_counts = Counter(
        f"{row.get('present_type', '')} | {row.get('jank_type', '')}" for row in actual_rows
    )

    present_rows = [row for row in video_rows if row.get("name") == "PresentFenceSignaled"]
    video_timestamps = [to_int(row.get("ts")) for row in present_rows]
    video_timestamps = [timestamp for timestamp in video_timestamps if timestamp is not None]
    video_reference_ns = median_reference_period_ns(video_timestamps) or display_timeline_period_ns
    video_interval_rows, video_summary = classify_present_intervals(
        present_rows,
        reference_period_ns=video_reference_ns,
        nominal_period_ns=nominal_period_ns,
        gap_threshold_periods=gap_threshold_periods,
    )
    stage_groups = group_stage_events(video_rows)
    duplicate_present_numbers = {
        to_int(number)
        for number, count in Counter(to_int(row.get("frame_number")) for row in present_rows).items()
        if number is not None and count > 1
    }
    video_frame_timing: list[dict[str, Any]] = []
    for index, row in enumerate(
        sorted(present_rows, key=lambda item: (to_int(item.get("ts"), 0), to_int(item.get("id"), 0)))
    ):
        joined = stage_join_for_present(row, stage_groups, duplicate_present_numbers)
        interval = video_interval_rows[index] if index < len(video_interval_rows) else {}
        present_ts = to_int(row.get("ts"))
        nearest_display = nearest_display_actual(present_ts, actual_rows, display_timeline_period_ns)
        phase = phase_against_expected(present_ts, expected_ts, display_timeline_period_ns)
        video_frame_timing.append(
            {
                "frame_index": index,
                "trace_row_id": to_int(row.get("id")),
                "frame_number": to_int(row.get("frame_number")),
                "present_ts_ns": present_ts,
                "present_source": "frame_slice.PresentFenceSignaled.ts",
                "scope": "selected_package_owned_video_surface_layer",
                "clock_domain": "perfetto_trace_clock",
                "queue_to_acquire_time_field_ns": to_int(row.get("queue_to_acquire_time")),
                "acquire_to_latch_time_field_ns": to_int(row.get("acquire_to_latch_time")),
                "latch_to_present_time_field_ns": to_int(row.get("latch_to_present_time")),
                "stage_join": joined,
                "interval": interval,
                "phase_against_expected_display_timeline": phase,
                "nearest_display_actual_temporal_only": nearest_display,
                "codec_frame_index": None,
                "codec_pts_us": None,
                "ijk_ready_ts_ns": None,
                "release_call_ts_ns": None,
                "scheduled_release_ts_ns": None,
                "target_vsync_ts_ns": None,
                "target_vsync_source": None,
                "sampled_vsync_id": None,
                "sampled_vsync_ts_ns": None,
                "vsync_duration_ns": None,
                "expected_presentation_ts_ns": None,
                "frame_deadline_ts_ns": None,
                "scheduler_release_minus_target_ns": None,
                "scheduler_present_minus_target_ns": None,
                "scheduler_present_minus_sampled_vsync_ns": None,
                "scheduler_identity_link": "UNMATCHED",
                "scheduler_temporal_match": None,
                "native_pts_us": None,
                "native_serial": None,
                "native_queue_picture_ts_ns": None,
                "native_display_picture_ts_ns": None,
                "native_handoff_event": None,
                "native_handoff_link": "UNMATCHED",
                "native_identity_link": "UNMATCHED",
                "native_handoff_temporal_match": None,
                "physical_panel_scanout_measured": False,
            }
        )

    scheduler_target_alignment = attach_scheduler_telemetry(video_frame_timing, telemetry)
    scheduler_render_alignment = attach_scheduler_render_telemetry(telemetry)
    scheduler_native_alignment = attach_scheduler_native_telemetry(
        telemetry, native_telemetry
    )
    native_handoff_alignment = attach_native_telemetry(video_frame_timing, native_telemetry)
    fixed_window = fixed_analysis_window(
        present_rows,
        video_frame_timing,
        reference_period_ns=video_reference_ns,
        nominal_period_ns=nominal_period_ns,
        gap_threshold_periods=gap_threshold_periods,
    )
    native_scheduler_phase_analysis = analyze_native_scheduler_phase(
        native_telemetry,
        stage_groups,
        fixed_window=fixed_window,
    )
    native_vp_duration_phase_analysis = analyze_native_vp_duration_phase(
        native_telemetry,
        stage_groups,
        fixed_window=fixed_window,
    )
    surface_flinger_buffer_replacement_analysis = analyze_surface_flinger_buffer_replacement(
        video_frame_timing,
        video_interval_rows,
        surface_flinger_events,
        stage_groups,
        reference_period_ns=video_reference_ns,
        gap_threshold_periods=gap_threshold_periods,
        fixed_window=fixed_window,
    )
    producer_stream_analyses: dict[str, dict[str, Any]] = {}
    for stream_name, stream_events in producer_queue_events.get("stream_events", {}).items():
        producer_stream_analyses[stream_name] = analyze_surface_flinger_queue_phase(
            video_frame_timing,
            surface_flinger_events,
            {
                "selected_stream": stream_name,
                "selected_events": stream_events,
            },
            reference_period_ns=video_reference_ns,
            fixed_window=fixed_window,
            stage_groups=stage_groups,
        )
    primary_stream = producer_queue_events.get("selected_stream")
    if primary_stream in producer_stream_analyses:
        surface_flinger_queue_phase_analysis = dict(
            producer_stream_analyses[primary_stream]
        )
    else:
        surface_flinger_queue_phase_analysis = analyze_surface_flinger_queue_phase(
            video_frame_timing,
            surface_flinger_events,
            producer_queue_events,
            reference_period_ns=video_reference_ns,
            fixed_window=fixed_window,
            stage_groups=stage_groups,
        )
    surface_flinger_queue_phase_analysis["per_stream"] = producer_stream_analyses
    surface_flinger_queue_phase_analysis["primary_stream_selection_reason"] = (
        "primary stream retained for backward-compatible summary; per_stream contains every "
        "producer thread and must be checked before attributing the phase to one producer"
    )
    native_vp_duration_phase_analysis["queue_phase_cross_tab"] = (
        analyze_vp_duration_queue_phase_cross_tab(
            native_vp_duration_phase_analysis,
            surface_flinger_queue_phase_analysis,
        )
    )
    surface_flinger_latch_analysis = analyze_surface_flinger_latch_gaps(
        video_frame_timing,
        video_interval_rows,
        surface_flinger_events,
        reference_period_ns=video_reference_ns,
        gap_threshold_periods=gap_threshold_periods,
        fixed_window=fixed_window,
    )
    surface_flinger_per_frame = surface_flinger_latch_analysis.pop("_by_frame_index", {})
    for timing in video_frame_timing:
        frame_index = to_int(timing.get("frame_index"))
        timing["surface_flinger_latch_diagnostic"] = (
            surface_flinger_per_frame.get(frame_index)
            if frame_index is not None
            else None
        )
    renderer_cadence_analysis = analyze_renderer_cadence(
        video_frame_timing,
        video_interval_rows,
        renderer_events,
        reference_period_ns=video_reference_ns,
        gap_threshold_periods=gap_threshold_periods,
        fixed_window=fixed_window,
        receiver_clock_offset_ns=clock_calibration.get(
            "offset_boottime_minus_monotonic_ns"
        ),
    )
    native_display_trace_analysis = analyze_native_display_trace(
        renderer_events.get("native_display_events", {}),
        renderer_cadence_analysis.get("receiver_dispatch_analysis", {}),
        renderer_events.get("display_callback_events", {}),
    )
    surface_texture_handoff_analysis = analyze_surface_texture_handoff(
        renderer_events.get("surface_texture_events", {}),
        renderer_cadence_analysis.get("receiver_dispatch_analysis", {}),
        renderer_cadence_analysis.get("video_gap_observations", []),
    )
    surface_texture_probe_analysis = analyze_surface_texture_probe_handoff(
        renderer_events.get("surface_texture_probe_events", {}),
        renderer_cadence_analysis.get("receiver_dispatch_analysis", {}),
        renderer_cadence_analysis.get("video_gap_observations", []),
    )
    renderer_per_frame = renderer_cadence_analysis.pop("_by_frame_index", {})
    for timing in video_frame_timing:
        frame_index = to_int(timing.get("frame_index"))
        timing["renderer_cadence_diagnostic"] = (
            renderer_per_frame.get(frame_index)
            if frame_index is not None
            else None
        )
    fixed_scheduler_alignment = summarize_frame_link_alignment(
        video_frame_timing, fixed_window, "scheduler_identity_link"
    )
    fixed_native_alignment = summarize_frame_link_alignment(
        video_frame_timing, fixed_window, "native_handoff_link"
    )
    fixed_window["scheduler_target_alignment"] = fixed_scheduler_alignment
    fixed_window["native_handoff_alignment"] = fixed_native_alignment
    frame_timing_examples = timing_examples(video_frame_timing, video_interval_rows)

    all_stage_frame_numbers = set(stage_groups)
    presented_frame_numbers = {
        to_int(row.get("frame_number"))
        for row in present_rows
        if to_int(row.get("frame_number")) is not None
    }
    stage_only_frame_numbers = sorted(all_stage_frame_numbers - presented_frame_numbers)

    video_span_ms = None
    if len(video_timestamps) >= 2:
        video_span_ms = (max(video_timestamps) - min(video_timestamps)) / 1e6
    trace_duration_ms = bounds.get("duration_ms")
    video_coverage_ratio = (
        None
        if video_span_ms is None or not trace_duration_ms or trace_duration_ms <= 0
        else video_span_ms / trace_duration_ms
    )
    video_frame_numbers = [to_int(row.get("frame_number")) for row in present_rows]
    video_frame_numbers = [number for number in video_frame_numbers if number is not None]
    frame_number_intervals = [
        current - previous for previous, current in zip(video_frame_numbers, video_frame_numbers[1:])
    ]
    continuous_video_start_margin_ms = (
        None
        if not video_timestamps or bounds.get("start_ts_ns") is None
        else (min(video_timestamps) - bounds["start_ts_ns"]) / 1e6
    )
    continuous_video_end_margin_ms = (
        None
        if not video_timestamps or bounds.get("end_ts_ns") is None
        else (bounds["end_ts_ns"] - max(video_timestamps)) / 1e6
    )

    required_source_checks = {
        "display_expected_stream": len(expected_rows) >= 100 and display_timeline_period_ns is not None,
        "display_actual_stream": len(actual_rows) >= 100,
        "video_present_fence_stream": len(present_rows) >= 100 and video_layer is not None,
        "video_frame_identity": len(video_frame_numbers) >= 100 and len(set(video_frame_numbers)) >= 100,
        "trace_duration_at_least_20s": bool(trace_duration_ms is not None and trace_duration_ms >= 20_000),
        "video_present_span_at_least_20s": bool(video_span_ms is not None and video_span_ms >= 20_000),
    }
    required_sources_valid = all(required_source_checks.values())
    coverage_status = (
        "UNKNOWN"
        if video_coverage_ratio is None
        else "CONTINUOUS_WINDOW"
        if video_coverage_ratio >= 0.9 and (continuous_video_end_margin_ms or 0) <= 2_000
        else "PARTIAL_WINDOW"
    )
    sensor_status = "VALID" if required_sources_valid else "INSUFFICIENT_DATA"
    scheduler_conclusion_status = (
        "VALID_BASELINE"
        if required_sources_valid and coverage_status == "CONTINUOUS_WINDOW"
        else "VALID_SOURCE_PARTIAL_WINDOW"
        if required_sources_valid
        else "INSUFFICIENT_DATA"
    )

    display_deadline_values = [
        item["display_present_minus_expected_deadline_ms"]
        for item in display_pairs_rows
        if item["display_present_minus_expected_deadline_ms"] is not None
    ]
    display_deadline_above = [value for value in display_deadline_values if value > display_deadline_threshold_ms]
    temporal_join_deltas = [
        abs(to_int(item["nearest_display_actual_temporal_only"].get("display_actual_delta_ns")))
        for item in video_frame_timing
        if item["nearest_display_actual_temporal_only"].get("display_actual_delta_ns") is not None
    ]
    video_summary["nearest_display_actual_temporal_join"] = {
        "count": len(temporal_join_deltas),
        "median_abs_delta_ns": None if not temporal_join_deltas else statistics.median(temporal_join_deltas),
        "p99_abs_delta_ns": None if not temporal_join_deltas else percentile(temporal_join_deltas, 0.99),
        "max_abs_delta_ns": None if not temporal_join_deltas else max(temporal_join_deltas),
        "join_method": "temporal_nearest_same_trace_clock",
        "identity_note": "This is a time-nearest cross-check; it is not a shared DisplayFrame token join.",
    }
    media3_source_mode = telemetry["source_mode"]
    native_source_mode = native_telemetry["source_mode"]
    if str(media3_source_mode.get("status", "")).startswith("VERIFIED_PTS"):
        source_mode = {
            **media3_source_mode,
            "primary_evidence": "media3_frame_timing.csv:MediaCodec PTS/frameIndex",
        }
    elif str(native_source_mode.get("status", "")).startswith("VERIFIED_IJK_QUEUE_PTS"):
        source_mode = {
            **native_source_mode,
            "primary_evidence": "refresh_samples.csv:ijk_queue_picture",
            "media3_status": media3_source_mode.get("status", "UNKNOWN"),
        }
    else:
        source_mode = {
            **media3_source_mode,
            "primary_evidence": "NONE",
            "native_status": native_source_mode.get("status", "UNKNOWN"),
        }
    source_mode_status = str(source_mode.get("status", "UNKNOWN"))
    cadence_observed = "OBSERVED" if video_summary.get("present_count", 0) >= 2 else "UNKNOWN"
    fixed_window_public = {
        key: value
        for key, value in fixed_window.items()
        if key != "video_interval_observations"
    }

    report = {
        "schema": "bili-frame-timeline-analysis-v2",
        "trace_path": str(trace_path),
        "package_name": package_name,
        "sensor_quality": {
            "status": sensor_status,
            "scheduler_conclusion_status": scheduler_conclusion_status,
            "required_source_checks": required_source_checks,
            "trace_bounds": bounds,
            "video_present_span_ms": video_span_ms,
            "video_coverage_ratio_of_trace_span": video_coverage_ratio,
            "video_start_margin_ms": continuous_video_start_margin_ms,
            "video_end_margin_ms": continuous_video_end_margin_ms,
            "coverage_status": coverage_status,
            "packet_loss_total": stats["packet_loss_total"],
            "ftrace_overrun_delta_total": stats["ftrace_overrun_delta_total"],
            "systrace_parse_failure_total": stats["systrace_parse_failure_total"],
            "fixed_analysis_window_status": fixed_window.get("status"),
            "quality_note": (
                "Source validity and cadence/jank verdict are separate. A PARTIAL_WINDOW still exposes all "
                "captured PresentFenceSignaled events, but it is not a complete scheduler baseline."
            ),
        },
        "measurement": {
            "display_expected_source": "expected_frame_timeline_slice.ts",
            "display_expected_semantics": "FrameTimeline prediction start; expected end is the predicted deadline, not an asserted physical panel-present timestamp",
            "display_actual_source": "actual_frame_timeline_slice.ts + dur",
            "display_actual_semantics": "DisplayFrame onPresent/presentTime end in the trace; not a scanline timestamp",
            "video_present_source": "frame_slice where name='PresentFenceSignaled' and selected layer",
            "video_present_semantics": "identified video buffer present-fence event; strongest software-side final-present proxy available in this trace",
            "clock_domain": "same Perfetto trace clock for temporal comparisons",
            "physical_panel_scanline_signal": "not captured",
            "physical_panel_scanline_note": "No per-scanline or raw TE signal is present; FrameTimeline and PresentFenceSignaled must not be labeled scanout completion.",
            "raw_vsync_slice_candidates": raw_vsync_candidates,
            "display_expected_stream_selection": expected_selection,
            "display_actual_stream_selection": actual_selection,
            "video_layer_selection": video_selection,
            "display_expected_timeline_period_ms": (
                None if display_timeline_period_ns is None else display_timeline_period_ns / 1e6
            ),
            "display_expected_timeline_period_source": "median delta of expected_frame_timeline_slice.ts",
            "display_expected_timeline_period_semantics": (
                "inferred cadence of FrameTimeline prediction samples; not a per-tick measured HWC VSync period"
            ),
            "measured_hwc_vsync_period_ms": None,
            "vsync_signal_status": "NOT_CAPTURED",
            "surface_flinger_scheduler_events": {
                "status": surface_flinger_events.get("status"),
                "commit_cycle_count": len(surface_flinger_events.get("commit_cycles", [])),
                "begin_frame_cycle_count": len(surface_flinger_events.get("begin_frame_cycles", [])),
                "predictor_frame_begin_count": len(surface_flinger_events.get("predictor_frame_begin_ts_ns", [])),
                "should_latch_unsignaled_event_count": len(
                    surface_flinger_events.get("should_latch_unsignaled_events", [])
                ),
                "unsignaled_fence_event_count": len(
                    surface_flinger_events.get("unsignaled_fence_events", [])
                ),
                "set_buffer_event_count": len(surface_flinger_events.get("set_buffer_events", [])),
                "latch_buffer_event_count": len(surface_flinger_events.get("latch_buffer_events", [])),
                "source": surface_flinger_events.get("source"),
            },
            "renderer_cadence": {
                "status": renderer_cadence_analysis.get("status"),
                "do_frame_count": renderer_cadence_analysis.get("do_frame_count"),
                "on_vsync_count": renderer_cadence_analysis.get("on_vsync_count"),
                "egl_swap_count": renderer_cadence_analysis.get("egl_swap_count"),
                "renderer_do_frame_gap_count": renderer_cadence_analysis.get(
                    "renderer_do_frame_gap_count"
                ),
                "display_callback_count": renderer_cadence_analysis.get(
                    "display_callback_count"
                ),
                "renderer_gaps_with_display_callback": renderer_cadence_analysis.get(
                    "renderer_gaps_with_display_callback"
                ),
                "renderer_gaps_without_display_callback": renderer_cadence_analysis.get(
                    "renderer_gaps_without_display_callback"
                ),
                "native_display_trace_count": native_display_trace_analysis.get(
                    "native_display_count"
                ),
                "native_display_call_count": native_display_trace_analysis.get(
                    "native_display_call_count"
                ),
                "native_display_gaps_crossing_expected": native_display_trace_analysis.get(
                    "dispatch_gaps_native_display_crossing_expected"
                ),
                "native_display_call_gaps_crossing_expected": native_display_trace_analysis.get(
                    "dispatch_gaps_native_call_crossing_expected"
                ),
                "binder_display_callback_count": native_display_trace_analysis.get(
                    "binder_display_callback_count"
                ),
                "binder_gaps_before_expected": native_display_trace_analysis.get(
                    "dispatch_gaps_binder_before_expected"
                ),
                "display_callback_gaps_after_expected": native_display_trace_analysis.get(
                    "dispatch_gaps_display_callback_after_expected"
                ),
                "surface_texture_callback_count": surface_texture_handoff_analysis.get(
                    "surface_texture_callback_count"
                ),
                "surface_texture_gaps_with_callback": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_with_surface_texture_callback"
                ),
                "surface_texture_gaps_before_expected": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_surface_texture_before_expected"
                ),
                "surface_texture_gaps_after_expected": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_surface_texture_after_expected"
                ),
                "surface_texture_gaps_crossing_expected": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_surface_texture_crossing_expected"
                ),
                "surface_texture_video_gap_matches": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_temporally_matched_to_video_gap"
                ),
                "surface_texture_video_gap_matches_after_expected": surface_texture_handoff_analysis.get(
                    "video_gap_matches_surface_texture_after_expected"
                ),
                "surface_texture_probe_frame_ready_count": surface_texture_probe_analysis.get(
                    "frame_ready_count"
                ),
                "surface_texture_probe_update_count": surface_texture_probe_analysis.get(
                    "update_count"
                ),
                "surface_texture_probe_update_timestamp_count": surface_texture_probe_analysis.get(
                    "update_timestamp_count"
                ),
                "surface_texture_probe_producer_timestamp_distinct_count": surface_texture_probe_analysis.get(
                    "producer_timestamp_distinct_count"
                ),
                "surface_texture_probe_ready_update_pair_after_expected": surface_texture_probe_analysis.get(
                    "dispatch_gaps_ready_update_pair_after_expected"
                ),
                "surface_texture_probe_video_gap_ready_update_after_expected": surface_texture_probe_analysis.get(
                    "video_gap_matches_ready_update_after_expected"
                ),
                "surface_texture_probe_ready_update_match_count": surface_texture_probe_analysis.get(
                    "token_ready_update_match_count"
                ),
                "surface_texture_probe_video_gap_matches": surface_texture_probe_analysis.get(
                    "dispatch_gaps_temporally_matched_to_video_gap"
                ),
                "ordered_binder_callback_gaps_crossing_expected": native_display_trace_analysis.get(
                    "dispatch_gaps_ordered_binder_before_callback_after"
                ),
                "renderer_on_vsync_gap_count": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gap_count"
                ),
                "renderer_on_vsync_gaps_with_app_vsync": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_with_app_vsync"
                ),
                "renderer_on_vsync_gaps_with_schedule": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_with_schedule"
                ),
                "renderer_on_vsync_gaps_schedule_before_app_on_vsync": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_schedule_before_app_on_vsync"
                ),
                "renderer_on_vsync_gaps_schedule_at_or_after_app_on_vsync": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_schedule_at_or_after_app_on_vsync"
                ),
                "source": renderer_events.get("source"),
                "display_callback_source": renderer_events.get(
                    "display_callback_events", {}
                ).get("source"),
            },
            "nominal_source_period_ms": nominal_period_ns / 1e6,
            "observed_video_surface_cadence_fps": video_summary.get("median_cadence_fps"),
            "observed_video_surface_effective_fps": video_summary.get("effective_present_fps"),
        },
        "display_frame_timing_summary": {
            **display_pair_summary,
            "actual_present_interval": actual_present_metrics,
            "jank_type_counts": dict(display_jank_counts),
            "display_present_minus_expected_deadline_above_threshold_count": len(display_deadline_above),
            "display_present_minus_expected_deadline_threshold_ms": display_deadline_threshold_ms,
            "display_present_minus_expected_deadline_threshold_note": "This is a DisplayFrame deadline diagnostic, not a video buffer present-lateness verdict.",
        },
        "display_frame_timing": display_pairs_rows,
        "video_present_summary": video_summary,
        "video_present_timing": video_frame_timing,
        "video_interval_observations": video_interval_rows,
        "fixed_analysis_window": fixed_window_public,
        "timing_examples": frame_timing_examples,
        "source_mode": source_mode,
        "media3_telemetry": {
            key: value for key, value in telemetry.items() if not str(key).startswith("_")
        },
        "native_telemetry": {
            key: value for key, value in native_telemetry.items() if not str(key).startswith("_")
        },
        "cadence_observed": {
            "status": cadence_observed,
            "scope": "selected video SurfaceView PresentFenceSignaled stream",
            "present_count": video_summary.get("present_count"),
            "present_fps": video_summary.get("effective_present_fps"),
            "median_interval_fps": video_summary.get("median_cadence_fps"),
            "note": "Observed final-buffer cadence is not source FPS and is not a target-VSync decision.",
        },
        "scheduler_target_alignment": scheduler_target_alignment,
        "scheduler_render_alignment": scheduler_render_alignment,
        "scheduler_native_alignment": scheduler_native_alignment,
        "native_handoff_alignment": native_handoff_alignment,
        "native_scheduler_phase_analysis": native_scheduler_phase_analysis,
        "native_vp_duration_phase_analysis": native_vp_duration_phase_analysis,
        "surface_flinger_latch_analysis": surface_flinger_latch_analysis,
        "surface_flinger_buffer_replacement_analysis": surface_flinger_buffer_replacement_analysis,
        "surface_flinger_queue_phase_analysis": surface_flinger_queue_phase_analysis,
        "surface_flinger_producer_queue_events": {
            key: value
            for key, value in producer_queue_events.items()
            if key not in {"events", "selected_events", "stream_events"}
        },
        "surface_flinger_scheduler_events": surface_flinger_events,
        "renderer_cadence_analysis": renderer_cadence_analysis,
        "renderer_cadence_events": renderer_events,
        "native_display_trace_analysis": native_display_trace_analysis,
        "surface_texture_handoff_analysis": surface_texture_handoff_analysis,
        "surface_texture_probe_analysis": surface_texture_probe_analysis,
        "status_summary": {
            "workflow_status": "ANALYSIS_COMPLETED",
            "sensor_quality": sensor_status,
            "source_mode_status": source_mode_status,
            "cadence_observed": cadence_observed,
            "scheduler_target_alignment": scheduler_target_alignment.get("status"),
            "scheduler_render_alignment": scheduler_render_alignment.get("status"),
            "scheduler_native_alignment": scheduler_native_alignment.get("status"),
            "native_handoff_status": native_handoff_alignment.get("status"),
            "native_scheduler_phase_status": native_scheduler_phase_analysis.get("status"),
            "native_scheduler_phase_conclusion": native_scheduler_phase_analysis.get("conclusion"),
            "native_vp_duration_phase_status": native_vp_duration_phase_analysis.get("status"),
            "native_vp_duration_phase_conclusion": native_vp_duration_phase_analysis.get("conclusion"),
            "surface_flinger_latch_analysis_status": surface_flinger_latch_analysis.get("status"),
            "surface_flinger_buffer_replacement_status": surface_flinger_buffer_replacement_analysis.get("status"),
            "surface_flinger_queue_phase_status": surface_flinger_queue_phase_analysis.get("status"),
            "surface_flinger_queue_phase_conclusion": surface_flinger_queue_phase_analysis.get("conclusion"),
            "surface_flinger_queued_non_latched_count": surface_flinger_buffer_replacement_analysis.get(
                "queued_non_latched_count"
            ),
            "surface_flinger_two_slot_gap_candidate_count": surface_flinger_buffer_replacement_analysis.get(
                "two_slot_gap_candidate_count"
            ),
            "surface_flinger_missing_present_frame_count": surface_flinger_queue_phase_analysis.get(
                "missing_present_frame_count"
            ),
            "renderer_cadence_status": renderer_cadence_analysis.get("status"),
            "native_display_trace_status": native_display_trace_analysis.get("status"),
            "surface_texture_handoff_status": surface_texture_handoff_analysis.get("status"),
            "surface_texture_probe_status": surface_texture_probe_analysis.get("status"),
            "interpretation": "No status here means the video is free of jank; these are independent evidence states.",
        },
        "video_surface_submission": {
            "source": "frame_slice.PresentFenceSignaled.ts",
            "selected_layer": video_layer,
            "all_stage_counts": dict(Counter(row.get("name") for row in video_rows)),
            "frame_number_min": min(video_frame_numbers) if video_frame_numbers else None,
            "frame_number_max": max(video_frame_numbers) if video_frame_numbers else None,
            "frame_number_nonunit_delta_count": sum(delta != 1 for delta in frame_number_intervals),
            "frame_number_nonunit_delta_max": max(frame_number_intervals, default=None),
            "stage_only_frame_number_count": len(stage_only_frame_numbers),
            "stage_only_frame_numbers_sample": stage_only_frame_numbers[:100],
            "present_event_count": len(present_rows),
            "present_event_interval_summary": interval_summary(
                video_timestamps,
                reference_period_ns=video_reference_ns,
                gap_threshold_periods=gap_threshold_periods,
                nominal_period_ns=nominal_period_ns,
            ),
        },
        "video_set_buffer_secondary": set_buffer_secondary,
        "decoder_release": interval_summary(decoder_release_ts),
        "vsync_predictor_frame_begin": interval_summary(vsync_callback_ts),
        "trace_stats": stats,
        "methodology": {
            "primary_jank_signal": "video PresentFenceSignaled intervals plus frame identity and Queue/Acquire/Latch stage joins",
            "whole_slot_rule": "round(delta/video_present_median_period) is recorded with residual; a 2-slot interval is an observed cadence event, not automatically a dropped source frame",
            "video_cadence_reference": {
                "source": "median interval of selected video PresentFenceSignaled.ts",
                "semantics": "measured buffer-present cadence estimate used only for interval classification",
                "not_a_target_vsync": True,
            },
            "display_timeline_reference": {
                "source": "median delta of expected_frame_timeline_slice.ts",
                "semantics": "predicted display timeline cadence estimate; not raw HWC VSync or panel scanout",
                "measured_hwc_vsync_available": False,
            },
            "native_handoff_reference": {
                "source": "refresh_samples.csv ijk_queue_picture and ijk_display_picture",
                "semantics": "IJK queue/display PTS source identity first, then temporal correlation to PresentFence",
                "exact_buffer_identity_available": False,
                "scheduler_native_pts_identity_available": scheduler_native_alignment.get("status") not in {"UNKNOWN", "UNMATCHED"},
                "scheduler_native_alignment": scheduler_native_alignment.get("status"),
                "clock_calibration": "CLOCK_MONOTONIC to Perfetto BOOTTIME via clock_snapshot",
            },
            "surface_flinger_latch_reference": {
                "source": "slice commit/beginFrame/shouldLatchUnsignaled/fence unsignaled/setBuffer/latchBuffer",
                "semantics": "SurfaceFlinger commit-cycle and selected-layer latch correlation",
                "shared_codec_renderer_buffer_identity_available": False,
                "interpretation_boundary": (
                    "Strong final-pipeline evidence, but temporal/frame_number correlation is not a shared "
                    "MediaCodec-to-external-renderer buffer identity."
                ),
            },
            "surface_flinger_buffer_replacement_reference": {
                "source": "selected video layer frame_number + Queue/Acquire/Latch/Present stages + setBuffer/latchBuffer",
                "semantics": (
                    "SurfaceFlinger-internal exact classification of a queued buffer with no latch/present "
                    "before a following present gap"
                ),
                "evidence_quality": surface_flinger_buffer_replacement_analysis.get("evidence_quality"),
                "cross_layer_identity_available": surface_flinger_buffer_replacement_analysis.get(
                    "cross_layer_identity_available"
                ),
                "interpretation_boundary": (
                    "This can localize a final-pipeline queued-buffer failure inside SurfaceFlinger, but "
                    "cannot identify the upstream MediaCodec or native producer buffer."
                ),
            },
            "surface_flinger_queue_phase_reference": {
                "source": "slice queueBuffer on IJK producer threads + selected video Queue + SurfaceFlinger commit",
                "semantics": "producer cadence and SurfaceFlinger commit phase temporal comparison",
                "evidence_quality": surface_flinger_queue_phase_analysis.get("evidence_quality"),
                "cross_layer_identity_available": surface_flinger_queue_phase_analysis.get(
                    "cross_layer_identity_available"
                ),
                "interpretation_boundary": (
                    "A phase separation with unchanged producer-to-SurfaceFlinger delay shifts the "
                    "candidate toward upstream cadence/phase, but does not identify a codec buffer."
                ),
            },
            "renderer_cadence_reference": {
                "source": "slice Choreographer#doFrame/onVsync/eglSwapBuffers on thread ijk_ext_render",
                "semantics": "external-render thread cadence and temporal correlation to video Queue",
                "shared_codec_renderer_buffer_identity_available": False,
                "interpretation_boundary": (
                    "A renderer cadence gap can locate the missing slot before SurfaceFlinger latch, but "
                    "cannot identify the codec frame without a shared buffer key."
                ),
            },
            "display_callback_reference": {
                "source": "slice BiliP2_display_callback/BiliP2_display_callback_end markers across all processes",
                "semantics": "IJK Java display-callback occurrence temporally compared with renderer doFrame gaps",
                "paired_callback_count": renderer_cadence_analysis.get(
                    "display_callback_paired_count"
                ),
                "callback_in_renderer_gap_count": renderer_cadence_analysis.get(
                    "renderer_gaps_with_display_callback"
                ),
                "callback_absent_in_renderer_gap_count": renderer_cadence_analysis.get(
                    "renderer_gaps_without_display_callback"
                ),
                "interpretation_boundary": (
                    "A callback inside a renderer cadence gap proves callback arrival before the next "
                    "renderer doFrame in trace time; it does not prove the callback's frame was the "
                    "buffer later presented by SurfaceFlinger."
                ),
            },
            "surface_texture_handoff_reference": {
                "source": surface_texture_handoff_analysis.get("source"),
                "semantics": (
                    "SurfaceTexture OnFrameAvailable callback timing compared with renderer dispatch gaps"
                ),
                "callback_count": surface_texture_handoff_analysis.get(
                    "surface_texture_callback_count"
                ),
                "dispatch_gap_count": surface_texture_handoff_analysis.get(
                    "dispatch_gap_count"
                ),
                "gaps_with_callback": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_with_surface_texture_callback"
                ),
                "gaps_callback_before_expected_vsync": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_surface_texture_before_expected"
                ),
                "gaps_callback_after_expected_vsync": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_surface_texture_after_expected"
                ),
                "gaps_callback_crossing_expected_vsync": surface_texture_handoff_analysis.get(
                    "dispatch_gaps_surface_texture_crossing_expected"
                ),
                "interpretation_boundary": (
                    "A callback after the expected VSync supports a late producer-to-Java handoff; "
                    "a callback before the expected VSync with a late renderer dispatch shifts the "
                    "candidate toward ijk_ext_render scheduling. Neither result proves the same "
                    "buffer reached SurfaceFlinger without a shared buffer identity."
                ),
            },
            "surface_texture_probe_reference": {
                "source": surface_texture_probe_analysis.get("source"),
                "semantics": (
                    "Object-tokenized SurfaceTexture frame-ready marker paired with updateTexImage "
                    "on the external-render path"
                ),
                "frame_ready_count": surface_texture_probe_analysis.get("frame_ready_count"),
                "update_count": surface_texture_probe_analysis.get("update_count"),
                "token_ready_update_match_count": surface_texture_probe_analysis.get(
                    "token_ready_update_match_count"
                ),
                "update_timestamp_count": surface_texture_probe_analysis.get(
                    "update_timestamp_count"
                ),
                "producer_timestamp_distinct_count": surface_texture_probe_analysis.get(
                    "producer_timestamp_distinct_count"
                ),
                "producer_timestamp_interval": surface_texture_probe_analysis.get(
                    "producer_timestamp_interval"
                ),
                "producer_timestamp_by_token": surface_texture_probe_analysis.get(
                    "producer_timestamp_by_token"
                ),
                "ready_update_pair_after_expected": surface_texture_probe_analysis.get(
                    "dispatch_gaps_ready_update_pair_after_expected"
                ),
                "video_gap_ready_update_to_queue": surface_texture_probe_analysis.get(
                    "video_gap_ready_update_to_queue"
                ),
                "video_gap_do_frame_to_ready_update": surface_texture_probe_analysis.get(
                    "video_gap_do_frame_to_ready_update"
                ),
                "video_gap_expected_to_ready_update": surface_texture_probe_analysis.get(
                    "video_gap_expected_to_ready_update"
                ),
                "ready_to_update_interval": surface_texture_probe_analysis.get(
                    "ready_to_update_interval"
                ),
                "interpretation_boundary": (
                    "The object token closes the Java SurfaceTexture handoff within one process, "
                    "but it still cannot prove which MediaCodec buffer or SurfaceFlinger frame "
                    "was consumed."
                ),
            },
            "renderer_vsync_receive_reference": {
                "source": "renderer ijk_ext_render DisplayEventReceiver.dispatchVsync/Choreographer#onVsync gaps compared with app-process onVsync and renderer scheduleVsyncLocked",
                "semantics": "same-process temporal witness for a renderer VSync delivery gap and one-shot scheduling phase",
                "gap_count": renderer_cadence_analysis.get("renderer_on_vsync_gap_count"),
                "gap_with_app_vsync_count": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_with_app_vsync"
                ),
                "gap_with_schedule_count": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_with_schedule"
                ),
                "schedule_before_app_on_vsync_count": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_schedule_before_app_on_vsync"
                ),
                "schedule_at_or_after_app_on_vsync_count": renderer_cadence_analysis.get(
                    "renderer_on_vsync_gaps_schedule_at_or_after_app_on_vsync"
                ),
                "schedule_minus_app_on_vsync": renderer_cadence_analysis.get(
                    "renderer_schedule_minus_app_on_vsync"
                ),
                "dispatch_gap_count": renderer_cadence_analysis.get(
                    "receiver_dispatch_analysis", {}
                ).get("dispatch_gap_count"),
                "dispatch_gap_with_app_vsync_count": renderer_cadence_analysis.get(
                    "receiver_dispatch_analysis", {}
                ).get("dispatch_gaps_with_app_vsync"),
                "dispatch_gap_schedule_after_expected_vsync_count": renderer_cadence_analysis.get(
                    "receiver_dispatch_analysis", {}
                ).get("dispatch_gaps_with_schedule_after_expected_vsync"),
                "dispatch_gap_schedule_within_1ms_before_expected_vsync_count": renderer_cadence_analysis.get(
                    "receiver_dispatch_analysis", {}
                ).get("dispatch_gaps_with_schedule_within_1ms_before_expected_vsync"),
                "interpretation_boundary": (
                    "App-process onVsync is a scheduling witness, not a raw panel signal. "
                    "A renderer dispatch gap with an app VSync and a schedule after the expected "
                    "renderer VSync locates a one-shot scheduling miss; it still does not measure "
                    "panel scanout or identify codec-to-buffer identity."
                ),
            },
            "codec_render_callback_reference": {
                "source": "media3_frame_timing.csv FRAME/FRAME_RENDERED",
                "semantics": "MediaCodec.OnFrameRenderedListener callback PTS and rendered nanoTime matched to scheduler PTS",
                "panel_scanout_identity_available": False,
                "scheduler_render_alignment": scheduler_render_alignment.get("status"),
                "match_time_key": scheduler_render_alignment.get("match_time_key"),
                "callback_dispatch_delay_note": "Callback dispatch time can be delayed or batched; rendered nanoTime is the occurrence anchor when present.",
            },
            "large_gap_rule": "gaps over 100ms are retained and flagged as large gaps; they are not silently assigned to loop/seek",
            "old_metric_replaced": "actual.ts - expected.ts is now start_delay_ms; display end minus expected deadline is a separate DisplayFrame field",
            "media3_reference": {
                "version_sha": "8c6678b657ede1e7883fc164ef73ed483c7796c3",
                "vsync_sample_period_ms": 500,
                "api33_sample": "Choreographer.FrameData.getFrameTimeNanos",
                "api33_period": "difference between two FrameTimeline expectedPresentationTimeNanos values",
                "release_alignment": "closest VSync projected from sampled anchor and period, 80% period pre-release offset, hysteresis and 20ms adjustment bound",
                "detector_boundary": "VideoFrameReleaseHelper schedules release; it is not itself a final panel-jank detector",
            },
        },
        "decision": {
            "status": sensor_status,
            "workflow_status": "ANALYSIS_COMPLETED",
            "source_mode_status": source_mode_status,
            "cadence_observed": cadence_observed,
            "scheduler_target_alignment_status": scheduler_target_alignment.get("status"),
            "scheduler_render_alignment_status": scheduler_render_alignment.get("status"),
            "scheduler_native_alignment_status": scheduler_native_alignment.get("status"),
            "native_handoff_status": native_handoff_alignment.get("status"),
            "surface_flinger_latch_analysis_status": surface_flinger_latch_analysis.get("status"),
            "renderer_cadence_status": renderer_cadence_analysis.get("status"),
            "surface_texture_handoff_status": surface_texture_handoff_analysis.get("status"),
            "surface_texture_probe_status": surface_texture_probe_analysis.get("status"),
            "scheduler_conclusion_status": scheduler_conclusion_status,
            "cadence_anomaly_observed": bool(video_summary.get("small_cadence_gap_count", 0) or video_summary.get("interval_gt_100ms_count", 0)),
            "reason": (
                "Primary video PresentFenceSignaled and identity streams are available; inspect the fixed 30-second window and full interval observations."
                if sensor_status == "VALID"
                else "Required display or identified video PresentFenceSignaled sources are missing or too short."
            ),
            "gap_threshold_periods": gap_threshold_periods,
            "lateness_threshold_ms_deprecated_for_video": display_deadline_threshold_ms,
        },
    }
    return report


def main(argv: Sequence[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace_path")
    parser.add_argument("report_path")
    parser.add_argument("package_name")
    parser.add_argument("--gap-threshold-periods", type=float, default=1.5)
    parser.add_argument("--display-deadline-threshold-ms", type=float, default=2.42)
    parser.add_argument("--nominal-source-fps", type=float, default=NOMINAL_SOURCE_FPS)
    parser.add_argument("--telemetry-csv", default=None)
    parser.add_argument("--native-telemetry-csv", default=None)
    args = parser.parse_args(argv)
    report = analyze_trace(
        args.trace_path,
        args.package_name,
        gap_threshold_periods=args.gap_threshold_periods,
        display_deadline_threshold_ms=args.display_deadline_threshold_ms,
        nominal_source_fps=args.nominal_source_fps,
        telemetry_path=args.telemetry_csv,
        native_telemetry_path=args.native_telemetry_csv,
    )
    Path(args.report_path).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Sensor status: {report['sensor_quality']['status']}")
    print(f"Scheduler baseline status: {report['sensor_quality']['scheduler_conclusion_status']}")
    print(f"Video layer: {report['measurement']['video_layer_selection'].get('selected')}")
    print(
        "Display expected/actual / video PresentFence events: "
        f"{len(report['display_frame_timing'])}/"
        f"{report['display_frame_timing_summary']['paired_count'] + report['display_frame_timing_summary']['unmatched_actual_count']}/"
        f"{report['video_present_summary']['present_count']}"
    )
    print(
        "Video intervals > nominal / > gap threshold / >100ms: "
        f"{report['video_present_summary']['interval_gt_nominal_source_period_count']}/"
        f"{report['video_present_summary']['interval_gt_gap_threshold_count']}/"
        f"{report['video_present_summary']['interval_gt_100ms_count']}"
    )
    print(
        "Video cadence median / 1% low all / 1% low <=100ms: "
        f"{report['video_present_summary']['median_cadence_fps']}/"
        f"{report['video_present_summary']['one_percent_low_fps_all_intervals']}/"
        f"{report['video_present_summary']['one_percent_low_fps_excluding_over_100ms']}"
    )
    fixed = report["fixed_analysis_window"]
    fixed_summary = fixed.get("video_present_summary", {})
    print(
        "Fixed 30s window status / presents / cadence gaps / 1% low: "
        f"{fixed.get('status')}/{fixed.get('present_count', 0)}/"
        f"{fixed_summary.get('small_cadence_gap_count')}/"
        f"{fixed_summary.get('one_percent_low_fps_excluding_over_100ms')}"
    )
    print(
        "Source mode / cadence observed / scheduler target alignment: "
        f"{report['status_summary']['source_mode_status']}/"
        f"{report['status_summary']['cadence_observed']}/"
        f"{report['status_summary']['scheduler_target_alignment']}"
    )
    probe = report["surface_texture_probe_analysis"]
    print(
        "SurfaceTexture probe ready / update / timestamp / token matches / dispatch-video matches: "
        f"{probe.get('frame_ready_count')}/"
        f"{probe.get('update_count')}/"
        f"{probe.get('update_timestamp_count')}/"
        f"{probe.get('token_ready_update_match_count')}/"
        f"{probe.get('dispatch_gaps_temporally_matched_to_video_gap')}"
    )
    print(
        "Native IJK handoff / queue / display temporal matches: "
        f"{report['status_summary'].get('native_handoff_status', 'UNKNOWN')}/"
        f"{report.get('native_handoff_alignment', {}).get('queue_temporal_match_count', 0)}/"
        f"{report.get('native_handoff_alignment', {}).get('display_temporal_match_count', 0)}"
    )
    scheduler_native = report.get("scheduler_native_alignment", {})
    print(
        "Scheduler/native PTS exact queue / display: "
        f"{scheduler_native.get('status', 'UNKNOWN')}/"
        f"{scheduler_native.get('queue', {}).get('exact_pts_count', 0)}/"
        f"{scheduler_native.get('display', {}).get('exact_pts_count', 0)}"
    )
    scheduler_render = report.get("scheduler_render_alignment", {})
    print(
        "Scheduler/render-callback PTS exact / callback count: "
        f"{scheduler_render.get('status', 'UNKNOWN')}/"
        f"{scheduler_render.get('exact_pts_count', 0)}/"
        f"{scheduler_render.get('render_callback_count', 0)}"
    )
    print(f"Analysis report: {args.report_path}")
    return 0 if report["sensor_quality"]["status"] == "VALID" else 2


if __name__ == "__main__":
    sys.exit(main())
