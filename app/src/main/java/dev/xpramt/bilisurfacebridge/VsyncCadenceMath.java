package dev.xpramt.bilisurfacebridge;

/** Pure timing helpers for mapping codec releases onto a fresh display timeline. */
final class VsyncCadenceMath {
    static final long TIME_UNSET = Long.MIN_VALUE;

    private VsyncCadenceMath() {
    }

    static long projectFreshAnchor(long sampledAtNs, long expectedPresentationTimeNs,
                                   long sampledVsyncTimeNs, long vsyncDurationNs, long nowNs,
                                   long maxSampleAgeNs, long minVsyncDurationNs,
                                   long maxVsyncDurationNs) {
        long anchorNs = expectedPresentationTimeNs != TIME_UNSET
                ? expectedPresentationTimeNs : sampledVsyncTimeNs;
        if (sampledAtNs == TIME_UNSET || anchorNs == TIME_UNSET
                || vsyncDurationNs < minVsyncDurationNs
                || vsyncDurationNs > maxVsyncDurationNs) {
            return TIME_UNSET;
        }
        long ageNs = nowNs - sampledAtNs;
        if (ageNs < 0L || ageNs > maxSampleAgeNs) {
            return TIME_UNSET;
        }
        if (anchorNs < nowNs) {
            long elapsedNs = nowNs - anchorNs;
            long slots = (elapsedNs + vsyncDurationNs - 1L) / vsyncDurationNs;
            anchorNs += slots * vsyncDurationNs;
        }
        return anchorNs;
    }

    static long firstSlotAtOrAfter(long anchorNs, long targetNs, long periodNs) {
        long relativeNs = targetNs - anchorNs;
        return relativeNs <= 0L ? 0L : (relativeNs + periodNs - 1L) / periodNs;
    }
}
