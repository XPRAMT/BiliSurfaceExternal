package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AudioSampleRateFormatterTest {
    @Test
    public void formatsCommonAudioSampleRates() {
        assertEquals("44.1KHz", AudioSampleRateFormatter.format(44100));
        assertEquals("48KHz", AudioSampleRateFormatter.format(48000));
        assertEquals("96KHz", AudioSampleRateFormatter.format(96000));
    }

    @Test
    public void formatsSubKilohertzAndRejectsUnknownValues() {
        assertEquals("800Hz", AudioSampleRateFormatter.format(800));
        assertEquals("", AudioSampleRateFormatter.format(0));
        assertEquals("", AudioSampleRateFormatter.format(-1));
    }

    @Test
    public void appendsToExistingQualityLabelAfterDecoderMetadataArrives() {
        assertEquals("AAC 128K 44.1KHz", AudioSampleRateFormatter.appendTo("AAC 128K", 44100));
        assertEquals("Hi-Res 96KHz", AudioSampleRateFormatter.appendTo("Hi-Res", 96000));
        assertEquals("AAC 128K", AudioSampleRateFormatter.appendTo("AAC 128K", -1));
    }
}
