"""Render a standalone HTML chart from a v2 Bilibili frame analysis report."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


HTML_TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Bilibili FrameTimeline cadence</title>
<style>
body { background:#101318; color:#e7edf5; font:14px system-ui,sans-serif; margin:24px; }
h1 { font-size:20px; margin:0 0 8px; }
#summary { color:#b7c1cf; line-height:1.5; margin-bottom:18px; }
.chart { background:#171c24; border:1px solid #303a49; border-radius:8px; margin:16px 0; padding:12px; }
canvas { display:block; width:100%; height:300px; }
.legend { color:#b7c1cf; font-size:12px; margin-top:8px; }
</style>
</head>
<body>
<h1>Bilibili video PresentFence cadence</h1>
<div id="summary"></div>
<div class="chart"><canvas id="intervals" width="1400" height="320"></canvas><div class="legend">Every video PresentFence interval is retained. Guides show nominal 120 fps and the inferred DisplayFrame prediction cadence; it is not a measured panel VSync.</div></div>
<div class="chart"><canvas id="deadline" width="1400" height="320"></canvas><div class="legend">DisplayFrame actual present minus expected deadline. This is a deadline diagnostic, not panel scanline time or video buffer lateness.</div></div>
<div class="chart"><canvas id="join" width="1400" height="320"></canvas><div class="legend">Absolute time difference between video PresentFenceSignaled and nearest DisplayFrame actual present. This is temporal correlation only.</div></div>
<script>
const report = __REPORT_JSON__;
const intervals = (report.video_interval_observations || []).map(function (row) { return Number(row.interval_ms); }).filter(Number.isFinite);
const deadlines = (report.display_frame_timing || []).map(function (row) { return Number(row.display_present_minus_expected_deadline_ms); }).filter(Number.isFinite);
const joins = (report.video_present_timing || []).map(function (row) {
  const item = row.nearest_display_actual_temporal_only || {};
  return Math.abs(Number(item.display_actual_delta_ns)) / 1000000;
}).filter(Number.isFinite);
const referenceMs = Number(report.measurement.display_expected_timeline_period_ms || 0);
const nominalMs = Number(report.measurement.nominal_source_period_ms || 8.333333);
const deadlineThresholdMs = Number(report.display_frame_timing_summary.display_present_minus_expected_deadline_threshold_ms || 2.42);

function drawChart(id, values, guides, label) {
  const canvas = document.getElementById(id);
  const context = canvas.getContext('2d');
  const width = canvas.width;
  const height = canvas.height;
  const left = 68;
  const right = 20;
  const top = 18;
  const bottom = 36;
  const plotWidth = width - left - right;
  const plotHeight = height - top - bottom;
  context.clearRect(0, 0, width, height);
  context.fillStyle = '#171c24';
  context.fillRect(0, 0, width, height);
  if (!values.length) {
    context.fillStyle = '#ff9d9d';
    context.fillText('No valid samples', left, top + 20);
    return;
  }
  const guideValues = guides.map(function (guide) { return guide.value; }).filter(Number.isFinite);
  const maximum = Math.max(1, ...values, ...guideValues) * 1.08;
  function x(index) { return left + (values.length === 1 ? plotWidth / 2 : index * plotWidth / (values.length - 1)); }
  function y(value) { return top + plotHeight - Number(value) / maximum * plotHeight; }
  context.font = '12px system-ui,sans-serif';
  context.strokeStyle = '#303a49';
  context.fillStyle = '#9aa7b8';
  for (let grid = 0; grid <= 4; grid++) {
    const value = maximum * grid / 4;
    const py = y(value);
    context.beginPath();
    context.moveTo(left, py);
    context.lineTo(width - right, py);
    context.stroke();
    context.fillText(value.toFixed(2) + ' ms', 4, py + 4);
  }
  context.save();
  context.translate(16, top + plotHeight / 2);
  context.rotate(-Math.PI / 2);
  context.fillText(label, 0, 0);
  context.restore();
  guides.forEach(function (guide) {
    if (!Number.isFinite(guide.value)) return;
    const py = y(guide.value);
    context.strokeStyle = guide.color;
    context.setLineDash([6, 4]);
    context.beginPath();
    context.moveTo(left, py);
    context.lineTo(width - right, py);
    context.stroke();
    context.setLineDash([]);
    context.fillStyle = guide.color;
    context.fillText(guide.label + ': ' + guide.value.toFixed(3) + ' ms', left + 8, Math.max(top + 12, py - 4));
  });
  context.strokeStyle = '#7ea6d8';
  context.beginPath();
  values.forEach(function (value, index) {
    if (index === 0) context.moveTo(x(index), y(value)); else context.lineTo(x(index), y(value));
  });
  context.stroke();
  values.forEach(function (value, index) {
    context.fillStyle = value > 12.474 ? '#ff9d5c' : '#8fc7ff';
    context.fillRect(x(index) - 1, y(value) - 1, 3, 3);
  });
  context.fillStyle = '#9aa7b8';
  context.fillText('captured event index', left + plotWidth / 2 - 42, height - 8);
}

drawChart('intervals', intervals, [
  {label: 'nominal 120 fps', value: nominalMs, color: '#d6b15d'},
  {label: 'inferred DisplayFrame prediction', value: referenceMs, color: '#65d39a'}
], 'interval (ms)');
drawChart('deadline', deadlines, [
  {label: '2.42 ms diagnostic', value: deadlineThresholdMs, color: '#ff9d5c'},
  {label: 'zero', value: 0, color: '#65d39a'}
], 'deadline delta (ms)');
drawChart('join', joins, [
  {label: 'zero', value: 0, color: '#65d39a'}
], 'absolute delta (ms)');

const summary = report.video_present_summary || {};
document.getElementById('summary').textContent =
  'Trace: ' + String(report.trace_path) + '\n' +
  'Video PresentFence events: ' + String(summary.present_count || 0) +
  '; median cadence: ' + Number(summary.median_cadence_fps || 0).toFixed(3) + ' fps' +
  '; intervals > 8.333 ms: ' + String(summary.interval_gt_nominal_source_period_count || 0) +
  '; whole-slot candidates: ' + String(summary.small_cadence_gap_count || 0) +
  '; gaps > 100 ms: ' + String(summary.interval_gt_100ms_count || 0);
</script>
</body>
</html>
"""

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_path")
    parser.add_argument("output_path")
    args = parser.parse_args()
    report = json.loads(Path(args.report_path).read_text(encoding="utf-8"))
    payload = json.dumps(report, ensure_ascii=False, separators=(",", ":"))
    Path(args.output_path).write_text(HTML_TEMPLATE.replace("__REPORT_JSON__", payload), encoding="utf-8")
    print(f"Chart: {args.output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
