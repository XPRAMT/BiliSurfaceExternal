package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Guards the enum-to-overlay mapping against new codecs in IjkMediaAsset.VideoCodecType. */
public final class VideoCodecLabelTest {
    @Test
    public void mapsEverySupportedCodecNameToItsExpectedOverlayLabel() {
        assertEquals("AVC", StreamCodecLabel.videoForEnumName("H264"));
        assertEquals("HEVC", StreamCodecLabel.videoForEnumName("H265"));
        assertEquals("AV1", StreamCodecLabel.videoForEnumName("AV1"));
        assertEquals("VVC", StreamCodecLabel.videoForEnumName("H266"));
    }

    @Test
    public void leavesAnUnrecognisedFutureCodecExplicitlyUnknown() {
        assertEquals("UNKNOWN", StreamCodecLabel.videoForEnumName("FUTURE_CODEC"));
    }

    @Test
    public void mapsActualDecoderMimesToTheirExpectedOverlayLabels() {
        assertEquals("AVC", StreamCodecLabel.videoForDecoderMime("video/avc"));
        assertEquals("HEVC", StreamCodecLabel.videoForDecoderMime("video/hevc"));
        assertEquals("AV1", StreamCodecLabel.videoForDecoderMime("video/av01"));
        assertEquals("VVC", StreamCodecLabel.videoForDecoderMime("video/vvc"));
        assertEquals("UNKNOWN", StreamCodecLabel.videoForDecoderMime("video/x-vnd.on2.vp9"));
    }
}
