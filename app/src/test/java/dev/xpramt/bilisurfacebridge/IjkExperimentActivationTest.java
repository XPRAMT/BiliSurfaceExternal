package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IjkExperimentActivationTest {
    @Test
    public void frameDropBlockDoesNotRequireDebugOverlay() {
        assertTrue(IjkExperimentActivation.shouldStartNativeHooks(false, true, false, false));
    }

    @Test
    public void computeTargetBlockDoesNotRequireDebugOverlay() {
        assertTrue(IjkExperimentActivation.shouldStartNativeHooks(false, false, true, false));
    }

    @Test
    public void waitRoundingDoesNotRequireDebugOverlay() {
        assertTrue(IjkExperimentActivation.shouldStartNativeHooks(false, false, false, true));
    }

    @Test
    public void overlayAloneMayCollectTelemetry() {
        assertTrue(IjkExperimentActivation.shouldStartNativeHooks(true, false, false, false));
    }

    @Test
    public void frameSyncAloneStartsNativeHooks() {
        assertTrue(IjkExperimentActivation.shouldStartNativeHooks(false, false, false, false,
                true));
        assertFalse(IjkExperimentActivation.shouldStartNativeHooks(false, false, false, false,
                false));
    }

    @Test
    public void allDisabledDoesNotInstallProbe() {
        assertFalse(IjkExperimentActivation.shouldStartNativeHooks(false, false, false, false));
    }
}
