package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HlsPlaylistBitrateEstimatorTest {
    @Test
    public void estimatesRecentAuxBytesOverSegmentDuration() {
        String playlist = "#EXTM3U\n"
                + "#EXTINF:2.0,\n#EXT-BILI-AUX:a|b|32768\nseg-1.ts\n"
                + "#EXTINF:2.0,\n#EXT-BILI-AUX:a|b|32768\nseg-2.ts\n"
                + "#EXTINF:2.0,\n#EXT-BILI-AUX:a|b|32768\nseg-3.ts\n";

        HlsPlaylistBitrateEstimator.Estimate estimate =
                HlsPlaylistBitrateEstimator.estimate(playlist);

        assertFalse(estimate.masterPlaylist);
        assertEquals(3, estimate.segmentCount);
        assertEquals(3, estimate.segmentsWithBytes);
        assertEquals(98304L, estimate.recentBytes);
        assertEquals(131072L, estimate.bitrateBitsPerSecond);
        assertTrue(estimate.hasUsableBitrate());
    }

    @Test
    public void recognizesMasterPlaylistWithoutInventingRate() {
        HlsPlaylistBitrateEstimator.Estimate estimate =
                HlsPlaylistBitrateEstimator.estimate("#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1000000\nvariant.m3u8\n");

        assertTrue(estimate.masterPlaylist);
        assertEquals(-1L, estimate.bitrateBitsPerSecond);
        assertFalse(estimate.hasUsableBitrate());
    }
}
