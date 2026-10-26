package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

/** Preferences shared by the module UI and the injected Bilibili process. */
final class ModulePreferences {
    private static final String TAG = "BiliSurfaceBridge";
    static final String PACKAGE_NAME = "dev.xpramt.bilisurfacebridge";
    static final String PREFS_NAME = "module";
    static final String KEY_VIDEO_DETAIL_RENDERER_MODE = "video_detail_renderer_mode";
    static final String KEY_PLAYBACK_DEBUG_OVERLAY = "playback_debug_overlay";
    static final String KEY_PLAYBACK_STREAM_OVERLAY = "playback_stream_overlay";
    static final String KEY_AUTO_SELECT_BEST_AUDIO = "auto_select_best_audio";
    static final String KEY_RENDER_CADENCE_PROBE = "render_cadence_probe";
    static final String KEY_MEDIA3_FRAME_SYNC = "media3_frame_sync";
    static final String KEY_QTI_OUTPUT_FENCE = "qti_output_fence";
    static final String KEY_BLOCK_IJK_FRAME_DROP = "block_ijk_frame_drop";
    static final String KEY_BLOCK_COMPUTE_TARGET_DELAY = "block_compute_target_delay";
    static final String KEY_FRAME_QUEUE_WAIT_ROUND_UP = "frame_queue_wait_round_up";
    static final String KEY_MINIMAL_MEDIA3_PLAYER = "minimal_media3_player";
    static final String KEY_REPLACE_STORY_VIDEO = "replace_story_video";
    static final boolean DEFAULT_PLAYBACK_DEBUG_OVERLAY = false;
    static final boolean DEFAULT_PLAYBACK_STREAM_OVERLAY = false;
    static final boolean DEFAULT_AUTO_SELECT_BEST_AUDIO = false;
    static final boolean DEFAULT_RENDER_CADENCE_PROBE = false;
    static final boolean DEFAULT_MEDIA3_FRAME_SYNC = true;
    // IJK earlyUs is retained as an explicit experiment. On NX809J its native clock sample can
    // lead the release deadline by hundreds of milliseconds, so it must not be the safe default.
    static final boolean DEFAULT_QTI_OUTPUT_FENCE = true;
    static final boolean DEFAULT_BLOCK_IJK_FRAME_DROP = false;
    static final boolean DEFAULT_BLOCK_COMPUTE_TARGET_DELAY = true;
    static final boolean DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP = false;
    static final boolean DEFAULT_MINIMAL_MEDIA3_PLAYER = false;
    static final boolean DEFAULT_REPLACE_STORY_VIDEO = false;

    private static volatile boolean automationProfileLocksRenderer = false;

    private ModulePreferences() {
    }

    enum RendererMode {
        TEXTURE_EXTERNAL("Texture", "TypeTextureViewWithExternalRender"),
        SURFACE_EXTERNAL("Surface external", "TypeSurfaceViewWithExternalRender"),
        SURFACE("Surface", "TypeSurfaceView");

        final String label;
        final String bilibiliTypeField;

        RendererMode(String label, String bilibiliTypeField) {
            this.label = label;
            this.bilibiliTypeField = bilibiliTypeField;
        }

        static RendererMode fromStoredValue(String value) {
            for (RendererMode mode : values()) {
                if (mode.name().equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    static RendererMode getVideoDetailRendererMode(Context context) {
        return rendererMode(preferences(context));
    }

    static void setVideoDetailRendererMode(Context context, RendererMode mode) {
        if (automationProfileLocksRenderer) {
            return;
        }
        commitPreferences(context, preferences(context).edit()
                .putString(KEY_VIDEO_DETAIL_RENDERER_MODE, mode.name()));
    }

    static boolean isPlaybackDebugOverlayEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_PLAYBACK_DEBUG_OVERLAY, DEFAULT_PLAYBACK_DEBUG_OVERLAY);
    }

    static void setPlaybackDebugOverlayEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_PLAYBACK_DEBUG_OVERLAY, enabled));
    }

    static boolean isPlaybackStreamOverlayEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_PLAYBACK_STREAM_OVERLAY, DEFAULT_PLAYBACK_STREAM_OVERLAY);
    }

    static void setPlaybackStreamOverlayEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_PLAYBACK_STREAM_OVERLAY, enabled));
    }

    static boolean isAutoSelectBestAudioEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_AUTO_SELECT_BEST_AUDIO, DEFAULT_AUTO_SELECT_BEST_AUDIO);
    }

    static boolean isReplaceStoryVideoEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_REPLACE_STORY_VIDEO, DEFAULT_REPLACE_STORY_VIDEO);
    }

    static void setReplaceStoryVideoEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_REPLACE_STORY_VIDEO, enabled));
    }

    static boolean loadReplaceStoryVideoForHook() {
        try {
            return hookPreferences().getBoolean(
                    KEY_REPLACE_STORY_VIDEO, DEFAULT_REPLACE_STORY_VIDEO);
        } catch (Throwable ignored) {
            return DEFAULT_REPLACE_STORY_VIDEO;
        }
    }

    static void setAutoSelectBestAudioEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_AUTO_SELECT_BEST_AUDIO, enabled));
    }

    static boolean isMedia3FrameSyncEnabled(Context context) {
        return preferences(context).getBoolean(KEY_MEDIA3_FRAME_SYNC, DEFAULT_MEDIA3_FRAME_SYNC);
    }

    static void setMedia3FrameSyncEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_MEDIA3_FRAME_SYNC, enabled));
    }

    static RendererMode loadVideoDetailRendererModeForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return rendererMode(preferences);
        } catch (Throwable ignored) {
            return RendererMode.SURFACE_EXTERNAL;
        }
    }

    static boolean loadPlaybackDebugOverlayForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_PLAYBACK_DEBUG_OVERLAY, DEFAULT_PLAYBACK_DEBUG_OVERLAY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadPlaybackStreamOverlayForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_PLAYBACK_STREAM_OVERLAY, DEFAULT_PLAYBACK_STREAM_OVERLAY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadAutoSelectBestAudioForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_AUTO_SELECT_BEST_AUDIO, DEFAULT_AUTO_SELECT_BEST_AUDIO);
        } catch (Throwable ignored) {
            return DEFAULT_AUTO_SELECT_BEST_AUDIO;
        }
    }

    static boolean loadRenderCadenceProbeForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_RENDER_CADENCE_PROBE,
                    DEFAULT_RENDER_CADENCE_PROBE);
        } catch (Throwable ignored) {
            return DEFAULT_RENDER_CADENCE_PROBE;
        }
    }

    static boolean loadMedia3FrameSyncForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_MEDIA3_FRAME_SYNC, DEFAULT_MEDIA3_FRAME_SYNC);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadMinimalMedia3PlayerForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            boolean enabled = preferences.getBoolean(
                    KEY_MINIMAL_MEDIA3_PLAYER, DEFAULT_MINIMAL_MEDIA3_PLAYER);
            File preferenceFile = preferences.getFile();
            Log.i(TAG, "[DEBUG-PREF-01] minimal_media3_player=" + enabled
                    + " contains=" + preferences.contains(KEY_MINIMAL_MEDIA3_PLAYER)
                    + " keys=" + preferences.getAll().keySet()
                    + " file=" + preferenceFile.getAbsolutePath()
                    + " readable=" + preferenceFile.canRead()
                    + " length=" + preferenceFile.length()
                    + " modified=" + preferenceFile.lastModified());
            return enabled;
        } catch (Throwable ignored) {
            Log.e(TAG, "[DEBUG-PREF-01] minimal_media3_player read failed"
                    + "=" + ignored.getClass().getSimpleName());
            return DEFAULT_MINIMAL_MEDIA3_PLAYER;
        }
    }

    static boolean isQtiOutputFenceEnabled(Context context) {
        return preferences(context).getBoolean(KEY_QTI_OUTPUT_FENCE, DEFAULT_QTI_OUTPUT_FENCE);
    }

    static void setQtiOutputFenceEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_QTI_OUTPUT_FENCE, enabled));
    }

    static boolean loadQtiOutputFenceForHook() {
        try {
            return hookPreferences().getBoolean(KEY_QTI_OUTPUT_FENCE, DEFAULT_QTI_OUTPUT_FENCE);
        } catch (Throwable ignored) {
            return DEFAULT_QTI_OUTPUT_FENCE;
        }
    }

    static boolean loadBlockIjkFrameDropForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_BLOCK_IJK_FRAME_DROP, DEFAULT_BLOCK_IJK_FRAME_DROP);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadComputeTargetDelayBlockedForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_BLOCK_COMPUTE_TARGET_DELAY,
                    DEFAULT_BLOCK_COMPUTE_TARGET_DELAY);
        } catch (Throwable ignored) {
            return DEFAULT_BLOCK_COMPUTE_TARGET_DELAY;
        }
    }

    static boolean loadFrameQueueWaitRoundUpForHook() {
        try {
            XSharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_FRAME_QUEUE_WAIT_ROUND_UP,
                    DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP);
        } catch (Throwable ignored) {
            return DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP;
        }
    }

    /** Apply a deterministic renderer A/B baseline from the unattended test runner. */
    static boolean applyAutomationProfile(Context context, String rendererModeValue,
                                            Boolean renderCadenceProbe,
                                            Boolean media3FrameSync,
                                            Boolean frameQueueWaitRoundUp) {
        return applyAutomationProfile(context, rendererModeValue, renderCadenceProbe,
                media3FrameSync, frameQueueWaitRoundUp, null, null);
    }

    /**
     * The measurement runner's A/B knobs (IJK block switches, frame-queue wait rounding, render
     * cadence probe) exist only here: they are not user settings.  The block switches default
     * to the historical A/B baseline (true) when null.
     */
    static boolean applyAutomationProfile(Context context, String rendererModeValue,
                                            Boolean renderCadenceProbe,
                                            Boolean media3FrameSync,
                                            Boolean frameQueueWaitRoundUp,
                                            Boolean blockIjkFrameDrop,
                                            Boolean blockComputeTargetDelay) {
        RendererMode mode = RendererMode.fromStoredValue(rendererModeValue);
        if (mode == null) {
            return false;
        }
        automationProfileLocksRenderer = true;
        SharedPreferences.Editor editor = preferences(context).edit()
                .putString(KEY_VIDEO_DETAIL_RENDERER_MODE, mode.name())
                .putBoolean(KEY_PLAYBACK_DEBUG_OVERLAY, true)
                .putBoolean(KEY_MEDIA3_FRAME_SYNC,
                        media3FrameSync != null && media3FrameSync.booleanValue())
                .putBoolean(KEY_BLOCK_IJK_FRAME_DROP,
                        blockIjkFrameDrop == null || blockIjkFrameDrop.booleanValue())
                .putBoolean(KEY_BLOCK_COMPUTE_TARGET_DELAY,
                        blockComputeTargetDelay == null
                                || blockComputeTargetDelay.booleanValue())
                .putBoolean(KEY_FRAME_QUEUE_WAIT_ROUND_UP,
                        frameQueueWaitRoundUp != null
                                && frameQueueWaitRoundUp.booleanValue());
        if (renderCadenceProbe != null) {
            editor.putBoolean(KEY_RENDER_CADENCE_PROBE, renderCadenceProbe.booleanValue());
        }
        return commitPreferences(context, editor);
    }

    /**
     * LSPosed's xposedsharedprefs bridge can only refresh module preferences when the source XML
     * remains readable outside the module process. Android 9+ ignores the legacy
     * MODE_WORLD_READABLE flag for modern-target applications, so make the committed file
     * explicitly world-readable after every write.
     */
    private static boolean commitPreferences(Context context, SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            return false;
        }
        return makePreferencesWorldReadable(context);
    }

    private static boolean makePreferencesWorldReadable(Context context) {
        try {
            File preferencesFile = new File(
                    new File(context.getApplicationInfo().dataDir, "shared_prefs"),
                    PREFS_NAME + ".xml");
            return preferencesFile.exists() && preferencesFile.setReadable(true, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // The hooks read preferences several times per video frame on the IJK release thread;
    // parsing the XML each time cost CPU and garbage in the hot path (GC bursts starved the
    // pipeline threads).  One instance, re-read at most every two seconds.
    private static final long HOOK_PREFERENCES_TTL_NS = 2_000_000_000L;
    private static XSharedPreferences cachedHookPreferences;
    private static long cachedHookPreferencesNs;

    private static synchronized XSharedPreferences hookPreferences() {
        long now = System.nanoTime();
        if (cachedHookPreferences == null
                || now - cachedHookPreferencesNs > HOOK_PREFERENCES_TTL_NS) {
            if (cachedHookPreferences == null) {
                cachedHookPreferences = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
            }
            cachedHookPreferences.reload();
            cachedHookPreferencesNs = now;
        }
        return cachedHookPreferences;
    }

    private static SharedPreferences preferences(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    private static RendererMode rendererMode(SharedPreferences preferences) {
        RendererMode stored = RendererMode.fromStoredValue(
                preferences.getString(KEY_VIDEO_DETAIL_RENDERER_MODE, null));
        if (stored != null) {
            return stored;
        }
        return RendererMode.SURFACE_EXTERNAL;
    }
}
