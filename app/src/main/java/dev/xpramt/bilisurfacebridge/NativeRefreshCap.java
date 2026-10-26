package dev.xpramt.bilisurfacebridge;

/** Loads the module-owned arm64 bridge only in Bilibili's IJK service process. */
final class NativeRefreshCap {
    private static boolean loadAttempted;
    private static String loadFailure;

    private NativeRefreshCap() {
    }

    static synchronized String apply4msPatch() {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeApply4msPatch();
        } catch (Throwable throwable) {
            return "native patch call failed=" + throwable.getClass().getSimpleName();
        }
    }

    static synchronized String startProducerCounter(String statsPath) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeStartProducerCounter(statsPath);
        } catch (Throwable throwable) {
            return "Surface producer counter unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    static synchronized String startRefreshTelemetry(String rawSamplePath) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeStartRefreshTelemetry(rawSamplePath);
        } catch (Throwable throwable) {
            return "IJK refresh telemetry unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    static synchronized String setIjkFrameDropBlocked(boolean blocked) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeSetIjkFrameDropBlocked(blocked);
        } catch (Throwable throwable) {
            return "IJK frame-drop block unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    static synchronized String setComputeTargetDelayBlocked(boolean blocked) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeSetComputeTargetDelayBlocked(blocked);
        } catch (Throwable throwable) {
            return "compute_target_delay block unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    static synchronized String setFrameQueueWaitRoundUp(boolean enabled) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeSetFrameQueueWaitRoundUp(enabled);
        } catch (Throwable throwable) {
            return "frame queue wait rounding unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    static synchronized String patchPictureQueueRange(double newMin) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativePatchPictureQueueRange(newMin);
        } catch (Throwable throwable) {
            return "picture queue range patch unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    static final int GRID_LEGACY_LEAD = 0;
    static final int GRID_ARRIVAL = 1;
    static final int GRID_DEADLINE = 2;
    static final int GRID_DEADLINE_GL = 3;
    /** Media3 1.8.1 release model: timestamped release 45 ms ahead (docs/media3-reference.md). */
    static final int GRID_MEDIA3 = 4;

    /** gridMode: 0 legacy lead, 1 arrival mid-way between app VSyncs, 2 SF latch window. */
    static synchronized String setReleaseScheduling(boolean enabled, int callLeadPercent,
                                                    int gridMode) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeSetReleaseScheduling(enabled, callLeadPercent, gridMode);
        } catch (Throwable throwable) {
            return "native release scheduling unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    /** Surface external: skip frames that land on an already-used VSync (Media3). */
    static synchronized void setMedia3SkipSameVsync(boolean enabled) {
        if (ensureLoaded() != null) {
            return;
        }
        try {
            nativeSetMedia3SkipSameVsync(enabled);
        } catch (Throwable ignored) {
            // Older native library: faster content stays with IJK.
        }
    }

    static String releaseSchedulingStatus() {
        if (loadFailure != null || !loadAttempted) {
            return "native bridge not loaded";
        }
        try {
            return nativeReleaseSchedulingStatus();
        } catch (Throwable throwable) {
            return "status unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    /** Hot path: called once per frame from the IJK thread; never synchronized. */
    static void setVsyncGrid(long anchorNanos, long periodNanos, long vsyncNanos,
                             long deadlineNanos) {
        if (loadFailure != null || !loadAttempted) {
            return;
        }
        try {
            nativeSetVsyncGrid(anchorNanos, periodNanos, vsyncNanos, deadlineNanos);
        } catch (Throwable ignored) {
            // The scheduler falls back to IJK's own delay when the grid goes stale.
        }
    }

    static void noteDisplay(long nowNanos) {
        if (loadFailure != null || !loadAttempted) {
            return;
        }
        try {
            nativeNoteDisplay(nowNanos);
        } catch (Throwable ignored) {
            // Frame timer tracking resyncs by itself.
        }
    }

    /** Loads the bridge in this process (main process callers). */
    static synchronized String load() {
        return ensureLoaded();
    }

    /** The Surface SurfaceFlinger composes for this player; enables compositor timing. */
    static synchronized String setCompositorWindow(android.view.Surface surface) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeSetCompositorWindow(surface);
        } catch (Throwable throwable) {
            return "compositor window unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    /** [nextCompositeDeadlineNs, compositeIntervalNs, compositeToPresentNs] or null. */
    static long[] queryCompositorTiming() {
        if (loadFailure != null || !loadAttempted) {
            return null;
        }
        try {
            return nativeQueryCompositorTiming();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Hot path, IJK refresh thread: the release timestamp the native scheduler chose for the
     * frame being released now, -1 to release without one, or -2 to skip the frame
     * (release without rendering: an earlier frame already owns its VSync).
     */
    static long takeReleaseTimestamp() {
        if (loadFailure != null || !loadAttempted) {
            return -1L;
        }
        try {
            return nativeTakeReleaseTimestamp();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** Hot path: presentation time for the next eglSwapBuffers on this thread (0 clears). */
    static void armSwapTimestamp(long presentationNs) {
        if (loadFailure != null || !loadAttempted) {
            return;
        }
        try {
            nativeArmSwapTimestamp(presentationNs);
        } catch (Throwable ignored) {
            // Feedback-free hot path.
        }
    }

    /** GOT hook on the IJK libraries' eglSwapBuffers in this process. */
    static synchronized String installSwapTimestampHook() {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return loadResult;
        }
        try {
            return nativeInstallSwapTimestampHook();
        } catch (Throwable throwable) {
            return "swap timestamp hook unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    static String swapTimestampStatus() {
        if (loadFailure != null || !loadAttempted) {
            return "native bridge not loaded";
        }
        try {
            return nativeSwapTimestampStatus();
        } catch (Throwable throwable) {
            return "swap status unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    /** Hot path: actual present time of a scheduled frame (MediaCodec render callback). */
    static void notePresent(long presentNanos) {
        if (loadFailure != null || !loadAttempted) {
            return;
        }
        try {
            nativeNotePresent(presentNanos);
        } catch (Throwable ignored) {
            // Feedback only.
        }
    }

    /** Playback rate from IjkMediaPlayer.setSpeed; scheduling only runs at 1.0x. */
    static void setPlaybackRate(float rate) {
        if (loadFailure != null || !loadAttempted) {
            return;
        }
        try {
            nativeSetPlaybackRate(rate);
        } catch (Throwable ignored) {
            // Nothing to recover.
        }
    }

    static void invalidateFrameTimer() {
        if (loadFailure != null || !loadAttempted) {
            return;
        }
        try {
            nativeInvalidateFrameTimer();
        } catch (Throwable ignored) {
            // Nothing to recover.
        }
    }

    static synchronized long[] getIjkEarlyUsForPts(long ptsUs) {
        String loadResult = ensureLoaded();
        if (loadResult != null) {
            return null;
        }
        try {
            return nativeGetIjkEarlyUsForPts(ptsUs);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String ensureLoaded() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                System.loadLibrary("bilisurfacebridge");
            } catch (Throwable throwable) {
                loadFailure = throwable.getClass().getSimpleName();
            }
        }
        if (loadFailure != null) {
            return "native bridge unavailable=" + loadFailure;
        }
        return null;
    }

    private static native String nativeApply4msPatch();

    private static native String nativeStartProducerCounter(String statsPath);

    private static native String nativeStartRefreshTelemetry(String rawSamplePath);

    private static native String nativeSetIjkFrameDropBlocked(boolean blocked);

    private static native String nativeSetComputeTargetDelayBlocked(boolean blocked);

    private static native String nativeSetFrameQueueWaitRoundUp(boolean enabled);

    private static native String nativePatchPictureQueueRange(double newMin);

    private static native String nativeSetReleaseScheduling(boolean enabled, int callLeadPercent,
            int gridMode);

    private static native String nativeReleaseSchedulingStatus();

    private static native void nativeSetVsyncGrid(long anchorNanos, long periodNanos,
            long vsyncNanos, long deadlineNanos);

    private static native void nativeNoteDisplay(long nowNanos);

    private static native void nativeInvalidateFrameTimer();

    private static native void nativeSetPlaybackRate(float rate);

    private static native void nativeNotePresent(long presentNanos);

    private static native long nativeTakeReleaseTimestamp();

    private static native void nativeSetMedia3SkipSameVsync(boolean enabled);

    private static native void nativeArmSwapTimestamp(long presentationNs);

    private static native String nativeInstallSwapTimestampHook();

    private static native String nativeSwapTimestampStatus();

    private static native String nativeSetCompositorWindow(android.view.Surface surface);

    private static native long[] nativeQueryCompositorTiming();

    private static native long[] nativeGetIjkEarlyUsForPts(long ptsUs);
}
