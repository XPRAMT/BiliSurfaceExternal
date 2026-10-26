package dev.xpramt.bilisurfacebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the same HLS auxiliary byte counters used by blbl-up. */
final class HlsPlaylistBitrateEstimator {
    private static final int RECENT_SEGMENT_COUNT = 3;

    private HlsPlaylistBitrateEstimator() {
    }

    static Estimate estimate(String text) {
        if (text == null || !text.trim().startsWith("#EXTM3U")) {
            return Estimate.empty();
        }
        boolean master = false;
        String mapUri = "";
        Double pendingDurationSec = null;
        String pendingAux = null;
        List<Segment> segments = new ArrayList<Segment>();
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.regionMatches(true, 0, "#EXT-X-STREAM-INF:", 0,
                    "#EXT-X-STREAM-INF:".length())) {
                master = true;
            }
            if (line.regionMatches(true, 0, "#EXT-X-MAP:", 0,
                    "#EXT-X-MAP:".length())) {
                int uriStart = line.indexOf("URI=\"");
                if (uriStart >= 0) {
                    uriStart += 5;
                    int uriEnd = line.indexOf('"', uriStart);
                    if (uriEnd > uriStart) {
                        mapUri = line.substring(uriStart, uriEnd).trim();
                    }
                }
            }
            if (line.regionMatches(true, 0, "#EXT-BILI-AUX:", 0,
                    "#EXT-BILI-AUX:".length())) {
                pendingAux = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.regionMatches(true, 0, "#EXTINF:", 0,
                    "#EXTINF:".length())) {
                String value = line.substring(line.indexOf(':') + 1);
                value = value.substring(0, value.indexOf(',') >= 0 ? value.indexOf(',') : value.length());
                try {
                    pendingDurationSec = Double.valueOf(value.trim());
                } catch (NumberFormatException ignored) {
                    pendingDurationSec = null;
                }
            } else if (!line.startsWith("#") && pendingDurationSec != null
                    && pendingDurationSec.doubleValue() > 0.0d) {
                segments.add(new Segment(pendingDurationSec.doubleValue(),
                        parseAuxBytes(pendingAux), line));
                pendingDurationSec = null;
                pendingAux = null;
            }
        }

        int start = Math.max(0, segments.size() - RECENT_SEGMENT_COUNT);
        long bytes = 0L;
        double durationSec = 0.0d;
        int segmentsWithBytes = 0;
        for (int index = start; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment.bytes >= 0L && segment.durationSec > 0.0d) {
                bytes += segment.bytes;
                durationSec += segment.durationSec;
                segmentsWithBytes++;
            }
        }
        long bitrate = bytes > 0L && durationSec > 0.0d
                ? Math.round(bytes * 8.0d / durationSec) : -1L;
        List<Segment> recentSegments = segments.subList(start, segments.size());
        return new Estimate(master, mapUri, segments.size(), segmentsWithBytes, bytes, durationSec,
                bitrate, Collections.unmodifiableList(new ArrayList<Segment>(recentSegments)));
    }

    private static long parseAuxBytes(String raw) {
        if (raw == null) {
            return -1L;
        }
        String[] parts = raw.split("\\|");
        if (parts.length < 3) {
            return -1L;
        }
        String value = parts[2].trim();
        if (value.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            try {
                return Long.parseLong(value, 16);
            } catch (NumberFormatException ignoredHex) {
                return -1L;
            }
        }
    }

    static final class Segment {
        final double durationSec;
        final long bytes;
        final String uri;

        Segment(double durationSec, long bytes, String uri) {
            this.durationSec = durationSec;
            this.bytes = bytes;
            this.uri = uri;
        }
    }

    static final class Estimate {
        final boolean masterPlaylist;
        final String mapUri;
        final int segmentCount;
        final int segmentsWithBytes;
        final long recentBytes;
        final double recentDurationSec;
        final long bitrateBitsPerSecond;
        final List<Segment> recentSegments;

        private Estimate(boolean masterPlaylist, String mapUri, int segmentCount, int segmentsWithBytes,
                         long recentBytes, double recentDurationSec,
                         long bitrateBitsPerSecond, List<Segment> recentSegments) {
            this.masterPlaylist = masterPlaylist;
            this.mapUri = mapUri;
            this.segmentCount = segmentCount;
            this.segmentsWithBytes = segmentsWithBytes;
            this.recentBytes = recentBytes;
            this.recentDurationSec = recentDurationSec;
            this.bitrateBitsPerSecond = bitrateBitsPerSecond;
            this.recentSegments = recentSegments;
        }

        static Estimate empty() {
            return new Estimate(false, "", 0, 0, 0L, 0.0d, -1L,
                    Collections.<Segment>emptyList());
        }

        boolean hasUsableBitrate() {
            return bitrateBitsPerSecond > 0L;
        }
    }
}
