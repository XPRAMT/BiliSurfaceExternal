import sys
import tempfile
import unittest
from pathlib import Path


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from FrameTimelineAnalysis import (  # noqa: E402
    analyze_frame_receiver_dispatch,
    analyze_native_display_trace,
    analyze_native_scheduler_phase,
    analyze_native_vp_duration_phase,
    analyze_vp_duration_queue_phase_cross_tab,
    analyze_renderer_cadence,
    analyze_surface_flinger_buffer_replacement,
    analyze_surface_flinger_queue_phase,
    analyze_surface_flinger_latch_gaps,
    analyze_surface_texture_handoff,
    analyze_surface_texture_probe_handoff,
    classify_present_intervals,
    display_pairs,
    fixed_analysis_window,
    group_stage_events,
    load_native_telemetry,
    load_media3_telemetry,
    parse_set_buffer_frame_number,
    query_frame_receiver_events,
    query_native_display_events,
    select_display_stream,
    select_video_layer,
    stage_join_for_present,
    attach_native_telemetry,
    attach_scheduler_native_telemetry,
    attach_scheduler_render_telemetry,
    attach_scheduler_telemetry,
    match_rows_by_identity,
    timing_examples,
)


def timeline_row(token, ts, dur, layer=None, surface_token=None, upid=4, row_id=None):
    return {
        "id": row_id if row_id is not None else token,
        "ts": ts,
        "dur": dur,
        "display_frame_token": token,
        "surface_frame_token": surface_token,
        "upid": upid,
        "layer_name": layer,
    }


class FrameTimelineSemanticTests(unittest.TestCase):
    def test_frame_receiver_dispatch_gap_reports_schedule_after_app_vsync(self):
        result = analyze_frame_receiver_dispatch(
            {
                "events": {
                    "dispatch_vsync": [
                        {"ts_ns": 10, "thread_name": "ijk_ext_render"},
                        {"ts_ns": 30, "thread_name": "ijk_ext_render"},
                    ]
                }
            },
            {
                "events": {
                    "app_on_vsync": [{"ts_ns": 20}],
                    "vsync_schedule": [{"ts_ns": 25}],
                }
            },
            reference_period_ns=10,
            gap_threshold_periods=1.5,
        )

        self.assertEqual(result["dispatch_gap_count"], 1)
        self.assertEqual(result["dispatch_gaps_with_app_vsync"], 1)
        self.assertEqual(result["dispatch_gaps_with_schedule_after_app_vsync"], 1)
        self.assertEqual(
            result["dispatch_gap_observations"][0]["classification"],
            "DISPATCH_GAP_SCHEDULE_AFTER_APP_VSYNC",
        )

    def test_frame_receiver_dispatch_classifies_java_wake_against_expected_vsync(self):
        result = analyze_frame_receiver_dispatch(
            {
                "events": {
                    "dispatch_vsync": [
                        {
                            "ts_ns": -20,
                            "thread_name": "ijk_ext_render",
                            "vsync_timestamp_ns": -20,
                        },
                        {
                            "ts_ns": -10,
                            "thread_name": "ijk_ext_render",
                            "vsync_timestamp_ns": -10,
                        },
                        {
                            "ts_ns": 0,
                            "thread_name": "ijk_ext_render",
                            "vsync_timestamp_ns": 0,
                        },
                        {
                            "ts_ns": 30,
                            "thread_name": "ijk_ext_render",
                            "vsync_timestamp_ns": 20,
                        },
                        {
                            "ts_ns": 40,
                            "thread_name": "ijk_ext_render",
                            "vsync_timestamp_ns": 30,
                        },
                    ]
                }
            },
            {
                "thread_wake_events": [
                    {
                        "ts_ns": 21,
                        "waker_thread_name": "ijk_java_player",
                    }
                ]
            },
            reference_period_ns=10,
            gap_threshold_periods=1.5,
            clock_offset_ns=0,
        )

        gap = result["dispatch_gap_observations"][0]
        self.assertEqual(gap["expected_missing_vsync_trace_timestamps_ns"], [10])
        self.assertEqual(gap["ijk_java_player_wake_count"], 1)
        self.assertEqual(gap["ijk_java_player_wake_after_expected_count"], 1)
        self.assertEqual(result["renderer_ijk_java_player_wake_after_expected_count"], 1)

    def test_frame_receiver_events_keep_entry_and_run_boundaries(self):
        class FakeTraceProcessor:
            def query(self, sql):
                if "left join thread_track" in sql:
                    return [
                        {
                            "slice_id": 1,
                            "ts": 100,
                            "dur": 0,
                            "name": "BiliP2_frame_receiver_on_vsync_42_7",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "DisplayEventReceiver",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili",
                        },
                        {
                            "slice_id": 2,
                            "ts": 110,
                            "dur": 0,
                            "name": "BiliP2_frame_receiver_run_8",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "ijk_ext_render",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili",
                        },
                        {
                            "slice_id": 3,
                            "ts": 99,
                            "dur": 0,
                            "name": "BiliP2_frame_receiver_dispatch_vsync_42_t123456789_6",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "ijk_ext_render",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili",
                        },
                        {
                            "slice_id": 4,
                            "ts": 108,
                            "dur": 0,
                            "name": "BiliP2_choreographer_schedule_frame_locked_9_d42",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "ijk_ext_render",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili",
                        },
                        {
                            "slice_id": 5,
                            "ts": 109,
                            "dur": 0,
                            "name": "BiliP2_choreographer_frame_handler_front_10_d42",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "binder:30_1",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili",
                        },
                        {
                            "slice_id": 6,
                            "ts": 110,
                            "dur": 0,
                            "name": "BiliP2_choreographer_queue_enqueue_11_d42",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "binder:30_1",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili",
                        },
                    ]
                return []

        result = query_frame_receiver_events(FakeTraceProcessor())

        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["event_counts"]["dispatch_vsync"], 1)
        self.assertEqual(result["event_counts"]["schedule_frame_locked"], 1)
        self.assertEqual(result["event_counts"]["handler_front"], 1)
        self.assertEqual(result["event_counts"]["queue_enqueue"], 1)
        self.assertEqual(result["event_counts"]["on_vsync"], 1)
        self.assertEqual(result["event_counts"]["run"], 1)
        self.assertEqual(result["events"]["dispatch_vsync"][0]["frame"], 42)
        self.assertEqual(result["events"]["schedule_frame_locked"][0]["token"], 9)
        self.assertEqual(result["events"]["schedule_frame_locked"][0]["display_token"], 42)
        self.assertEqual(result["events"]["handler_front"][0]["token"], 10)
        self.assertEqual(result["events"]["queue_enqueue"][0]["token"], 11)
        self.assertEqual(result["events"]["dispatch_vsync"][0]["vsync_timestamp_ns"], 123456789)
        self.assertEqual(result["events"]["on_vsync"][0]["frame"], 42)
        self.assertEqual(result["events"]["on_vsync"][0]["token"], 7)
        self.assertEqual(result["events"]["run"][0]["thread_name"], "ijk_ext_render")

    def test_native_display_events_parse_latest_queue_annotation_and_call(self):
        class FakeTraceProcessor:
            def query(self, sql):
                if "binder transaction" in sql:
                    return [
                        {
                            "slice_id": 3,
                            "ts": 102,
                            "dur": 500,
                            "name": "binder transaction",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "ijk_ff_vout",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili:ijkservice",
                        }
                    ]
                if "left join thread_track" in sql:
                    return [
                        {
                            "slice_id": 1,
                            "ts": 100,
                            "dur": 5000,
                            "name": "BiliP2_native_display_7_s42_p8000",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "ijk_ff_vout",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili:ijkservice",
                        },
                        {
                            "slice_id": 2,
                            "ts": 101,
                            "dur": 3000,
                            "name": "BiliP2_native_display_call_7",
                            "track_id": 9,
                            "utid": 10,
                            "tid": 10,
                            "thread_name": "ijk_ff_vout",
                            "upid": 20,
                            "pid": 30,
                            "process_name": "tv.danmaku.bili:ijkservice",
                        },
                    ]
                return []

        result = query_native_display_events(FakeTraceProcessor())

        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["event_counts"]["native_display"], 1)
        self.assertEqual(result["event_counts"]["native_display_call"], 1)
        self.assertEqual(result["event_counts"]["binder_display_callback"], 1)
        self.assertEqual(result["events"]["native_display"][0]["serial"], 42)
        self.assertEqual(result["events"]["native_display"][0]["pts_us"], 8000)
        self.assertEqual(result["events"]["native_display_call"][0]["end_ts_ns"], 3101)

    def test_native_display_trace_classifies_section_crossing_expected_vsync(self):
        result = analyze_native_display_trace(
            {
                "status": "VALID",
                "events": {
                    "native_display": [
                        {"ts_ns": 10, "end_ts_ns": 30, "token": 7, "serial": 42}
                    ],
                    "native_display_call": [
                        {"ts_ns": 11, "end_ts_ns": 29, "dur_ns": 18_000_000, "token": 7}
                    ],
                    "binder_display_callback": [
                        {"ts_ns": 12, "dur_ns": 500, "token": 7}
                    ],
                },
                "thread_counts": {"ijk_ff_vout": 1},
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 0,
                        "dispatch_ts_ns": 40,
                        "expected_missing_vsync_trace_timestamps_ns": [20],
                    }
                ]
            },
            {
                "events": {
                    "display_callback": [
                        {"ts_ns": 25, "dur_ns": 100, "token": 7}
                    ]
                }
            },
        )

        self.assertEqual(result["dispatch_gaps_native_display_crossing_expected"], 1)
        self.assertEqual(result["dispatch_gaps_native_call_crossing_expected"], 1)
        self.assertEqual(result["dispatch_gaps_ordered_binder_before_callback_after"], 1)
        self.assertEqual(result["ordered_binder_callback_transport"]["median_ms"], 0.000013)
        self.assertEqual(
            result["gap_classification_counts"]["NATIVE_DISPLAY_CALL_CROSSES_EXPECTED_VSYNC"],
            1,
        )

    def test_display_pair_keeps_start_delay_separate_from_deadline_delta(self):
        expected = [timeline_row(7, 1_000_000_000, 10_000_000)]
        actual = [
            {
                **timeline_row(7, 1_001_000_000, 18_000_000),
                "present_type": "Late Present",
                "jank_type": "Unknown Jank",
            }
        ]

        pairs, summary = display_pairs(expected, actual)

        self.assertEqual(len(pairs), 1)
        self.assertAlmostEqual(pairs[0]["start_delay_ms"], 1.0)
        self.assertAlmostEqual(pairs[0]["display_present_minus_expected_deadline_ms"], 9.0)
        self.assertEqual(summary["paired_count"], 1)

    def test_display_selection_requires_display_scope_before_count(self):
        package_layer = [timeline_row(index, index * 10, 10, "TX - tv.danmaku.bili#1", 500 + index, 3, index) for index in range(10)]
        display_scope = [timeline_row(index, index * 10, 10, None, None, 4, 100 + index) for index in range(2)]

        selected, details = select_display_stream(package_layer + display_scope, "actual")

        self.assertEqual(len(selected), 2)
        self.assertIsNone(details["selected"]["layer_name"])
        self.assertTrue(details["selected"]["is_display_scope"])

    def test_video_selection_prefers_identity_bearing_surface_layer(self):
        ui_layer = "tv.danmaku.bili/Main#1"
        video_layer = "abc SurfaceView[tv.danmaku.bili/Main](BLAST)#2"
        rows = []
        for index in range(3):
            rows.append({"id": index, "ts": index * 8, "dur": 0, "name": "PresentFenceSignaled", "layer_name": ui_layer, "frame_number": index})
        for index in range(2):
            frame_number = 100 + index
            for offset, name in enumerate(("Queue", "AcquireFenceSignaled", "Latch", "PresentFenceSignaled")):
                rows.append({"id": 10 + index * 4 + offset, "ts": frame_number * 8 + offset, "dur": 0, "name": name, "layer_name": video_layer, "frame_number": frame_number})

        selected_layer, selected_rows, details = select_video_layer(rows, "tv.danmaku.bili")

        self.assertEqual(selected_layer, video_layer)
        self.assertEqual(sum(row["name"] == "PresentFenceSignaled" for row in selected_rows), 2)
        self.assertTrue(details["selected"]["is_surface_view"])

    def test_present_intervals_keep_nominal_two_slot_and_large_gap_observations(self):
        period = 8_333_333
        rows = [
            {"id": 1, "ts": 0, "name": "PresentFenceSignaled", "frame_number": 1},
            {"id": 2, "ts": period, "name": "PresentFenceSignaled", "frame_number": 2},
            {"id": 3, "ts": period * 3, "name": "PresentFenceSignaled", "frame_number": 3},
            {"id": 4, "ts": period * 3 + 150_000_000, "name": "PresentFenceSignaled", "frame_number": 4},
        ]

        observations, summary = classify_present_intervals(rows, period, period, 1.5)

        self.assertEqual(summary["interval_gt_nominal_source_period_count"], 2)
        self.assertEqual(summary["interval_gt_100ms_count"], 1)
        self.assertEqual(observations[2]["classification"], "whole_slot_or_cadence_gap")
        self.assertEqual(observations[3]["classification"], "large_gap_over_100ms")
        self.assertEqual(observations[2]["whole_vsync_slots_rounded"], 2)

    def test_stage_join_marks_missing_stage_and_duplicate_identity(self):
        layer = "SurfaceView[tv.danmaku.bili/Main]#3"
        frame_number = 42
        rows = [
            {"id": 1, "ts": 100, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": frame_number},
            {"id": 2, "ts": 110, "dur": 0, "name": "AcquireFenceSignaled", "layer_name": layer, "frame_number": frame_number},
            {"id": 3, "ts": 120, "dur": 0, "name": "Latch", "layer_name": layer, "frame_number": frame_number},
            {"id": 4, "ts": 130, "dur": 0, "name": "PresentFenceSignaled", "layer_name": layer, "frame_number": frame_number},
        ]
        grouped = group_stage_events(rows)
        joined = stage_join_for_present(rows[-1], grouped, {frame_number})

        self.assertIn("duplicate_present_frame_number", joined["invalid_reasons"])
        self.assertNotIn("missing_queue_stage", joined["invalid_reasons"])
        self.assertEqual(joined["stage_latency_ns"]["latch_to_present"], 10)

        incomplete = [row for row in rows if row["name"] != "Latch"]
        incomplete_join = stage_join_for_present(incomplete[-1], group_stage_events(incomplete), set())
        self.assertIn("missing_latch_stage", incomplete_join["invalid_reasons"])

    def test_surface_flinger_gap_classifies_unsignaled_acquire_fence_miss(self):
        timing = [
            {
                "frame_index": 0,
                "frame_number": 1,
                "present_ts_ns": 110,
                "stage_join": {
                    "stage_ts_ns": {"Queue": 80, "AcquireFenceSignaled": 85, "Latch": 105},
                    "stage_latency_ns": {"latch_to_present": 5},
                },
            },
            {
                "frame_index": 1,
                "frame_number": 2,
                "present_ts_ns": 130,
                "stage_join": {
                    "stage_ts_ns": {"Queue": 100, "AcquireFenceSignaled": 111, "Latch": 125},
                    "stage_latency_ns": {"latch_to_present": 5},
                },
            },
        ]
        intervals = [
            {"present_event_index": 0, "is_gap_over_threshold": False, "is_large_gap": False},
            {"present_event_index": 1, "interval_ns": 20, "is_gap_over_threshold": True, "is_large_gap": False},
        ]
        events = {
            "commit_cycles": [
                {"ts_ns": 100, "vsync_id": 10},
                {"ts_ns": 110, "vsync_id": 11},
                {"ts_ns": 120, "vsync_id": 12},
            ],
            "should_latch_unsignaled_events": [{"ts_ns": 110}],
            "unsignaled_fence_events": [{"ts_ns": 110}],
            "set_buffer_events": [{"ts_ns": 121, "frame_number": 2}],
            "latch_buffer_events": [{"ts_ns": 125, "frame_number": 2}],
        }

        result = analyze_surface_flinger_latch_gaps(
            timing,
            intervals,
            events,
            reference_period_ns=10,
            gap_threshold_periods=1.5,
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_ns_exclusive": 1_000},
        )

        self.assertEqual(result["gap_count"], 1)
        self.assertEqual(
            result["classification_counts"],
            {"LATCH_MISS_UNSIGNALED_ACQUIRE_FENCE": 1},
        )
        observation = result["gap_observations"][0]
        self.assertEqual(observation["skipped_commit_count"], 1)
        self.assertEqual(observation["set_buffer_minus_acquire_ns"], 10)

    def test_surface_flinger_gap_classifies_missing_commit_cadence(self):
        timing = [
            {
                "frame_index": 0,
                "frame_number": 1,
                "present_ts_ns": 110,
                "stage_join": {"stage_ts_ns": {"Latch": 105}, "stage_latency_ns": {}},
            },
            {
                "frame_index": 1,
                "frame_number": 2,
                "present_ts_ns": 130,
                "stage_join": {"stage_ts_ns": {"Latch": 125}, "stage_latency_ns": {}},
            },
        ]
        intervals = [
            {"present_event_index": 0, "is_gap_over_threshold": False, "is_large_gap": False},
            {"present_event_index": 1, "interval_ns": 20, "is_gap_over_threshold": True, "is_large_gap": False},
        ]
        events = {
            "commit_cycles": [{"ts_ns": 100, "vsync_id": 10}, {"ts_ns": 120, "vsync_id": 12}],
            "set_buffer_events": [],
            "latch_buffer_events": [],
        }

        result = analyze_surface_flinger_latch_gaps(
            timing,
            intervals,
            events,
            reference_period_ns=10,
            gap_threshold_periods=1.5,
        )

        self.assertEqual(result["classification_counts"], {"SURFACEFLINGER_COMMIT_CADENCE_GAP": 1})

    def test_surface_flinger_buffer_replacement_links_queued_frame_to_two_slot_gap(self):
        layer = "SurfaceView[tv.danmaku.bili/Main]#3"
        stage_rows = [
            {"id": 1, "ts": 80, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": 10},
            {"id": 2, "ts": 85, "dur": 0, "name": "AcquireFenceSignaled", "layer_name": layer, "frame_number": 10},
            {"id": 3, "ts": 90, "dur": 0, "name": "Latch", "layer_name": layer, "frame_number": 10},
            {"id": 4, "ts": 95, "dur": 0, "name": "PresentFenceSignaled", "layer_name": layer, "frame_number": 10},
            {"id": 5, "ts": 100, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": 11},
            {"id": 6, "ts": 110, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": 12},
            {"id": 7, "ts": 115, "dur": 0, "name": "AcquireFenceSignaled", "layer_name": layer, "frame_number": 12},
            {"id": 8, "ts": 120, "dur": 0, "name": "Latch", "layer_name": layer, "frame_number": 12},
            {"id": 9, "ts": 135, "dur": 0, "name": "PresentFenceSignaled", "layer_name": layer, "frame_number": 12},
        ]
        timing = [
            {"frame_index": 0, "frame_number": 10, "present_ts_ns": 95},
            {"frame_index": 1, "frame_number": 12, "present_ts_ns": 135},
        ]
        intervals = [
            {"present_event_index": 0, "is_gap_over_threshold": False, "is_large_gap": False},
            {"present_event_index": 1, "interval_ns": 40, "is_gap_over_threshold": True, "is_large_gap": False},
        ]
        events = {
            "set_buffer_events": [
                {"ts_ns": 80, "frame_number": 10},
                {"ts_ns": 100, "frame_number": 11},
                {"ts_ns": 100_500, "frame_number": 12},
            ],
            "latch_buffer_events": [
                {"ts_ns": 90, "frame_number": 10},
                {"ts_ns": 120, "frame_number": 12},
            ],
        }

        result = analyze_surface_flinger_buffer_replacement(
            timing,
            intervals,
            events,
            group_stage_events(stage_rows),
            reference_period_ns=10,
            gap_threshold_periods=1.5,
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_ns_exclusive": 1_000},
        )

        self.assertEqual(result["queued_non_latched_count"], 1)
        self.assertEqual(result["two_slot_gap_candidate_count"], 1)
        self.assertEqual(result["submission_burst_linked_count"], 1)
        self.assertEqual(
            result["classification_counts"],
            {"QUEUED_BUFFER_NOT_LATCHED_BEFORE_PRESENT_GAP": 1},
        )
        observation = result["observations"][0]
        self.assertEqual(observation["frame_number"], 11)
        self.assertEqual(observation["previous_present_frame_number"], 10)
        self.assertEqual(observation["next_present_frame_number"], 12)
        self.assertEqual(observation["evidence_quality"], "EXACT_WITHIN_SF")

    def test_native_scheduler_phase_separates_cadence_without_scheduler_spike(self):
        layer = "SurfaceView[tv.danmaku.bili/Main]#3"
        stage_rows = []
        for frame_number, queue_ts_ms, presented in [
            (1, 100, True),
            (2, 108, True),
            (3, 120, False),
            (4, 128, True),
            (5, 140, False),
            (6, 148, True),
        ]:
            queue_ts = queue_ts_ms * 1_000_000
            stage_rows.append(
                {
                    "id": frame_number,
                    "ts": queue_ts,
                    "dur": 0,
                    "name": "Queue",
                    "layer_name": layer,
                    "frame_number": frame_number,
                }
            )
            if presented:
                stage_rows.append(
                    {
                        "id": 100 + frame_number,
                        "ts": queue_ts + 5,
                        "dur": 0,
                        "name": "PresentFenceSignaled",
                        "layer_name": layer,
                        "frame_number": frame_number,
                    }
                )
        native = {
            "_queue_rows": [
                {"event_ts_ns": ts * 1_000_000, "timestamp_ns_mono": ts * 1_000_000, "pts_us": index}
                for index, ts in enumerate([99, 107, 119, 127, 139, 147])
            ],
            "_scheduler_rows": [],
        }
        native["_scheduler_rows"] = []
        for ts in [99, 107, 119, 127, 139, 147]:
            native["_scheduler_rows"].extend(
                [
                    {
                        "event": "frame_queue_wait",
                        "event_ts_ns": ts * 1_000_000 - 1,
                        "value_ns": 3_000_000,
                    },
                    {
                        "event": "compute_target",
                        "event_ts_ns": ts * 1_000_000,
                        "value_ns": 9_000_000,
                    },
                ]
            )

        result = analyze_native_scheduler_phase(
            native,
            group_stage_events(stage_rows),
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_ns_exclusive": 200_000_000},
        )

        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["missing_present_count"], 2)
        self.assertEqual(
            result["conclusion"],
            "PRODUCER_CADENCE_PHASE_WITHOUT_SCHEDULER_SEPARATION",
        )
        self.assertEqual(result["missing_minus_present_wait_total_median_ns"], 0)
        self.assertEqual(result["missing_minus_present_compute_max_median_ns"], 0)

    def test_native_vp_duration_phase_reports_duration_separation(self):
        layer = "SurfaceView[tv.danmaku.bili/Main]#3"
        stage_rows = []
        presented_frames = {1, 2, 4, 6}
        queue_times_ms = [100, 108, 120, 128, 140, 148]
        native_times_ms = [99, 107, 119, 127, 139, 147]
        for frame_number, queue_time_ms in enumerate(queue_times_ms, start=1):
            queue_ts = queue_time_ms * 1_000_000
            stage_rows.append(
                {
                    "id": frame_number,
                    "ts": queue_ts,
                    "dur": 0,
                    "name": "Queue",
                    "layer_name": layer,
                    "frame_number": frame_number,
                }
            )
            if frame_number in presented_frames:
                stage_rows.append(
                    {
                        "id": 100 + frame_number,
                        "ts": queue_ts + 5_000_000,
                        "dur": 0,
                        "name": "PresentFenceSignaled",
                        "layer_name": layer,
                        "frame_number": frame_number,
                    }
                )
        native = {
            "_queue_rows": [
                {
                    "event_ts_ns": time_ms * 1_000_000,
                    "timestamp_ns_mono": time_ms * 1_000_000,
                    "pts_us": index,
                }
                for index, time_ms in enumerate(native_times_ms)
            ],
            "_vp_duration_rows": [
                {
                    "event_ts_ns": time_ms * 1_000_000,
                    "value_ns": 8_300_000 if index in {0, 1, 3, 5} else 9_000_000,
                    "auxiliary_ns": 8_333_333,
                }
                for index, time_ms in enumerate(native_times_ms)
            ],
            "_vp_duration_context_rows": [
                {
                    "event_ts_ns": time_ms * 1_000_000,
                    "value_ns": 8_333_333,
                    "auxiliary_ns": 0,
                }
                for time_ms in native_times_ms
            ],
        }

        result = analyze_native_vp_duration_phase(
            native,
            group_stage_events(stage_rows),
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_ns_exclusive": 200_000_000},
        )

        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["missing_present_count"], 2)
        self.assertEqual(result["conclusion"], "VP_DURATION_SEPARATION")
        self.assertEqual(result["missing_present_fallback_count"], 0)

    def test_vp_duration_queue_phase_cross_tab_separates_late_phase(self):
        vp_result = {
            "observations": [
                {
                    "frame_number": 1,
                    "returned_duration_ns": 8_000_000,
                    "presented": True,
                },
                {
                    "frame_number": 2,
                    "returned_duration_ns": 8_000_000,
                    "presented": False,
                },
                {
                    "frame_number": 3,
                    "returned_duration_ns": 9_000_000,
                    "presented": True,
                },
                {
                    "frame_number": 4,
                    "returned_duration_ns": 9_000_000,
                    "presented": False,
                },
            ]
        }
        queue_result = {
            "observations": [
                {
                    "frame_number": 1,
                    "queue_since_previous_commit_ns": 4_000_000,
                    "next_commit_minus_queue_ns": 4_000_000,
                },
                {
                    "frame_number": 2,
                    "queue_since_previous_commit_ns": 8_000_000,
                    "next_commit_minus_queue_ns": 8_000_000,
                },
                {
                    "frame_number": 3,
                    "queue_since_previous_commit_ns": 4_500_000,
                    "next_commit_minus_queue_ns": 3_500_000,
                },
                {
                    "frame_number": 4,
                    "queue_since_previous_commit_ns": 8_100_000,
                    "next_commit_minus_queue_ns": 7_900_000,
                },
            ]
        }

        result = analyze_vp_duration_queue_phase_cross_tab(vp_result, queue_result)

        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["matched_observation_count"], 4)
        self.assertFalse(result["cross_layer_identity_available"])
        groups = {
            row["returned_duration_ns"]: row for row in result["duration_groups"]
        }
        self.assertEqual(groups[8_000_000]["missing_present_count"], 1)
        self.assertEqual(
            groups[8_000_000]["missing_minus_present_queue_phase_median_ns"],
            4_000_000,
        )
        self.assertEqual(
            groups[9_000_000]["missing_minus_present_queue_phase_median_ns"],
            3_600_000,
        )

    def test_surface_flinger_queue_phase_separates_missing_present_frames(self):
        layer = "SurfaceView[tv.danmaku.bili/Main]#3"
        stage_rows = [
            {"id": 1, "ts": 10, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": 10},
            {"id": 2, "ts": 15, "dur": 0, "name": "PresentFenceSignaled", "layer_name": layer, "frame_number": 10},
            {"id": 3, "ts": 37, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": 11},
            {"id": 4, "ts": 39, "dur": 0, "name": "Queue", "layer_name": layer, "frame_number": 12},
            {"id": 5, "ts": 45, "dur": 0, "name": "PresentFenceSignaled", "layer_name": layer, "frame_number": 12},
        ]
        timing = [
            {"frame_index": 0, "frame_number": 10, "present_ts_ns": 15},
            {"frame_index": 1, "frame_number": 12, "present_ts_ns": 45},
        ]
        events = {"commit_cycles": [{"ts_ns": 5}, {"ts_ns": 25}, {"ts_ns": 45}]}
        producer = {
            "events": [
                {"ts_ns": 9, "thread_name": "ijk_ff_vout", "process_name": "tv.danmaku.bili:ijkservice"},
                {"ts_ns": 36, "thread_name": "ijk_ff_vout", "process_name": "tv.danmaku.bili:ijkservice"},
                {"ts_ns": 38, "thread_name": "ijk_ff_vout", "process_name": "tv.danmaku.bili:ijkservice"},
            ],
            "selected_stream": "ijk_ff_vout",
        }

        result = analyze_surface_flinger_queue_phase(
            timing,
            events,
            producer,
            reference_period_ns=10,
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_ns_exclusive": 100},
            stage_groups=group_stage_events(stage_rows),
        )

        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["missing_present_frame_count"], 1)
        self.assertEqual(result["temporal_pair_count"], 3)
        self.assertGreater(
            result["missing_present_queue_phase"]["median_ns"],
            result["present_queue_phase"]["median_ns"],
        )
        self.assertEqual(result["evidence_quality"], "TEMPORAL_ONLY")

    def test_renderer_cadence_correlates_a_missing_do_frame_slot(self):
        timing = [
            {
                "frame_index": 0,
                "frame_number": 1,
                "present_ts_ns": 15,
                "stage_join": {"stage_ts_ns": {"Queue": 10}},
            },
            {
                "frame_index": 1,
                "frame_number": 2,
                "present_ts_ns": 35,
                "stage_join": {"stage_ts_ns": {"Queue": 41}},
            },
        ]
        intervals = [
            {"present_event_index": 0, "is_gap_over_threshold": False, "is_large_gap": False},
            {"present_event_index": 1, "interval_ns": 20, "is_gap_over_threshold": True, "is_large_gap": False},
        ]
        events = {
            "status": "VALID",
            "events": {
                "do_frame": [{"ts_ns": 9}, {"ts_ns": 20}, {"ts_ns": 40}],
                "on_vsync": [{"ts_ns": 9}, {"ts_ns": 20}, {"ts_ns": 40}],
                "egl_swap": [{"ts_ns": 40}],
            },
        }

        result = analyze_renderer_cadence(
            timing,
            intervals,
            events,
            reference_period_ns=10,
            gap_threshold_periods=1.5,
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_exclusive": 100},
        )

        self.assertEqual(result["video_gap_count"], 1)
        self.assertEqual(result["video_gaps_temporally_matched_to_renderer"], 1)
        self.assertEqual(result["video_gaps_with_renderer_do_frame_gap"], 1)

    def test_surface_texture_handoff_classifies_callback_timing_without_expected_vsync(self):
        result = analyze_surface_texture_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "surface_texture_callback": [
                        {"slice_id": 1, "ts_ns": 15, "dur_ns": 2, "end_ts_ns": 17},
                        {"slice_id": 2, "ts_ns": 35, "dur_ns": 1, "end_ts_ns": 36},
                    ]
                },
                "thread_counts": {"ijk_java_player": 2},
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 10,
                        "dispatch_ts_ns": 40,
                        "expected_missing_vsync_trace_timestamps_ns": [],
                    }
                ]
            },
        )

        self.assertEqual(result["surface_texture_callback_count"], 2)
        self.assertEqual(result["dispatch_gaps_with_surface_texture_callback"], 1)
        self.assertEqual(
            result["gap_classification_counts"],
            {"SURFACE_TEXTURE_CALLBACK_NO_EXPECTED_VSYNC": 1},
        )

    def test_surface_texture_handoff_distinguishes_before_and_after_expected_vsync(self):
        result = analyze_surface_texture_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "surface_texture_callback": [
                        {"slice_id": 1, "ts_ns": 15, "dur_ns": 2, "end_ts_ns": 17},
                        {"slice_id": 2, "ts_ns": 26, "dur_ns": 1, "end_ts_ns": 27},
                    ]
                },
                "thread_counts": {"ijk_java_player": 2},
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 10,
                        "dispatch_ts_ns": 40,
                        "expected_missing_vsync_trace_timestamps_ns": [20],
                    }
                ]
            },
        )

        self.assertEqual(result["dispatch_gaps_surface_texture_before_expected"], 1)
        self.assertEqual(result["dispatch_gaps_surface_texture_after_expected"], 1)
        self.assertEqual(
            result["gap_classification_counts"],
            {"SURFACE_TEXTURE_CALLBACK_AFTER_EXPECTED_VSYNC": 1},
        )

    def test_surface_texture_handoff_matches_a_video_gap_by_renderer_do_frame(self):
        result = analyze_surface_texture_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "surface_texture_callback": [
                        {"slice_id": 1, "ts_ns": 15, "dur_ns": 1, "end_ts_ns": 16},
                    ]
                },
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 10,
                        "dispatch_ts_ns": 20,
                        "expected_missing_vsync_trace_timestamps_ns": [18],
                    }
                ]
            },
            [
                {
                    "present_event_index": 7,
                    "frame_number": 107,
                    "queue_ts_ns": 20_500_000,
                    "renderer_do_frame_ts_ns": 20_000_000,
                    "renderer_do_frame_gap": True,
                }
            ],
        )

        self.assertEqual(result["dispatch_gaps_temporally_matched_to_video_gap"], 0)

        result = analyze_surface_texture_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "surface_texture_callback": [
                        {"slice_id": 1, "ts_ns": 15_000_000, "dur_ns": 1, "end_ts_ns": 16_000_000},
                    ]
                },
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 10_000_000,
                        "dispatch_ts_ns": 20_000_000,
                        "expected_missing_vsync_trace_timestamps_ns": [18_000_000],
                    }
                ]
            },
            [
                {
                    "present_event_index": 7,
                    "frame_number": 107,
                    "queue_ts_ns": 20_500_000,
                    "renderer_do_frame_ts_ns": 20_100_000,
                    "renderer_do_frame_gap": True,
                }
            ],
        )

        self.assertEqual(result["dispatch_gaps_temporally_matched_to_video_gap"], 1)
        self.assertEqual(result["video_gap_matches_surface_texture_before_expected"], 1)

    def test_surface_texture_probe_pairs_object_token_ready_and_update(self):
        result = analyze_surface_texture_probe_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "frame_ready": [
                        {"slice_id": 1, "token": 9, "ts_ns": 15, "dur_ns": 0, "end_ts_ns": 15},
                    ],
                    "update": [
                        {"slice_id": 2, "token": 9, "ts_ns": 17, "dur_ns": 4, "end_ts_ns": 21},
                    ],
                    "update_timestamp": [
                        {
                            "slice_id": 3,
                            "token": 9,
                            "ts_ns": 22,
                            "dur_ns": 0,
                            "end_ts_ns": 22,
                            "producer_timestamp_ns": 1000,
                        },
                    ],
                },
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 10,
                        "dispatch_ts_ns": 30,
                        "expected_missing_vsync_trace_timestamps_ns": [20],
                    }
                ]
            },
        )

        self.assertEqual(result["frame_ready_count"], 1)
        self.assertEqual(result["update_count"], 1)
        self.assertEqual(result["update_timestamp_count"], 1)
        self.assertEqual(result["producer_timestamp_distinct_count"], 1)
        self.assertEqual(result["token_ready_update_match_count"], 1)
        self.assertEqual(result["dispatch_gaps_with_frame_ready"], 1)
        self.assertEqual(result["dispatch_gaps_with_update"], 1)
        self.assertEqual(result["dispatch_gaps_update_crossing_expected"], 1)
        self.assertEqual(result["dispatch_gaps_ready_update_pair_before_expected"], 1)
        self.assertEqual(result["dispatch_gaps_ready_update_pair_after_expected"], 0)

    def test_surface_texture_probe_closes_ready_update_queue_chain(self):
        result = analyze_surface_texture_probe_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "frame_ready": [
                        {"slice_id": 1, "token": 9, "ts_ns": 15, "dur_ns": 0, "end_ts_ns": 15},
                    ],
                    "update": [
                        {"slice_id": 2, "token": 9, "ts_ns": 17, "dur_ns": 2, "end_ts_ns": 19},
                    ],
                    "update_timestamp": [],
                },
            },
            {
                "dispatch_gap_observations": [
                    {
                        "previous_dispatch_ts_ns": 10,
                        "dispatch_ts_ns": 30,
                        "expected_missing_vsync_trace_timestamps_ns": [12],
                    }
                ]
            },
            [
                {
                    "present_event_index": 3,
                    "frame_number": 103,
                    "queue_ts_ns": 32,
                    "renderer_do_frame_ts_ns": 30,
                    "renderer_do_frame_gap": True,
                }
            ],
        )

        self.assertEqual(result["dispatch_gaps_temporally_matched_to_video_gap"], 1)
        self.assertEqual(result["video_gap_matches_ready_update_after_expected"], 1)
        self.assertEqual(result["video_gap_ready_update_to_queue"]["median_ms"], 0.000015)
        self.assertEqual(result["video_gap_do_frame_to_ready_update"]["median_ms"], -0.000013)

    def test_surface_texture_probe_consumes_each_update_once(self):
        result = analyze_surface_texture_probe_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "frame_ready": [
                        {"slice_id": 1, "token": 9, "ts_ns": 10},
                        {"slice_id": 2, "token": 9, "ts_ns": 11},
                    ],
                    "update": [
                        {"slice_id": 3, "token": 9, "ts_ns": 12, "end_ts_ns": 13},
                    ],
                    "update_timestamp": [],
                },
            },
            {"dispatch_gap_observations": []},
        )

        pairs = result["ready_update_observations"]
        self.assertEqual(len(pairs), 2)
        self.assertEqual(pairs[0]["update_ts_ns"], 12)
        self.assertIsNone(pairs[1]["update_ts_ns"])
        self.assertEqual(result["token_ready_update_match_count"], 1)
        self.assertEqual(result["token_ready_without_update_count"], 1)

    def test_surface_texture_probe_keeps_producer_intervals_per_token(self):
        result = analyze_surface_texture_probe_handoff(
            {
                "status": "VALID",
                "source": "test",
                "events": {
                    "frame_ready": [],
                    "update": [],
                    "update_timestamp": [
                        {"token": 131, "ts_ns": 10, "producer_timestamp_ns": 1_000_000_000},
                        {"token": 132, "ts_ns": 11, "producer_timestamp_ns": 2_000_000_000},
                        {"token": 132, "ts_ns": 12, "producer_timestamp_ns": 2_008_333_333},
                    ],
                },
            },
            {"dispatch_gap_observations": []},
        )

        self.assertEqual(result["producer_timestamp_token"], 132)
        self.assertEqual(result["producer_timestamp_interval"]["median_ms"], 8.333333)
        self.assertNotEqual(result["producer_timestamp_interval"]["median_ms"], 1_000_000.0)

    def test_renderer_gap_reports_display_callback_inside_missing_slot(self):
        timing = [
            {
                "frame_index": 0,
                "frame_number": 1,
                "present_ts_ns": 15,
                "stage_join": {"stage_ts_ns": {"Queue": 10}},
            },
            {
                "frame_index": 1,
                "frame_number": 2,
                "present_ts_ns": 35,
                "stage_join": {"stage_ts_ns": {"Queue": 41}},
            },
        ]
        intervals = [
            {"present_event_index": 0, "is_gap_over_threshold": False, "is_large_gap": False},
            {"present_event_index": 1, "interval_ns": 20, "is_gap_over_threshold": True, "is_large_gap": False},
        ]
        events = {
            "status": "VALID",
            "events": {
                "do_frame": [{"ts_ns": 9}, {"ts_ns": 20}, {"ts_ns": 40}],
                "on_vsync": [{"ts_ns": 9}, {"ts_ns": 20}, {"ts_ns": 40}],
                "egl_swap": [{"ts_ns": 40}],
            },
            "display_callback_events": {
                "status": "VALID",
                "events": {
                    "display_callback": [
                        {
                            "ts_ns": 25,
                            "end_ts_ns": 26,
                            "token": 101,
                            "thread_name": "main",
                            "process_name": "tv.danmaku.bili",
                        }
                    ]
                },
            },
        }

        result = analyze_renderer_cadence(
            timing,
            intervals,
            events,
            reference_period_ns=10,
            gap_threshold_periods=1.5,
            fixed_window={"status": "VALID", "start_ts_ns": 0, "end_ts_exclusive": 100},
        )

        self.assertEqual(result["renderer_do_frame_gap_count"], 1)
        self.assertEqual(result["renderer_gaps_with_display_callback"], 1)
        self.assertEqual(result["renderer_gaps_without_display_callback"], 0)
        observation = result["renderer_gap_observations"][0]
        self.assertEqual(observation["display_callback_count"], 1)
        self.assertEqual(observation["display_callback_classification"], "CALLBACK_PRESENT_RENDERER_SLOT_MISSING")
        self.assertEqual(observation["latest_display_callback_ts_ns"], 25)
        self.assertEqual(observation["display_callback_to_next_do_frame_ns"], 15)
        self.assertEqual(result["video_gap_observations"][0]["display_callback_count"], 1)

    def test_renderer_vsync_gap_correlates_app_vsync_and_schedule_request(self):
        result = analyze_renderer_cadence(
            [],
            [],
            {
                "status": "VALID",
                "events": {
                    "on_vsync": [{"ts_ns": 10}, {"ts_ns": 30}],
                    "app_on_vsync": [{"ts_ns": 20}],
                    "vsync_schedule": [{"ts_ns": 19}],
                    "do_frame": [],
                    "egl_swap": [],
                },
                "display_callback_events": {"events": {"display_callback": []}},
                "frame_receiver_events": {
                    "events": {
                        "dispatch_vsync": [
                            {"ts_ns": 10, "thread_name": "ijk_ext_render"},
                            {"ts_ns": 29, "thread_name": "ijk_ext_render"},
                        ],
                        "on_vsync": [],
                        "run": [
                            {"ts_ns": 10, "thread_name": "ijk_ext_render"},
                            {"ts_ns": 29, "thread_name": "ijk_ext_render"},
                        ],
                    }
                },
            },
            reference_period_ns=10,
            gap_threshold_periods=1.5,
        )

        self.assertEqual(result["renderer_on_vsync_gap_count"], 1)
        self.assertEqual(result["renderer_on_vsync_gaps_with_app_vsync"], 1)
        self.assertEqual(result["renderer_on_vsync_gaps_with_schedule"], 1)
        self.assertEqual(
            result["renderer_on_vsync_gap_observations"][0]["receiver_dispatch_classification"],
            "DISPATCH_BOUNDARY_ONLY_OR_MISSING",
        )
        self.assertEqual(result["renderer_on_vsync_gap_observations"][0]["receiver_dispatch_vsync_count"], 0)
        self.assertEqual(
            result["renderer_on_vsync_gap_classification_counts"],
            {"RENDERER_VSYNC_GAP_WITH_APP_VSYNC_AND_SCHEDULE": 1},
        )

    def test_set_buffer_frame_number_pattern_does_not_overescape_digits(self):
        self.assertEqual(parse_set_buffer_frame_number("setBuffer frameNumber 1475 - hasBuffer=true"), 1475)
        self.assertIsNone(parse_set_buffer_frame_number("setBuffer without identity - hasBuffer=true"))

    def test_fixed_window_starts_at_first_complete_present_and_keeps_gap_events(self):
        period = 8_333_333
        rows = []
        timestamp = 0
        for index in range(4_000):
            rows.append({
                "id": index,
                "ts": timestamp,
                "name": "PresentFenceSignaled",
                "frame_number": index,
            })
            timestamp += period * (2 if index in {100, 101} else 1)
        timings = [
            {
                "frame_index": index,
                "frame_number": index,
                "present_ts_ns": row["ts"],
                "stage_join": {"invalid_reasons": []},
            }
            for index, row in enumerate(rows)
        ]

        window = fixed_analysis_window(
            rows,
            timings,
            reference_period_ns=period,
            nominal_period_ns=period,
            gap_threshold_periods=1.5,
        )

        self.assertEqual(window["rule_id"], "first_complete_video_present_plus_30s")
        self.assertEqual(window["status"], "VALID")
        self.assertEqual(window["start_ts_ns"], 0)
        self.assertEqual(window["requested_duration_ms"], 30_000)
        self.assertTrue(window["all_events_in_window_retained"])
        self.assertGreaterEqual(window["video_present_summary"]["small_cadence_gap_count"], 2)
        self.assertIsNotNone(window["boundary_after_window_present_ts_ns"])

    def test_media3_telemetry_keeps_pts_and_signed_target_deltas(self):
        header = (
            "sessionId,eventNs,event,codec,bufferIndex,ptsUs,frameIndex,dequeuedAtNs,"
            "playbackPositionUs,positionAgeMs,estimatedPositionUs,earlyUs,"
            "unadjustedReleaseNs,releaseTimeNs,action,render,api,detail\n"
        )
        rows = [
            '"s","100","DEQUEUED","c2.qti.avc.decoder","1","0","0","90","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","DEQUEUED","true","dequeueOutputBuffer",""\n',
            '"s","101","FRAME","c2.qti.avc.decoder","1","0","0","90","-9223372036854775808","0","-9223372036854775808","-9223372036854775808","100","110","SCHEDULED_TIMED_RELEASE","true","releaseOutputBuffer(bool->time)","expectedPresentationNs=115;sampledVsyncNs=120"\n',
            '"s","102","DEQUEUED","c2.qti.avc.decoder","2","8333","1","92","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","DEQUEUED","true","dequeueOutputBuffer",""\n',
            '"s","103","FRAME","c2.qti.avc.decoder","2","8333","1","92","-9223372036854775808","0","-9223372036854775808","-9223372036854775808","102","112","SCHEDULED_TIMED_RELEASE","true","releaseOutputBuffer(bool->time)","expectedPresentationNs=117;sampledVsyncNs=122"\n',
        ]
        for index in range(2, 20):
            event_ns = 104 + index * 2
            pts_us = index * 8333
            dequeued_ns = 90 + index * 2
            release_ns = 110 + index * 2
            expected_ns = 115 + index * 2
            sampled_ns = 120 + index * 2
            rows.append(
                f'"s","{event_ns}","DEQUEUED","c2.qti.avc.decoder","{index}","{pts_us}","{index}","{dequeued_ns}","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","-9223372036854775808","DEQUEUED","true","dequeueOutputBuffer",""\n'
            )
            rows.append(
                f'"s","{event_ns + 1}","FRAME","c2.qti.avc.decoder","{index}","{pts_us}","{index}","{dequeued_ns}","-9223372036854775808","0","-9223372036854775808","-9223372036854775808","{release_ns - 10}","{release_ns}","SCHEDULED_TIMED_RELEASE","true","releaseOutputBuffer(bool->time)","expectedPresentationNs={expected_ns};sampledVsyncNs={sampled_ns}"\n'
            )
        rows.append(
            '"s","350","FRAME","c2.qti.avc.decoder","99","999999","99","340",'
            '"-9223372036854775808","-9223372036854775808","-9223372036854775808",'
            '"-9223372036854775808","-9223372036854775808","360","SCHEDULED_TIMED_RELEASE",'
            '"true","releaseOutputBuffer(bool->time)","expectedPresentationNs=365;sampledVsyncNs=370"\n'
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "media3_frame_timing.csv"
            path.write_text(header + "".join(rows), encoding="utf-8")
            telemetry = load_media3_telemetry(
                path,
                {"start_ts_ns": 100, "end_ts_ns": 300},
                {
                    "status": "AVAILABLE",
                    "offset_boottime_minus_monotonic_ns": 0,
                },
            )

        self.assertEqual(telemetry["status"], "AVAILABLE")
        self.assertEqual(telemetry["source_mode"]["status"], "VERIFIED_PTS_AVERAGE")
        self.assertAlmostEqual(telemetry["source_mode"]["source_fps"], 120.00480019200768, places=6)
        self.assertEqual(telemetry["rows_in_trace_margin_only"], 1)
        self.assertNotIn(99, [
            row["frame_index"] for row in telemetry["_all_rows"]
        ])
        self.assertEqual(telemetry["scheduler"]["scheduled_timed_release_count"], 20)
        self.assertTrue(telemetry["scheduler"]["release_to_expected_presentation"]["signed_values"])

    def test_timing_examples_choose_a_later_event_from_a_chained_cluster(self):
        period = 8_333_333
        rows = []
        timestamp = 0
        for index in range(8):
            interval = period * (2 if index in {2, 3, 4} else 1)
            timestamp += interval
            rows.append({
                "present_event_index": index,
                "interval_ns": interval,
                "interval_ms": interval / 1e6,
                "whole_vsync_slots_rounded": 2 if interval > period else 1,
                "slot_residual_ns": 0,
                "classification": "whole_slot_or_cadence_gap" if interval > period else "at_or_below_nominal_source_period",
                "cadence_anomaly_cluster_id": 7 if index in {2, 3, 4} else None,
                "is_gap_over_threshold": index in {2, 3, 4},
                "is_large_gap": False,
                "previous_frame_number": index,
            })
        timings = [
            {
                "frame_index": index,
                "frame_number": index + 1,
                "present_ts_ns": index * period,
                "stage_join": {"stage_ts_ns": {}, "stage_latency_ns": {}, "invalid_reasons": []},
                "scheduler_identity_link": "UNMATCHED",
                "nearest_display_actual_temporal_only": None,
            }
            for index in range(8)
        ]

        examples = timing_examples(timings, rows)

        self.assertEqual(examples["chained_anomaly"]["status"], "AVAILABLE")
        self.assertEqual(examples["chained_anomaly"]["interval"]["cadence_anomaly_cluster_id"], 7)
        self.assertEqual(examples["chained_anomaly"]["interval"]["cadence_anomaly_cluster_size"], 3)
        self.assertEqual(examples["chained_anomaly"]["frame_number"], 4)

    def test_native_queue_display_telemetry_verifies_pts_and_temporal_handoff(self):
        period = 8_333_333
        trace_start = 1_000_000_000
        calibration = {
            "status": "AVAILABLE",
            "offset_boottime_minus_monotonic_ns": 500_000_000,
        }
        lines = [
            "# nativeTelemetryBuild=0.9.4-native-queue-display-telemetry\n",
            "# columns=timestamp_ns,event,value_ns,auxiliary_ns\n",
            "timestamp_ns,event,value_ns,auxiliary_ns\n",
        ]
        for index in range(24):
            mono_ts = trace_start - 500_000_000 + index * period
            pts_ns = index * period
            lines.append(f"{mono_ts},ijk_queue_picture,{pts_ns},7\n")
            lines.append(f"{mono_ts + 1_000_000},ijk_display_picture,{pts_ns},7\n")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "refresh_samples.csv"
            path.write_text("".join(lines), encoding="utf-8")
            native = load_native_telemetry(
                path,
                {"start_ts_ns": trace_start, "end_ts_ns": trace_start + 25 * period},
                calibration,
            )

        self.assertEqual(native["status"], "AVAILABLE")
        self.assertEqual(native["source_mode"]["status"], "VERIFIED_IJK_QUEUE_PTS_AVERAGE")
        self.assertAlmostEqual(native["source_mode"]["source_fps"], 120.0000048000002, places=5)
        self.assertEqual(native["native_telemetry_build"], "0.9.4-native-queue-display-telemetry")
        self.assertEqual(native["queue_picture_count"], 24)
        self.assertEqual(native["display_picture_count"], 24)

        timing = [
            {"frame_index": index, "present_ts_ns": trace_start + index * period}
            for index in range(20)
        ]
        alignment = attach_native_telemetry(timing, native)

        self.assertEqual(alignment["status"], "TEMPORAL_ONLY")
        self.assertEqual(alignment["exact_identity_count"], 0)
        self.assertEqual(alignment["queue_temporal_match_count"], 20)
        self.assertEqual(alignment["display_temporal_match_count"], 20)
        self.assertEqual(alignment["unmatched_count"], 0)
        self.assertEqual(alignment["queue_display_same_pts_count"], 24)
        self.assertEqual(alignment["native_identity_exact_pts_count"], 24)
        self.assertEqual(alignment["queue_display_pts_mismatch_count"], 0)
        self.assertEqual(alignment["present_minus_queue_picture"]["median_ms"], 0.0)
        self.assertEqual(timing[3]["native_handoff_link"], "TEMPORAL_ONLY")
        self.assertEqual(timing[3]["native_pts_us"], 24_999.0)
        self.assertEqual(timing[3]["native_serial"], 7)
        self.assertEqual(timing[3]["native_handoff_temporal_match"]["present_minus_display_picture_ns"], -1_000_000)

    def test_identity_matching_prefers_exact_pts_over_nearest_timestamp(self):
        queue_rows = [
            {"event_ts_ns": 100, "pts_us": 1_000, "serial": 7, "pts_generation": 0},
            {"event_ts_ns": 200, "pts_us": 2_000, "serial": 7, "pts_generation": 0},
        ]
        display_rows = [
            {"event_ts_ns": 110, "pts_us": 2_000, "serial": 7, "pts_generation": 0},
            {"event_ts_ns": 210, "pts_us": 1_000, "serial": 7, "pts_generation": 0},
        ]

        result = match_rows_by_identity(
            queue_rows,
            display_rows,
            require_serial_match=True,
            require_generation_match=True,
        )

        self.assertEqual(result["exact_pts_count"], 2)
        self.assertEqual(result["temporal_only_fallback_count"], 0)
        pairs = {record["expected"]["pts_us"]: record["actual"]["pts_us"] for record in result["records"]}
        self.assertEqual(pairs, {1_000: 1_000, 2_000: 2_000})

    def test_identity_matching_rejects_different_native_serial(self):
        result = match_rows_by_identity(
            [{"event_ts_ns": 100, "pts_us": 1_000, "serial": 7, "pts_generation": 0}],
            [{"event_ts_ns": 101, "pts_us": 1_000, "serial": 8, "pts_generation": 0}],
            require_serial_match=True,
            require_generation_match=True,
        )

        self.assertEqual(result["exact_pts_count"], 0)
        self.assertEqual(result["temporal_only_fallback_count"], 0)
        self.assertEqual(result["unmatched_count"], 1)

    def test_identity_matching_consumes_repeated_pts_by_occurrence_and_generation(self):
        expected = [
            {"event_ts_ns": 100, "pts_us": 0, "serial": 7, "pts_generation": 0},
            {"event_ts_ns": 200, "pts_us": 1_000, "serial": 7, "pts_generation": 0},
            {"event_ts_ns": 300, "pts_us": 0, "serial": 7, "pts_generation": 1},
        ]
        actual = [
            {"event_ts_ns": 105, "pts_us": 0, "serial": 7, "pts_generation": 0},
            {"event_ts_ns": 205, "pts_us": 1_000, "serial": 7, "pts_generation": 0},
            {"event_ts_ns": 305, "pts_us": 0, "serial": 7, "pts_generation": 1},
        ]

        result = match_rows_by_identity(
            expected,
            actual,
            require_serial_match=True,
            require_generation_match=True,
        )

        self.assertEqual(result["exact_pts_count"], 3)
        self.assertEqual(result["unmatched_count"], 0)
        self.assertEqual(
            [(record["expected"]["pts_generation"], record["actual"]["pts_generation"]) for record in result["records"]],
            [(0, 0), (0, 0), (1, 1)],
        )

    def test_identity_matching_falls_back_temporally_only_without_exact_pts(self):
        result = match_rows_by_identity(
            [{"event_ts_ns": 100, "pts_us": 1_000, "serial": 7, "pts_generation": 0}],
            [{"event_ts_ns": 101, "pts_us": 2_000, "serial": 7, "pts_generation": 0}],
            require_serial_match=True,
            require_generation_match=True,
        )

        self.assertEqual(result["exact_pts_count"], 0)
        self.assertEqual(result["temporal_only_fallback_count"], 1)
        self.assertEqual(result["records"][0]["link"], "TEMPORAL_ONLY_FALLBACK")
        self.assertTrue(result["records"][0]["pts_mismatch"])

    def test_identity_matching_reserves_future_exact_pts_before_fallback(self):
        result = match_rows_by_identity(
            [
                {"event_ts_ns": 100, "pts_us": 1_000},
                {"event_ts_ns": 200, "pts_us": 2_000},
            ],
            [
                {"event_ts_ns": 110, "pts_us": 2_000},
                {"event_ts_ns": 140, "pts_us": 3_000},
            ],
        )

        self.assertEqual(result["exact_pts_count"], 1)
        self.assertEqual(result["temporal_only_fallback_count"], 1)
        self.assertEqual(result["unmatched_count"], 0)
        links = {record["expected"]["pts_us"]: record["link"] for record in result["records"]}
        self.assertEqual(links, {1_000: "TEMPORAL_ONLY_FALLBACK", 2_000: "PTS_EXACT"})

    def test_scheduler_native_alignment_reports_pts_evidence_separately(self):
        result = attach_scheduler_native_telemetry(
            {
                "_scheduled_rows": [
                    {"event_ts_ns": 100, "pts_us": 1_000, "session_id": "s"},
                    {"event_ts_ns": 200, "pts_us": 2_000, "session_id": "s"},
                ]
            },
            {
                "_queue_rows": [
                    {"event_ts_ns": 110, "pts_us": 2_000, "serial": 7},
                    {"event_ts_ns": 140, "pts_us": 3_000, "serial": 7},
                ],
                "_display_rows": [],
            },
        )

        self.assertEqual(result["queue"]["exact_pts_count"], 1)
        self.assertEqual(result["queue"]["temporal_only_fallback_count"], 1)
        self.assertEqual(result["queue"]["fallback_pts_mismatch_count"], 1)
        self.assertEqual(result["display"]["evidence_level"], "NATIVE_DISPLAY_PTS_INFERRED")
        self.assertEqual(result["status"], "PTS_EXACT_WITH_TEMPORAL_FALLBACK")

    def test_scheduler_render_alignment_uses_rendered_nano_time_not_callback_dispatch_time(self):
        header = (
            "sessionId,eventNs,event,codec,bufferIndex,ptsUs,frameIndex,dequeuedAtNs,"
            "playbackPositionUs,positionAgeMs,estimatedPositionUs,earlyUs,"
            "unadjustedReleaseNs,releaseTimeNs,action,render,api,detail\n"
        )
        rows = [
            '"s","100","FRAME","codec","1","1000","0","90",'
            '"-9223372036854775808","-9223372036854775808","-9223372036854775808",'
            '"-9223372036854775808","90","100","SCHEDULED_TIMED_RELEASE",'
            '"true","releaseOutputBuffer(bool->time)","chosenTargetVsyncNs=120"\n',
            '"s","1000","FRAME","codec","1","1000","0","90",'
            '"-9223372036854775808","-9223372036854775808","-9223372036854775808",'
            '"-9223372036854775808","90","100","FRAME_RENDERED",'
            '"true","MediaCodec.OnFrameRenderedListener",'
            '"callbackNanoTimeNs=105;callbackObservedNs=1000;renderMatch=PTS_EXACT;'
            'renderListenerGeneration=2;renderCallbackBurstCount=1;'
            'callbackDispatchDelayNs=895"\n',
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "media3_frame_timing.csv"
            path.write_text(header + "".join(rows), encoding="utf-8")
            telemetry = load_media3_telemetry(
                path,
                {"start_ts_ns": 0, "end_ts_ns": 2_000},
                {"status": "AVAILABLE", "offset_boottime_minus_monotonic_ns": 0},
            )

        result = attach_scheduler_render_telemetry(telemetry)

        self.assertEqual(telemetry["scheduler"]["render_callback_count"], 1)
        self.assertEqual(result["exact_pts_count"], 1)
        self.assertEqual(result["unmatched_count"], 0)
        self.assertEqual(result["render_match_exact_count"], 1)
        self.assertEqual(result["render_callback_burst_count"], 1)
        self.assertEqual(
            result["match_time_key"],
            "codec_frame_identity_then_rendered_nano_ts_ns",
        )

    def test_scheduler_render_alignment_prefers_composite_identity_over_stale_time(self):
        telemetry = {
            "_trace_bounds": {"start_ts_ns": 0, "end_ts_ns": 10_000},
            "_scheduled_rows": [{
                "session_id": "s",
                "codec": "codec",
                "frame_index": 7,
                "dequeued_at_ns": 100,
                "pts_us": 7_000,
                "buffer_index": 2,
                "event_ts_ns": 8_000,
                "release_call_ts_ns": 8_000,
                "release_time_ns": -2_000_000_000,
                "pts_generation": 1,
            }],
            "_rendered_rows": [{
                "session_id": "s",
                "codec": "codec",
                "frame_index": 7,
                "dequeued_at_ns": 100,
                "pts_us": 7_000,
                "buffer_index": 2,
                "event_ts_ns": 9_000,
                "rendered_nano_ts_ns": -2_000_000_000,
                "render_callback_observed_ts_ns": 9_100,
                "pts_generation": 1,
                "render_match": "PTS_EXACT",
            }],
        }

        result = attach_scheduler_render_telemetry(telemetry)

        self.assertEqual(result["status"], "COMPOSITE_IDENTITY_EXACT")
        self.assertEqual(result["exact_identity_count"], 1)
        self.assertEqual(result["matched_count"], 1)
        self.assertEqual(result["unmatched_count"], 0)
        self.assertEqual(result["stale_requested_release_count"], 1)
        self.assertEqual(result["callback_nano_minus_requested_release"]["median_ms"], 0.0)
        self.assertEqual(
            result["callback_observed_minus_release_call"]["median_ms"],
            0.0011,
        )

    def test_scheduler_render_alignment_classifies_boundary_callback(self):
        telemetry = {
            "_trace_bounds": {"start_ts_ns": 0, "end_ts_ns": 10_000},
            "_scheduled_rows": [{
                "session_id": "s",
                "codec": "codec",
                "frame_index": 1,
                "dequeued_at_ns": 100,
                "pts_us": 1_000,
                "buffer_index": 1,
                "event_ts_ns": 2_000,
                "release_call_ts_ns": 2_000,
                "release_time_ns": 1_900,
                "pts_generation": 0,
            }],
            "_rendered_rows": [
                {
                    "session_id": "s",
                    "codec": "codec",
                    "frame_index": 1,
                    "dequeued_at_ns": 100,
                    "pts_us": 1_000,
                    "buffer_index": 1,
                    "event_ts_ns": 2_100,
                    "rendered_nano_ts_ns": 1_900,
                    "render_callback_observed_ts_ns": 2_100,
                    "pts_generation": 0,
                    "render_match": "PTS_EXACT",
                },
                {
                    "session_id": "s",
                    "codec": "codec",
                    "frame_index": 0,
                    "dequeued_at_ns": 90,
                    "pts_us": 0,
                    "buffer_index": 0,
                    "event_ts_ns": 100,
                    "rendered_nano_ts_ns": -100,
                    "render_callback_observed_ts_ns": 100,
                    "pts_generation": 0,
                    "render_match": "PTS_EXACT",
                },
            ],
        }

        result = attach_scheduler_render_telemetry(telemetry)

        self.assertEqual(result["exact_identity_count"], 1)
        self.assertEqual(result["raw_unmatched_callback_count"], 1)
        self.assertEqual(result["trace_boundary_callback_count"], 1)
        self.assertEqual(result["unmatched_callback_count"], 0)

    def test_quantized_eight_nine_ms_pts_uses_endpoint_slope(self):
        from FrameTimelineAnalysis import _pts_cadence_metrics

        pts_us = [0]
        for index in range(1, 61):
            pts_us.append(pts_us[-1] + (8_000 if index % 3 else 9_000))

        metrics = _pts_cadence_metrics(pts_us, unit_ns=1_000)

        self.assertAlmostEqual(metrics["source_fps"], 119.99999999999999, places=6)
        self.assertEqual(metrics["pts_delta_histogram"], {"8000": 40, "9000": 20})
        self.assertEqual(metrics["pts_delta_median_ms"], 8.0)

    def test_pts_reset_uses_longest_monotonic_segment(self):
        from FrameTimelineAnalysis import _pts_cadence_metrics

        first_segment = [index * 8_333 for index in range(61)]
        second_segment = [index * 8_333 for index in range(5)]
        metrics = _pts_cadence_metrics(first_segment + second_segment, unit_ns=1_000)

        self.assertAlmostEqual(metrics["source_fps"], 120.00480019200768, places=6)
        self.assertEqual(metrics["pts_reset_count"], 1)
        self.assertEqual(metrics["monotonic_segment_count"], 2)
        self.assertEqual(metrics["selected_segment_pts_count"], len(first_segment))

    def test_scheduler_match_rejects_release_call_after_present(self):
        timing = [
            {
                "present_ts_ns": 100,
                "frame_index": 0,
                "interval": {},
            }
        ]
        telemetry = {
            "_scheduled_rows": [
                {
                    "release_call_ts_ns": 120,
                    "event_ts_ns": 120,
                    "release_time_ns": 110,
                    "frame_index": 9,
                    "pts_us": 75_000,
                    "dequeued_at_ns": 90,
                    "detail": {"fixedCadence": "active", "cadenceVsyncDurationNs": "10"},
                    "sampled_vsync_id": 1,
                    "sampled_vsync_ts_ns": 80,
                    "vsync_duration_ns": 10,
                    "expected_presentation_ts_ns": 90,
                    "frame_deadline_ts_ns": 85,
                }
            ]
        }

        result = attach_scheduler_telemetry(timing, telemetry)

        self.assertEqual(result["status"], "UNMATCHED")
        self.assertEqual(result["unmatched_count"], 1)
        self.assertEqual(result["expired_release_count"], 0)
        self.assertEqual(timing[0]["scheduler_temporal_order"], "UNKNOWN")

    def test_scheduler_prefers_runtime_chosen_target_over_reconstruction(self):
        timing = [
            {
                "present_ts_ns": 200,
                "frame_index": 0,
                "interval": {},
            }
        ]
        telemetry = {
            "_scheduled_rows": [
                {
                    "release_call_ts_ns": 100,
                    "event_ts_ns": 100,
                    "release_time_ns": 110,
                    "runtime_decision_now_ts_ns": 100,
                    "runtime_scheduled_release_ts_ns": 110,
                    "runtime_target_vsync_ts_ns": 130,
                    "runtime_scheduled_release_minus_decision_ns": 10,
                    "runtime_sampled_vsync_age_ns": 500_000_000,
                    "runtime_cadence_frame_slot": 4,
                    "runtime_cadence_reanchor_reason": "none",
                    "frame_index": 9,
                    "pts_us": 75_000,
                    "dequeued_at_ns": 90,
                    "detail": {"fixedCadence": "active", "cadenceVsyncDurationNs": "10"},
                    "sampled_vsync_id": 1,
                    "sampled_vsync_ts_ns": 80,
                    "vsync_duration_ns": 10,
                    "expected_presentation_ts_ns": 90,
                    "frame_deadline_ts_ns": 85,
                }
            ]
        }

        result = attach_scheduler_telemetry(timing, telemetry)

        self.assertEqual(result["status"], "TEMPORAL_ONLY_ORDERED")
        self.assertEqual(result["runtime_target_vsync_count"], 1)
        self.assertEqual(result["reconstructed_target_vsync_count"], 0)
        self.assertEqual(timing[0]["target_vsync_ts_ns"], 130)
        self.assertEqual(timing[0]["target_vsync_source"], "runtime_chosen_target_vsync_ns")
        self.assertEqual(timing[0]["runtime_cadence_frame_slot"], 4)
        self.assertEqual(timing[0]["runtime_sampled_vsync_age_ns"], 500_000_000)

    def test_native_telemetry_accepts_legacy_comment_only_header(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy-refresh_samples.csv"
            path.write_text(
                "# nativeTelemetryBuild=legacy\n"
                "# columns=timestamp_ns,event,value_ns,auxiliary_ns\n"
                "1000,ijk_queue_picture,0,1\n"
                "2000,ijk_display_picture,0,1\n",
                encoding="utf-8",
            )
            native = load_native_telemetry(
                path,
                {"start_ts_ns": 1000, "end_ts_ns": 3000},
                {"offset_boottime_minus_monotonic_ns": 0},
            )

        self.assertEqual(native["status"], "AVAILABLE")
        self.assertEqual(native["rows_read"], 2)
        self.assertEqual(native["queue_picture_count"], 1)
        self.assertEqual(native["display_picture_count"], 1)


if __name__ == "__main__":
    unittest.main()
