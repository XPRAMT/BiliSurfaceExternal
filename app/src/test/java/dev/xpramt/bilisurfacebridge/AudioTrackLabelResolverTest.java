package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** Regression test for an audio label disappearing after the player rebuilds a video asset. */
public final class AudioTrackLabelResolverTest {
    @Test
    public void fallsBackToKnownLabelWhenRebuiltAssetOmitsTheTrack() {
        Map<Integer, String> current = Collections.emptyMap();
        Map<Integer, String> known = new HashMap<Integer, String>();
        known.put(30251, "Hi-Res 2K");

        assertEquals("Hi-Res 2K", AudioTrackLabelResolver.resolve(30251, current, known));
    }
}
