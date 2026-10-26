package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AudioBitrateFormatterTest {
    @Test
    public void preservesDecimalStandardRate() {
        assertEquals("128K", AudioBitrateFormatter.format(128000));
    }

    @Test
    public void formatsBinary256KProbeAs256K() {
        assertEquals("256K", AudioBitrateFormatter.format(262144));
    }

    @Test
    public void doesNotRoundLowRateTracksUpTo32K() {
        assertEquals("5K", AudioBitrateFormatter.format(5273));
        assertEquals("1K", AudioBitrateFormatter.format(1400));
    }
}
