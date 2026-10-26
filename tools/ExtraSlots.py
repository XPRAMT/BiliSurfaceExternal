"""Extra slots per 30 s on the Bilibili video layer (the metric of README section 1.2).

Every interval between two SurfaceFlinger latches of the video SurfaceView is rounded to
whole VSync slots; a frame that stays n slots longer than it should counts n.  A stall
longer than 100 ms (the test clip's loop point) and the 3 s after it are excluded, and the
result is scaled to 30 s of counted playback.

Usage:
    python tools/ExtraSlots.py TRACE.pftrace SLOTS_PER_FRAME [--refresh-hz 120.285]
        [--trace-processor PATH]

SLOTS_PER_FRAME: 1 for 120 fps at 1x (and 120 fps at 2x), 2 for 60 fps at 1x and
120 fps at 0.5x, 4 for 60 fps at 0.5x.
"""
import argparse
import collections

from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('trace')
    parser.add_argument('slots_per_frame', type=int)
    parser.add_argument('--refresh-hz', type=float, default=120.285)
    parser.add_argument('--window-s', type=float, default=30.0)
    parser.add_argument('--trace-processor', help='trace_processor_shell binary (default: downloaded by perfetto)')
    args = parser.parse_args()

    config = TraceProcessorConfig(bin_path=args.trace_processor) if args.trace_processor else None
    tp = TraceProcessor(trace=args.trace, config=config) if config else TraceProcessor(trace=args.trace)
    period = 1e9 / args.refresh_hz
    latches = [row.ts for row in tp.query(
        "select ts from slice where name like 'latchBuffer %SurfaceView%' order by ts")]
    tp.close()
    if len(latches) < 2:
        raise SystemExit('no SurfaceView latches in this trace')

    start = latches[0] + 1e9  # skip the first second of the trace
    end = start + args.window_s * 1e9
    intervals = [(a, b) for a, b in zip(latches, latches[1:]) if start <= b < end]
    excluded_until = 0
    extra = loops = excluded = 0
    histogram = collections.Counter()
    for a, b in intervals:
        if b - a > 100e6:
            loops += 1
            excluded_until = b + 3e9
            continue
        if b < excluded_until:
            excluded += 1
            continue
        slots = round((b - a) / period)
        histogram[slots] += 1
        if slots > args.slots_per_frame:
            extra += slots - args.slots_per_frame

    span = (min(end, latches[-1]) - start) / 1e9
    counted = span - loops * 3
    per30 = extra * 30 / counted if counted > 0 else 0.0
    print(f'span {span:.1f} s, loop stalls {loops}, excluded intervals {excluded}, '
          f'extra slots {extra}, per 30 s {per30:.1f}, presents/s {len(intervals) / span:.1f}')
    print('slots per interval:', dict(sorted(histogram.items())))


if __name__ == '__main__':
    main()
