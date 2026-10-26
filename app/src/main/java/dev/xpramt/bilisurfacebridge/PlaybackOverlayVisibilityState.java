package dev.xpramt.bilisurfacebridge;

/** Maps Bilibili's semantic control-container state to the stream-overlay visibility. */
final class PlaybackOverlayVisibilityState {
    private PlaybackOverlayVisibilityState() {
    }

    static boolean shouldShow(Boolean controlContainerShowing, boolean positionReady) {
        return positionReady && Boolean.TRUE.equals(controlContainerShowing);
    }
}
