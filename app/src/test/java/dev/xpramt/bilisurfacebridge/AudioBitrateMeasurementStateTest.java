package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for AAC/Hi-Res measurement races during in-place track switches. */
public final class AudioBitrateMeasurementStateTest {
    @Test
    public void rejectsLateResultFromPreviousTrackAndAllowsSwitchBack() {
        AudioBitrateMeasurementState state = new AudioBitrateMeasurementState();

        AudioBitrateMeasurementState.Started aac = state.begin("aac", false);
        AudioBitrateMeasurementState.Started hiRes = state.begin("hi-res", true);

        assertTrue(aac.started);
        assertTrue(hiRes.started);
        assertFalse(state.accepts("aac", aac.generation));
        assertTrue(state.accepts("hi-res", hiRes.generation));

        state.markMeasured("hi-res", hiRes.generation);
        AudioBitrateMeasurementState.Started aacAgain = state.begin("aac", true);

        assertNotEquals(aac.generation, aacAgain.generation);
        assertFalse(state.accepts("aac", aac.generation));
        assertTrue(state.accepts("aac", aacAgain.generation));
    }

    @Test
    public void failedCurrentMeasurementCanBeFinishedWithoutStayingPending() {
        AudioBitrateMeasurementState state = new AudioBitrateMeasurementState();
        AudioBitrateMeasurementState.Started hiRes = state.begin("hi-res", true);

        assertTrue(state.isPending());
        assertTrue(state.finish("hi-res", hiRes.generation));
        assertFalse(state.isPending());
        assertFalse(state.accepts("hi-res", hiRes.generation));
    }
}
