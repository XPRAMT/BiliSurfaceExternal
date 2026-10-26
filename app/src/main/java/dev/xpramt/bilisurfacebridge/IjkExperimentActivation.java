package dev.xpramt.bilisurfacebridge;

/** Decides whether the IJK service needs the native refresh hooks. */
final class IjkExperimentActivation {
    private IjkExperimentActivation() {
    }

    static boolean shouldStartNativeHooks(boolean debugOverlay, boolean blockFrameDrop,
            boolean blockComputeTargetDelay, boolean roundUpFrameQueueWait) {
        return shouldStartNativeHooks(debugOverlay, blockFrameDrop, blockComputeTargetDelay,
                roundUpFrameQueueWait, false);
    }

    /** Frame-sync release scheduling runs inside the compute_target_delay hook. */
    static boolean shouldStartNativeHooks(boolean debugOverlay, boolean blockFrameDrop,
            boolean blockComputeTargetDelay, boolean roundUpFrameQueueWait,
            boolean media3FrameSync) {
        return debugOverlay || blockFrameDrop || blockComputeTargetDelay
                || roundUpFrameQueueWait || media3FrameSync;
    }
}
