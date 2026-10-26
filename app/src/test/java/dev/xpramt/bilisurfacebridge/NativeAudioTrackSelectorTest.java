package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Regression tests for live/container audio-track selection by actual bitrate. */
public final class NativeAudioTrackSelectorTest {
    @Test
    public void selects320KWhen128KIsListedFirst() {
        assertEquals(7, NativeAudioTrackSelector.chooseHighestBitrateIndex(
                Arrays.asList(
                        new NativeAudioTrackSelector.Track(3, 128000L),
                        new NativeAudioTrackSelector.Track(7, 320000L)),
                new int[]{3, 7},
                3));
    }

    @Test
    public void selects320KWhenItIsListedBefore128K() {
        assertEquals(3, NativeAudioTrackSelector.chooseHighestBitrateIndex(
                Arrays.asList(
                        new NativeAudioTrackSelector.Track(3, 320000L),
                        new NativeAudioTrackSelector.Track(7, 128000L)),
                new int[]{3, 7},
                7));
    }

    @Test
    public void keepsCurrentTrackWhenHighestBitrateIsTied() {
        assertEquals(7, NativeAudioTrackSelector.chooseHighestBitrateIndex(
                Arrays.asList(
                        new NativeAudioTrackSelector.Track(3, 320000L),
                        new NativeAudioTrackSelector.Track(7, 320000L)),
                new int[]{3, 7},
                7));
    }

    @Test
    public void doesNotSelectWhenThereAreNoUsableTracks() {
        assertEquals(-1, NativeAudioTrackSelector.chooseHighestBitrateIndex(
                Collections.<NativeAudioTrackSelector.Track>emptyList(),
                new int[0],
                -1));
    }
}
