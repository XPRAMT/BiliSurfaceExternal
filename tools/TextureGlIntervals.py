"""GL-side cadence check for the Texture renderer.

With `TEXTURE_EXTERNAL` the module redraws the TextureView on every UI VSync, so the window
layer presents a frame in every slot and the present-based gap count of
FrameTimelineAnalysis.py no longer shows repeated video frames.  This script counts, for every
UI VSync interval (boundary = the UI thread's Choreographer#doFrame start + 0.9 ms, i.e. the
RenderThread's updateTexImage), how many frames the `ijk_ext_render` GL thread queued:

  0 -> the slot repeated the previous video frame (beat floor is ~9 per 30 s at 4K120)
  2 -> two video frames landed in one slot, one of them never shown

Usage:
  python tools/TextureGlIntervals.py <trace.pftrace> <analysis.json>

The analysis JSON supplies the fixed 30 s window; `perfetto` must be importable and the
bundled trace_processor_shell.exe is used.
"""
import bisect
import collections
import json
import os
import sys

from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHELL = os.path.join(ROOT, "artifacts", "tools", "trace_processor_shell.exe")
UPDATE_TEX_IMAGE_OFFSET_NS = 900_000


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    trace, analysis = sys.argv[1], sys.argv[2]
    tp = TraceProcessor(file_path=trace, config=TraceProcessorConfig(bin_path=SHELL))
    report = json.load(open(analysis, encoding="utf-8-sig"))
    window = report["fixed_analysis_window"]
    start, end = window["start_ts_ns"], window["end_ts_ns_exclusive"]

    def events(thread: str, condition: str) -> list[tuple[int, int]]:
        query = (
            "select s.ts, s.dur from slice s join thread_track tt on s.track_id=tt.id "
            "join thread t using(utid) join process p using(upid) "
            f"where p.name='tv.danmaku.bili' and t.name='{thread}' and {condition} "
            f"and s.ts between {start} and {end} order by s.ts"
        )
        return [(row.ts, row.dur) for row in tp.query(query)]

    # queueBuffer is traced twice (nested) per GL frame.
    gl = [ts for ts, _ in events("ijk_ext_render", "s.name='queueBuffer'")][::2]
    do_frames = events("tv.danmaku.bili", "s.name like 'Choreographer#doFrame%'")
    traversals = [ts for ts, _ in events("tv.danmaku.bili", "s.name='traversal'")]
    no_traversal = sum(
        1 for ts, dur in do_frames if not any(ts <= x <= ts + dur for x in traversals)
    )
    bounds = [ts + UPDATE_TEX_IMAGE_OFFSET_NS for ts, _ in do_frames]
    per_interval = collections.Counter()
    empty = []
    for index in range(len(bounds) - 1):
        count = bisect.bisect_left(gl, bounds[index + 1]) - bisect.bisect_left(gl, bounds[index])
        per_interval[count] += 1
        if count == 0:
            empty.append(round((bounds[index] - start) / 1e9, 2))
    print(f"GL frames queued: {len(gl)}  UI doFrames: {len(do_frames)}  "
          f"doFrames without traversal: {no_traversal}")
    print("GL frames per UI VSync interval:", sorted(per_interval.items()))
    print("empty intervals (s from window start):", empty)
    return 0


if __name__ == "__main__":
    sys.exit(main())
