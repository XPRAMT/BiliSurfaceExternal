package dev.xpramt.bilisurfacebridge;

/**
 * Small synchronized state machine for encoded-audio bitrate probes.
 *
 * <p>Every explicit track switch gets a new generation.  A URL is not an identity by itself:
 * the same URL can be selected again after switching away and back, while a late worker from the
 * previous selection may still be finishing.</p>
 */
final class AudioBitrateMeasurementState {
    private long generation;
    private String sourceKey = "";
    private boolean pending;
    private boolean measured;

    synchronized Started begin(String nextSourceKey, boolean force) {
        String normalizedKey = normalize(nextSourceKey);
        if (!force && normalizedKey.equals(sourceKey) && (pending || measured)) {
            return new Started(generation, false);
        }
        generation++;
        if (generation <= 0L) {
            generation = 1L;
        }
        sourceKey = normalizedKey;
        pending = true;
        measured = false;
        return new Started(generation, true);
    }

    synchronized boolean accepts(String resultSourceKey, long resultGeneration) {
        return pending
                && generation == resultGeneration
                && sourceKey.equals(normalize(resultSourceKey));
    }

    synchronized boolean markMeasured(String resultSourceKey, long resultGeneration) {
        if (!accepts(resultSourceKey, resultGeneration)) {
            return false;
        }
        pending = false;
        measured = true;
        return true;
    }

    synchronized boolean markMeasuredCurrent(String resultSourceKey) {
        if (!pending || !sourceKey.equals(normalize(resultSourceKey))) {
            return false;
        }
        pending = false;
        measured = true;
        return true;
    }

    synchronized boolean finish(String resultSourceKey, long resultGeneration) {
        if (!accepts(resultSourceKey, resultGeneration)) {
            return false;
        }
        pending = false;
        measured = false;
        return true;
    }

    synchronized boolean finishCurrent(String resultSourceKey) {
        if (!pending || !sourceKey.equals(normalize(resultSourceKey))) {
            return false;
        }
        pending = false;
        measured = false;
        return true;
    }

    synchronized void reset() {
        generation++;
        if (generation <= 0L) {
            generation = 1L;
        }
        sourceKey = "";
        pending = false;
        measured = false;
    }

    synchronized boolean isPending() {
        return pending;
    }

    synchronized boolean isMeasured() {
        return measured;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized String sourceKey() {
        return sourceKey;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Started {
        final long generation;
        final boolean started;

        Started(long generation, boolean started) {
            this.generation = generation;
            this.started = started;
        }
    }
}
