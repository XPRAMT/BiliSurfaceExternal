package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression coverage for semantic player-control visibility events. */
public final class PlaybackOverlayVisibilityStateTest {
    @Test
    public void unknownControlStateDoesNotFlashOverlayBeforeFirstServiceEvent() {
        assertFalse(PlaybackOverlayVisibilityState.shouldShow(null, true));
    }

    @Test
    public void serviceShowWaitsUntilPlayerBoundsAreReady() {
        assertFalse(PlaybackOverlayVisibilityState.shouldShow(Boolean.TRUE, false));
        assertTrue(PlaybackOverlayVisibilityState.shouldShow(Boolean.TRUE, true));
    }

    @Test
    public void serviceHideMakesOverlayHidden() {
        assertFalse(PlaybackOverlayVisibilityState.shouldShow(Boolean.FALSE, true));
    }
}
