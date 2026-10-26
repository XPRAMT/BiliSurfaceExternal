package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the audio overlay when the official NORMAL stream omits its codec enum. */
public final class AudioCodecLabelTest {
    @Test
    public void mapsNormalAudioWithMissingCodecToAac() {
        assertEquals("AAC", StreamCodecLabel.audioForEnumName("NORMAL", ""));
    }

    @Test
    public void keepsKnownAudioQualityLabels() {
        assertEquals("Dolby", StreamCodecLabel.audioForEnumName("DOLBY", ""));
        assertEquals("Hi-Res", StreamCodecLabel.audioForEnumName("HIRES", ""));
        assertEquals("Hi-Res", StreamCodecLabel.audioForEnumName("HI_RES", ""));
        assertEquals("AAC", StreamCodecLabel.audioForEnumName("NORMAL", "MP4A_LATM"));
    }

    @Test
    public void keepsUnknownNonEmptyCodecVisible() {
        assertEquals("OPUS", StreamCodecLabel.audioForEnumName("NORMAL", "opus"));
    }

    @Test
    public void mapsActualAudioDecoderMimeToOverlayLabel() {
        assertEquals("AAC", StreamCodecLabel.audioForDecoderMime("audio/mp4a-latm"));
        assertEquals("OPUS", StreamCodecLabel.audioForDecoderMime("audio/opus"));
    }

    @Test
    public void mapsActualIjkAudioDecoderToOverlayLabel() {
        assertEquals("AAC", StreamCodecLabel.audioForDecoderName("aac", "ffmpeg"));
        assertEquals("Dolby", StreamCodecLabel.audioForDecoderName("eac3", "ffmpeg"));
    }

    @Test
    public void matchesHiResQualityToItsFlacDecoderWithoutAcceptingOldAac() {
        assertTrue(StreamCodecLabel.audioDecoderMatchesSelection("Hi-Res", "FLAC"));
        assertFalse(StreamCodecLabel.audioDecoderMatchesSelection("Hi-Res", "AAC"));
        assertTrue(StreamCodecLabel.audioDecoderMatchesSelection("AAC", "AAC"));
        assertFalse(StreamCodecLabel.audioDecoderMatchesSelection("AAC", "FLAC"));
    }
}
