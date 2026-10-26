package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Regression tests for choosing the highest available live quality URL. */
public final class LiveQualitySelectorTest {
    @Test
    public void ignoresQualityDescriptionWithoutAPlayableUrl() {
        LiveQualitySelector.Candidate selected = LiveQualitySelector.chooseHighest(
                Arrays.asList(
                        new LiveQualitySelector.Candidate(400, 400, 0, 311887, 0),
                        new LiveQualitySelector.Candidate(10000, 10000, 0, -1, -1)),
                400);

        assertEquals(400, selected.qn);
        assertEquals(400, selected.qnId);
    }

    @Test
    public void choosesHighestBandwidthWhenQualityIsTied() {
        LiveQualitySelector.Candidate selected = LiveQualitySelector.chooseHighest(
                Arrays.asList(
                        new LiveQualitySelector.Candidate(400, 400, 0, 132965, 1),
                        new LiveQualitySelector.Candidate(400, 400, 0, 311887, 0)),
                10000);

        assertEquals(400, selected.qn);
        assertEquals(311887, selected.urlBandwidth);
        assertEquals(0, selected.codec);
    }

    @Test
    public void prefersHigherQualityWhenBothCandidatesHavePlayableUrls() {
        LiveQualitySelector.Candidate selected = LiveQualitySelector.chooseHighest(
                Arrays.asList(
                        new LiveQualitySelector.Candidate(400, 400, 0, 311887, 0),
                        new LiveQualitySelector.Candidate(10000, 10000, 0, 220000, 0)),
                400);

        assertEquals(10000, selected.qn);
        assertEquals(10000, selected.qnId);
    }

    @Test
    public void ignoresAutoQualityPlaceholder() {
        assertNull(LiveQualitySelector.chooseHighest(
                Collections.singletonList(
                        new LiveQualitySelector.Candidate(0, 0, 0, -1, 0)),
                0));
    }
}
