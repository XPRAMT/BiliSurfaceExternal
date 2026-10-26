package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VsyncCadenceMathTest {
    private static final long PERIOD_NS = 8_316_000L;

    @Test
    public void usesExpectedPresentationAndProjectsToFirstFutureSlot() {
        long expectedNs = 1_000_000_000L;
        long nowNs = expectedNs + PERIOD_NS * 3L + 100L;

        long result = VsyncCadenceMath.projectFreshAnchor(nowNs - 1_000_000L,
                expectedNs, expectedNs - 2_000_000L, PERIOD_NS, nowNs,
                100_000_000L, 4_000_000L, 34_000_000L);

        assertEquals(expectedNs + PERIOD_NS * 4L, result);
    }

    @Test
    public void rejectsStaleSampleInsteadOfReanchoringCadence() {
        long nowNs = 2_000_000_000L;

        long result = VsyncCadenceMath.projectFreshAnchor(nowNs - 100_000_001L,
                nowNs - PERIOD_NS, nowNs - PERIOD_NS, PERIOD_NS, nowNs,
                100_000_000L, 4_000_000L, 34_000_000L);

        assertEquals(VsyncCadenceMath.TIME_UNSET, result);
    }

    @Test
    public void keepsAlreadyFutureExpectedPresentation() {
        long nowNs = 3_000_000_000L;
        long expectedNs = nowNs + 2_000_000L;

        long result = VsyncCadenceMath.projectFreshAnchor(nowNs,
                expectedNs, nowNs, PERIOD_NS, nowNs,
                100_000_000L, 4_000_000L, 34_000_000L);

        assertEquals(expectedNs, result);
    }

    @Test
    public void usesCurrentAnchorWhenItAlreadyMeetsTarget() {
        assertEquals(0L, VsyncCadenceMath.firstSlotAtOrAfter(
                1_000_000_000L, 999_000_000L, PERIOD_NS));
    }

    @Test
    public void advancesEnoughSlotsToKeepEarlyReleaseInFuture() {
        long anchorNs = 1_000_000_000L;
        long nowNs = anchorNs - 2_000_000L;
        long releaseOffsetNs = PERIOD_NS * 80L / 100L;
        long earliestTargetNs = nowNs + releaseOffsetNs;

        assertEquals(1L, VsyncCadenceMath.firstSlotAtOrAfter(
                anchorNs, earliestTargetNs, PERIOD_NS));
    }
}
