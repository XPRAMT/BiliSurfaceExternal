package dev.xpramt.bilisurfacebridge;

import android.graphics.Point;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Selects the requested Bilibili renderer only for a normal video-detail player. This avoids
 * changing cards, ads, and embedded players. Stream information overlays may also attach to a
 * live-room Activity, without changing the live renderer. The module logs renderer and display
 * facts without reading media URLs, account data, cookies, or playback authorization data.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final String IJK_SERVICE_PROCESS = "tv.danmaku.bili:ijkservice";
    private static final String NX809J_MODEL = "NX809J";
    private static final String FACTORY =
            "tv.danmaku.biliplayerimpl.render.DefaultVideoRenderLayerFactory";
    private static final String FACTORY_913 = "Wr1.a";
    private static final String RENDER_CONTAINER =
            "tv.danmaku.biliplayerimpl.render.RenderContainerService";
    private static final String RENDER_CONTAINER_913 = "Wr1.r";
    private static final String PLAYER_CONTAINER =
            "tv.danmaku.biliplayerimpl.PlayerContainerImpl";
    private static final String PLAYER_CONTAINER_913 = "Rr1.i";
    private static final String RENDER_LAYER = "tv.danmaku.render.core.IVideoRenderLayer";
    private static final String RENDER_TYPE = "tv.danmaku.render.core.IVideoRenderLayer$Type";
    private static final String VIDEO_DETAIL_ACTIVITY =
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity";
    private static final Set<String> REPORTED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Object, String> FACTORY_CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Map<Object, WeakReference<Activity>> FACTORY_ACTIVITIES =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Activity>>());
    private static final String CONTROL_CONTAINER_SERVICE =
            "tv.danmaku.biliplayerimpl.controlcontainer.ControlContainerService";
    private static final String CONTROL_CONTAINER_SERVICE_913 =
            "tv.danmaku.biliplayerimpl.controlcontainer.f";
    private static final String IJK_SERVICE =
            "tv.danmaku.ijk.media.player.services.IjkMediaPlayerService";
    private static final String IJK_PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer";
    private static final String IJK_VIDEO_DISPLAY_CALLBACK =
            "tv.danmaku.ijk.media.player.IjkMediaPlayer$OnVideoDisplayCallback";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }
        log("loaded for Bilibili; no media or account data is collected");
        boolean minimalMedia3Enabled = ModulePreferences.loadMinimalMedia3PlayerForHook();
        log("minimal Media3 preference=" + minimalMedia3Enabled
                + " process=" + loadPackageParam.processName);
        if (minimalMedia3Enabled) {
            logOnce("minimal-media3-player:" + loadPackageParam.processName,
                    Media3MinimalPlayerBridge.install(loadPackageParam.classLoader));
        } else {
            logOnce("stream-selection-probe:" + loadPackageParam.processName,
                    Media3MinimalPlayerBridge.installStreamSelectionProbe(
                            loadPackageParam.classLoader));
        }
        if (IJK_SERVICE_PROCESS.equals(loadPackageParam.processName)) {
            hookIjkService(loadPackageParam.classLoader);
            return;
        }
        if (TARGET_PACKAGE.equals(loadPackageParam.processName)) {
            // The IjkMediaPlayer Java API is driven from the main process (it forwards to the
            // service over AIDL), so player option hooks belong here, not in the service.
            installPictureQueueSizeHook(loadPackageParam.classLoader);
            if (ModulePreferences.loadMedia3FrameSyncForHook()) {
                logOnce("external-render-immediate", ExternalRenderImmediate.install());
                logOnce("textureview-vsync-invalidator", TextureViewVsyncInvalidator.install());
                logOnce("external-render-timestamps", ExternalRenderTimestamps.install());
            }
        }
        if (TARGET_PACKAGE.equals(loadPackageParam.processName)
                && ModulePreferences.loadReplaceStoryVideoForHook()) {
            logOnce("replace-story-video", StoryRouteHook.install(loadPackageParam.classLoader));
        }
        hookIjkDisplayFrameCounter(loadPackageParam.classLoader);
        hookFactory(loadPackageParam.classLoader);
        hookRenderContainer(loadPackageParam.classLoader);
        hookPlaybackActivityLifecycle(loadPackageParam.classLoader);
    }

    private void hookIjkDisplayFrameCounter(ClassLoader classLoader) {
        if (!ModulePreferences.loadPlaybackDebugOverlayForHook()) {
            return;
        }
        try {
            Class<?> ijkPlayerClass = XposedHelpers.findClass(IJK_PLAYER, classLoader);
            Class<?> callbackClass = XposedHelpers.findClass(IJK_VIDEO_DISPLAY_CALLBACK, classLoader);
            XposedHelpers.findAndHookMethod(ijkPlayerClass, "setOnVideoDisplayCallback", callbackClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object delegate = param.args[0];
                            if (delegate != null && Proxy.isProxyClass(delegate.getClass())
                                    && Proxy.getInvocationHandler(delegate)
                                    instanceof PlaybackDebugOverlay.FrameCallbackProxy) {
                                return;
                            }
                            param.args[0] = PlaybackDebugOverlay.frameCallback(
                                    callbackClass, delegate);
                        }
                    });
            XposedHelpers.findAndHookMethod(ijkPlayerClass, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        PlaybackDebugOverlay.resetFrameTiming();
                        Object existingCallback = XposedHelpers.getObjectField(
                                param.thisObject, "mOnVideoDisplayCallback");
                        if (existingCallback != null && Proxy.isProxyClass(existingCallback.getClass())
                                && Proxy.getInvocationHandler(existingCallback)
                                instanceof PlaybackDebugOverlay.FrameCallbackProxy) {
                            return;
                        }
                        XposedHelpers.callMethod(param.thisObject, "setOnVideoDisplayCallback",
                                PlaybackDebugOverlay.frameCallback(callbackClass, existingCallback));
                    } catch (Throwable throwable) {
                        logOnce("ijk-debug-start", "IJK debug callback unavailable: "
                                + throwable.getClass().getSimpleName());
                    }
                }
            });
            log("hooked IJK video-display callback for debug fps");
            if (ModulePreferences.loadRenderCadenceProbeForHook()) {
                logOnce("p2-render-cadence-probe", RenderCadenceProbe.install());
            } else {
                logOnce("p2-render-cadence-probe-disabled",
                        "P2 render cadence probe disabled by profile");
            }
        } catch (Throwable throwable) {
            log("IJK debug fps hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookIjkService(ClassLoader classLoader) {
        if (!NX809J_MODEL.equals(Build.MODEL)) {
            log("IJK 4ms experiment skipped: device=" + Build.MODEL);
            return;
        }
        try {
            if (ModulePreferences.loadPlaybackDebugOverlayForHook()
                    && ModulePreferences.loadRenderCadenceProbeForHook()) {
                logOnce("p2-render-cadence-probe-service",
                        RenderCadenceProbe.install());
            }
            Class<?> serviceClass = XposedHelpers.findClass(IJK_SERVICE, classLoader);
            XposedHelpers.findAndHookMethod(serviceClass, "loadLibrariesOnce", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyExperiments(param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(serviceClass, "onBind", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyExperiments(param.thisObject);
                        }
            });
            logOnce("mediacodec-frame-sync-hooks", Media3FrameSynchronizer.install());
            logOnce("media3-playback-clock-hooks",
                    Media3FrameSynchronizer.installPlaybackClockHooks(classLoader));
            log("hooked IjkMediaPlayerService for NX809J experiments");
        } catch (Throwable throwable) {
            log("IJK refresh-cap hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * IJK keeps three decoded pictures ahead of display; together with the two buffers
     * SurfaceFlinger holds, that leaves the decoder's output pool almost no slack, and any
     * VSync-aligned release delays the pool by up to a period.  Two pictures are enough for a
     * hardware decoder and give the pool one buffer back.  Option category 4 = player.
     */
    private static final int PICTURE_QUEUE_SIZE = 12;
    private static final ThreadLocal<Boolean> INTERNAL_OPTION_CALL = new ThreadLocal<Boolean>();

    // Disabled: Bilibili 9.13.0's IJK rejects the option ("Value 2 for parameter
    // 'video-pictq-size' out of range [3 - 16]"), so the three-picture queue cannot be
    // reduced from the Java API.  Kept for the option log, which showed that the app itself
    // only sets start-on-prepared through IjkMediaPlayer.setOption.
    private static final boolean IJK_PICTURE_QUEUE_OVERRIDE = true;

    private void installPictureQueueSizeHook(ClassLoader classLoader) {
        if (!IJK_PICTURE_QUEUE_OVERRIDE || !ModulePreferences.loadMedia3FrameSyncForHook()) {
            return;
        }
        try {
            final Class<?> playerClass = XposedHelpers.findClass(IJK_PLAYER, classLoader);
            XC_MethodHook optionHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        XposedHelpers.callMethod(param.thisObject, "_setOptionLong", 4,
                                "video-pictq-size", 2L);
                        logOnce("ijk-pictq-size:" + param.method.getName(),
                                "IJK video-pictq-size set to 2 before " + param.method.getName());
                    } catch (Throwable throwable) {
                        logOnce("ijk-pictq-size-failed", "IJK video-pictq-size option failed="
                                + throwable.getClass().getSimpleName());
                    }
                }
            };
            StringBuilder names = new StringBuilder();
            for (java.lang.reflect.Method method : playerClass.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("speed") || name.contains("rate") || name.contains("prepare")
                        || name.contains("option") || name.contains("start")
                        || name.contains("datasource") || name.contains("item")) {
                    names.append(method.getName()).append('(')
                            .append(method.getParameterTypes().length).append(") ");
                }
            }
            logOnce("ijk-player-methods", "IjkMediaPlayer methods: " + names);
            // Bilibili configures the player through setOption(int, String, long/String) before
            // preparing; the first such call per instance is the hook point (prepareAsync and
            // the item path never fired on 9.13.0).
            final java.util.Set<Object> configured = java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Object, Boolean>());
            XC_MethodHook firstOptionHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(INTERNAL_OPTION_CALL.get())) {
                        return;
                    }
                    if (param.args.length == 3 && param.args[1] instanceof String) {
                        String optionName = (String) param.args[1];
                        logOnce("ijk-option:" + param.args[0] + ":" + optionName,
                                "IJK option " + param.args[0] + " " + optionName + "="
                                        + param.args[2]);
                        if ("video-pictq-size".equals(optionName)
                                && param.args[2] instanceof Long) {
                            param.args[2] = (long) PICTURE_QUEUE_SIZE;
                            logOnce("ijk-pictq-override", "IJK video-pictq-size overridden to "
                                    + PICTURE_QUEUE_SIZE);
                        }
                    }
                    synchronized (configured) {
                        if (!configured.add(param.thisObject)) {
                            return;
                        }
                    }
                    try {
                        INTERNAL_OPTION_CALL.set(Boolean.TRUE);
                        XposedHelpers.callMethod(param.thisObject, "setOption", 4,
                                "video-pictq-size", (long) PICTURE_QUEUE_SIZE);
                        logOnce("ijk-pictq-size", "IJK video-pictq-size set to "
                                + PICTURE_QUEUE_SIZE + " on first setOption");
                    } catch (Throwable throwable) {
                        logOnce("ijk-pictq-size-failed", "IJK video-pictq-size option failed="
                                + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
                    } finally {
                        INTERNAL_OPTION_CALL.set(Boolean.FALSE);
                    }
                }
            };
            int hooked = XposedBridge.hookAllMethods(playerClass, "setOption", firstOptionHook).size();
            // Bilibili may configure through IjkMediaPlayerItem instead; list and hook its
            // option setters as well (the item forwards options to the native player).
            try {
                Class<?> itemClass = XposedHelpers.findClass(
                        "tv.danmaku.ijk.media.player.IjkMediaPlayerItem", classLoader);
                StringBuilder itemNames = new StringBuilder();
                for (java.lang.reflect.Method method : itemClass.getDeclaredMethods()) {
                    String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("option") || name.contains("prepare") || name.contains("start")
                            || name.contains("datasource") || name.contains("init")) {
                        itemNames.append(method.getName()).append('(')
                                .append(method.getParameterTypes().length).append(") ");
                    }
                }
                logOnce("ijk-item-methods", "IjkMediaPlayerItem methods: " + itemNames);
                hooked += XposedBridge.hookAllMethods(itemClass, "setOption", firstOptionHook).size();
            } catch (Throwable throwable) {
                logOnce("ijk-item-class", "IjkMediaPlayerItem unavailable="
                        + throwable.getClass().getSimpleName());
            }
            for (String name : new String[] {"prepareAsync", "_prepareAsync", "setIjkMediaPlayerItem"}) {
                hooked += XposedBridge.hookAllMethods(playerClass, name, optionHook).size();
            }
            log("hooked IjkMediaPlayer prepare paths for picture queue size=" + hooked);
        } catch (Throwable throwable) {
            log("picture queue size hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private void applyExperiments(Object service) {
        boolean debugOverlay = ModulePreferences.loadPlaybackDebugOverlayForHook();
        boolean blockDrop = ModulePreferences.loadBlockIjkFrameDropForHook();
        boolean blockComputeTargetDelay =
                ModulePreferences.loadComputeTargetDelayBlockedForHook();
        boolean frameQueueWaitRoundUp =
                ModulePreferences.loadFrameQueueWaitRoundUpForHook();
        boolean media3FrameSync = ModulePreferences.loadMedia3FrameSyncForHook();
        if (media3FrameSync) {
            // libijkplayer.so is loaded by the time the service runs; rewrite the option's
            // default and minimum before any ffp is created.  The online detail page never
            // calls IjkMediaPlayer.setOption, so the main-process injection alone left the
            // queue at 3 there.  Log each distinct result so a "not loaded" first attempt
            // does not hide the later success.
            String pictqPatch = NativeRefreshCap.patchPictureQueueRange(PICTURE_QUEUE_SIZE);
            logOnce("ijk-pictq-range:" + pictqPatch, pictqPatch);
        }
        if (IjkExperimentActivation.shouldStartNativeHooks(debugOverlay, blockDrop,
                blockComputeTargetDelay, frameQueueWaitRoundUp, media3FrameSync)) {
            logOnce("ijk-refresh-telemetry",
                    NativeRefreshCap.startRefreshTelemetry(debugOverlay ? rawSamplePath() : ""));
            // Keep frame-drop blocking independent from Media3 timed release so the A/B test can
            // disable PTS/VSync release scheduling without restoring IJK's original drop path.
            logOnce("ijk-frame-drop-block:" + blockDrop,
                    NativeRefreshCap.setIjkFrameDropBlocked(blockDrop));
            logOnce("compute-target-delay-block:" + blockComputeTargetDelay,
                    NativeRefreshCap.setComputeTargetDelayBlocked(blockComputeTargetDelay));
            logOnce("frame-queue-wait-round-up:" + frameQueueWaitRoundUp,
                    NativeRefreshCap.setFrameQueueWaitRoundUp(frameQueueWaitRoundUp));
        }
    }

    private String rawSamplePath() {
        int userId = android.os.Process.myUid() / 100000;
        File directory = new File("/data/user/" + userId + "/"
                + TARGET_PACKAGE + "/files/bilisurfacebridge");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            return "";
        }
        return new File(directory, "refresh_samples.csv").getAbsolutePath();
    }

    private void hookFactory(ClassLoader classLoader) {
        try {
            Class<?> factoryClass = XposedHelpers.findClassIfExists(FACTORY, classLoader);
            boolean modern = factoryClass == null;
            if (modern) {
                factoryClass = XposedHelpers.findClass(FACTORY_913, classLoader);
            }
            final Class<?> typeClass = XposedHelpers.findClass(RENDER_TYPE, classLoader);
            XposedHelpers.findAndHookMethod(factoryClass, modern ? "onCreate" : "b",
                    typeClass, new XC_MethodHook() {
                @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object requestedType = param.args[0];
                        Object configuredType = configuredRendererType(param.thisObject);
                        // The offline-cache player lives in UnitedPlaylistActivity and uses
                        // the same factory, so the renderer choice applies to both pages.
                        String context = factoryContext(param.thisObject);
                        if (PlaybackActivityScope.isVideoDetailActivity(context)
                                || PlaybackActivityScope.isVideoPlaylistActivity(context)) {
                            ModulePreferences.RendererMode mode =
                                    ModulePreferences.loadVideoDetailRendererModeForHook();
                            if (ModulePreferences.loadMinimalMedia3PlayerForHook()) {
                                mode = ModulePreferences.RendererMode.SURFACE;
                            }
                        Object selectedRenderer = XposedHelpers.getStaticObjectField(
                                typeClass, mode.bilibiliTypeField);
                        param.args[0] = selectedRenderer;
                        logOnce("detail-renderer:" + mode.name(),
                                "selected video-detail renderer=" + mode.label
                                        + " requested=" + requestedType
                                        + " configured=" + configuredType);
                    } else {
                        logOnce("factory:" + String.valueOf(requestedType) + ':'
                                        + String.valueOf(configuredType),
                                "kept Bilibili renderer request=" + requestedType
                                        + ", configured=" + configuredType);
                    }
                }

                @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object layer = param.getResult();
                            reportLayer("factory", layer);
                            attachDebugOverlayForPlayback(param.thisObject, layer);
                            if (SURFACE_FRAME_RATE_VOTE && layer != null
                                    && isVideoPlaybackActivity(factoryContext(param.thisObject))) {
                                voteSurfaceFrameRate(layer);
                            }
                        }
            });
            log("hooked video render factory=" + factoryClass.getName());
        } catch (Throwable throwable) {
            log("factory hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookRenderContainer(ClassLoader classLoader) {
        try {
            Class<?> containerClass = XposedHelpers.findClassIfExists(RENDER_CONTAINER, classLoader);
            boolean modern = containerClass == null;
            if (modern) {
                containerClass = XposedHelpers.findClass(RENDER_CONTAINER_913, classLoader);
            }
            Class<?> layerClass = XposedHelpers.findClass(RENDER_LAYER, classLoader);
            Class<?> playerContainerClass = XposedHelpers.findClass(
                    modern ? PLAYER_CONTAINER_913 : PLAYER_CONTAINER, classLoader);
            final String contextField = modern ? "b" : "c";
            XposedHelpers.findAndHookMethod(containerClass, modern ? "e" : "M7",
                    layerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    reportLayer("attached", param.args[0]);
                    if (ModulePreferences.loadMedia3FrameSyncForHook()) {
                        PipelineThreadBoost.scheduleRenderThreadBoost();
                        AppVsyncRelay.start();
                    }
                }
            });
            XposedHelpers.findAndHookMethod(containerClass,
                    "bindPlayerContainer$srcs_legacy_andruid_common_player_biliplayerimpl_biliplayerimpl_base",
                    playerContainerClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object factory = XposedHelpers.getObjectField(param.thisObject, "s");
                                Object context = XposedHelpers.getObjectField(
                                        param.args[0], contextField);
                                if (factory != null && context instanceof Context) {
                                    String contextName = context.getClass().getName();
                                    FACTORY_CONTEXTS.put(factory, contextName);
                                    Activity activity = activityFromContext((Context) context);
                                    if (activity != null) {
                                        FACTORY_ACTIVITIES.put(factory,
                                                new WeakReference<Activity>(activity));
                                    }
                                    logOnce("factory-context:" + contextName,
                                            "player factory context=" + contextName);
                                }
                            } catch (Throwable throwable) {
                                log("player-container context unavailable: "
                                        + throwable.getClass().getSimpleName());
                            }
                        }
                    });
            log("hooked video render container=" + containerClass.getName());
        } catch (Throwable throwable) {
            log("render-container hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private Object configuredRendererType(Object factory) {
        try {
            Object playerParams = XposedHelpers.getObjectField(factory, "a");
            if (FACTORY_913.equals(factory.getClass().getName())) {
                Object config = XposedHelpers.callMethod(playerParams, "getConfig");
                return XposedHelpers.callMethod(config, "getVideoRenderLayerType");
            }
            Object configuration = XposedHelpers.getObjectField(playerParams, "c");
            return XposedHelpers.getObjectField(configuration, "l");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String factoryContext(Object factory) {
        String contextName = FACTORY_CONTEXTS.get(factory);
        return contextName == null ? "unknown" : contextName;
    }

    /**
     * SurfaceFlinger decides the panel mode from the buffer cadence unless the layer votes.
     * The codec-side Surface.setFrameRate() never reaches the BLAST layer from the service
     * process (votes stayed "Heuristic" and the panel flipped to 60 Hz when late frames made
     * the stream look like 60 fps), so vote on the SurfaceView's SurfaceControl here, in the
     * process that owns it.  Only while frame sync is on: the scheduler paces onto the
     * panel's VSync grid, so 120 Hz is wanted for any content rate.
     */
    private void voteSurfaceFrameRate(Object layer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !ModulePreferences.loadMedia3FrameSyncForHook()) {
            return;
        }
        final SurfaceView surfaceView = findSurfaceView(layer, 0);
        if (surfaceView == null) {
            logOnce("frame-rate-vote-no-surfaceview:" + layer.getClass().getName(),
                    "no SurfaceView under renderer " + layer.getClass().getName());
            return;
        }
        if (surfaceView.getTag(FRAME_RATE_VOTE_TAG) != null) {
            return;
        }
        surfaceView.setTag(FRAME_RATE_VOTE_TAG, Boolean.TRUE);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                applyFrameRateVote(surfaceView);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                applyFrameRateVote(surfaceView);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // The vote dies with the SurfaceControl.
            }
        });
        applyFrameRateVote(surfaceView);
    }

    // Disabled: NX809J's SurfaceFlinger (Android 16, MRR panel) accepted the vote
    // ("ExplicitExactOrMultiple 120 Hz") but then reset it every frame ("Reset layer to
    // ignore explicit vote on MRR") and initiated a display mode change ~88 times per
    // second; the panel effectively ran at 87 Hz.  Leaving the heuristic alone keeps 120 Hz
    // as long as the buffer cadence is right.
    private static final boolean SURFACE_FRAME_RATE_VOTE = false;
    private static final int FRAME_RATE_VOTE_TAG = 0x7f0b5b1d;
    private static final float FRAME_SYNC_PANEL_RATE = 120f;

    private void applyFrameRateVote(SurfaceView surfaceView) {
        try {
            SurfaceControl control = surfaceView.getSurfaceControl();
            if (control == null || !control.isValid()) {
                return;
            }
            new SurfaceControl.Transaction()
                    .setFrameRate(control, FRAME_SYNC_PANEL_RATE,
                            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            Surface.CHANGE_FRAME_RATE_ALWAYS)
                    .apply();
            logOnce("frame-rate-vote", "voted " + FRAME_SYNC_PANEL_RATE
                    + " Hz on the video SurfaceView layer");
        } catch (Throwable throwable) {
            logOnce("frame-rate-vote-failed", "SurfaceControl frame-rate vote failed="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static SurfaceView findSurfaceView(Object view, int depth) {
        if (view instanceof SurfaceView) {
            return (SurfaceView) view;
        }
        if (view instanceof ViewGroup && depth < 6) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                SurfaceView found = findSurfaceView(group.getChildAt(index), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void reportLayer(String source, Object layer) {
        if (layer == null) {
            return;
        }
        String className = layer.getClass().getName();
        logOnce(source + ':' + className, source + " renderer=" + className);
        if (layer instanceof View) {
            reportDisplay((View) layer, className);
        }
    }

    private void attachDebugOverlayForPlayback(Object factory, Object layer) {
        boolean debugOverlayEnabled = ModulePreferences.loadPlaybackDebugOverlayForHook();
        boolean streamOverlayEnabled = ModulePreferences.loadPlaybackStreamOverlayForHook();
        if (!debugOverlayEnabled && !streamOverlayEnabled) {
            return;
        }
        if (!isVideoPlaybackActivity(factoryContext(factory)) || !(layer instanceof View)) {
            return;
        }
        WeakReference<Activity> reference = FACTORY_ACTIVITIES.get(factory);
        Activity activity = reference == null ? null : reference.get();
        if (activity == null) {
            logOnce("debug-overlay:activity-missing",
                    "debug overlay waiting for video-detail Activity");
            return;
        }
        DecoderFormatRelay.installMainProcessReceiver(activity);
        String rendererClass = layer.getClass().getName();
        if (debugOverlayEnabled) {
            PlaybackDebugOverlay.show(activity, rendererClass);
            logOnce("debug-overlay:" + rendererClass,
                    "debug overlay attached renderer=" + rendererClass);
        }
        if (streamOverlayEnabled) {
            PlaybackStreamOverlay.show(activity);
            logOnce("stream-overlay", "stream overlay attached");
        }
    }

    private boolean isVideoPlaybackActivity(String contextName) {
        return PlaybackActivityScope.isVideoPlaybackActivity(contextName);
    }

    /**
     * Attach the stream overlay from playback activity lifecycle. Newer Bilibili versions no
     * longer expose the legacy render-layer factory used by the original attachment path.
     */
    private void hookPlaybackActivityLifecycle(ClassLoader classLoader) {
        if (!ModulePreferences.loadPlaybackStreamOverlayForHook()) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                attachStreamOverlayToActivity((Activity) param.thisObject);
                            }
                        }
                    });
            logOnce("stream-activity-hook",
                    "hooked Activity.onPostResume for playback stream overlay");
        } catch (Throwable throwable) {
            log("live stream activity hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        installControlContainerVisibilityHook(classLoader);
    }

    private void installControlContainerVisibilityHook(ClassLoader classLoader) {
        if (!REPORTED.add("control-container-service-visibility-hook")) {
            return;
        }
        try {
            Class<?> serviceClass = XposedHelpers.findClassIfExists(
                    CONTROL_CONTAINER_SERVICE, classLoader);
            if (serviceClass == null) {
                serviceClass = XposedHelpers.findClass(
                        CONTROL_CONTAINER_SERVICE_913, classLoader);
            }
            XC_MethodHook visibilityHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        return;
                    }
                    Activity activity = activityFromControlContainerService(param.thisObject);
                    if (activity == null || !PlaybackActivityScope.isVideoPlaybackActivity(
                            activity.getClass().getName())) {
                        return;
                    }
                    boolean visible;
                    try {
                        Object isShowing = XposedHelpers.callMethod(
                                param.thisObject, "isShowing");
                        visible = isShowing instanceof Boolean
                                ? ((Boolean) isShowing).booleanValue()
                                : "show".equals(param.method.getName());
                    } catch (Throwable ignored) {
                        visible = "show".equals(param.method.getName());
                    }
                    PlaybackStreamOverlay.setPlaybackControlsVisible(activity, visible);
                    logOnce("control-container-state:" + visible + ':'
                                    + activity.getClass().getName(),
                            "Bilibili control container state="
                                    + (visible ? "shown" : "hidden")
                                    + " activity=" + activity.getClass().getName());
                }
            };
            XposedHelpers.findAndHookMethod(serviceClass, "show", visibilityHook);
            XposedHelpers.findAndHookMethod(serviceClass, "hide", visibilityHook);
            log("hooked Bilibili control container show/hide class="
                    + serviceClass.getName());
        } catch (Throwable throwable) {
            log("Bilibili ControlContainerService visibility hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private Activity activityFromControlContainerService(Object service) {
        try {
            Object playerContainer = XposedHelpers.getObjectField(service, "a");
            Object context = XposedHelpers.getObjectField(playerContainer, "c");
            if (context instanceof Context) {
                Activity activity = activityFromContext((Context) context);
                if (activity != null) {
                    return activity;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the actual control-container View if the owner context is unavailable.
        }
        try {
            Object controlContainer = XposedHelpers.getObjectField(service, "b");
            if (controlContainer instanceof View) {
                return activityFromContext(((View) controlContainer).getContext());
            }
        } catch (Throwable ignored) {
            // The semantic event is ignored if neither known owner exposes an Activity context.
        }
        return null;
    }

    private void attachStreamOverlayToActivity(Activity activity) {
        String activityName = activity.getClass().getName();
        if (!PlaybackActivityScope.isVideoPlaybackActivity(activityName)) {
            return;
        }
        DecoderFormatRelay.installMainProcessReceiver(activity);
        if (PlaybackActivityScope.isLiveRoomActivity(activityName)) {
            PlaybackStreamOverlay.beginLiveAudioBitrateMeasurement();
        }
        PlaybackStreamOverlay.show(activity);
        logOnce("stream-activity-overlay:" + activityName,
                "stream overlay attached activity=" + activityName);
    }

    private Activity activityFromContext(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) {
                return null;
            }
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private void reportDisplay(View view, String rendererClass) {
        try {
            Display display = view.getDisplay();
            if (display == null) {
                WindowManager windowManager = (WindowManager) view.getContext()
                        .getSystemService(android.content.Context.WINDOW_SERVICE);
                display = windowManager == null ? null : windowManager.getDefaultDisplay();
            }
            if (display == null) {
                logOnce("display:none", "display unavailable for " + rendererClass);
                return;
            }
            Point appSize = new Point();
            display.getSize(appSize);
            DisplayMetrics realMetrics = new DisplayMetrics();
            display.getRealMetrics(realMetrics);
            Display.Mode mode = display.getMode();
            Display.Mode[] modes = display.getSupportedModes();
            StringBuilder message = new StringBuilder("display renderer=")
                    .append(rendererClass)
                    .append(" app=").append(appSize.x).append('x').append(appSize.y)
                    .append(" real=").append(realMetrics.widthPixels).append('x')
                    .append(realMetrics.heightPixels)
                    .append(" active=").append(mode.getPhysicalWidth()).append('x')
                    .append(mode.getPhysicalHeight()).append('@').append(mode.getRefreshRate())
                    .append(" supportedModes=").append(modes.length)
                    .append(" hdrTypes=");
            if (android.os.Build.VERSION.SDK_INT >= 24
                    && display.getHdrCapabilities() != null) {
                int[] hdrTypes = display.getHdrCapabilities().getSupportedHdrTypes();
                for (int index = 0; index < hdrTypes.length; index++) {
                    if (index > 0) {
                        message.append(',');
                    }
                    message.append(hdrTypes[index]);
                }
            } else {
                message.append("none");
            }
            logOnce("display:" + rendererClass + ':' + appSize.x + 'x' + appSize.y + ':'
                    + realMetrics.widthPixels + 'x' + realMetrics.heightPixels + ':'
                    + mode.getPhysicalWidth() + 'x' + mode.getPhysicalHeight(), message.toString());
        } catch (Throwable throwable) {
            log("display report failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void logOnce(String key, String message) {
        if (REPORTED.add(key)) {
            log(message);
            if (message.contains("renderer=")) {
                Log.i(TAG, message);
            }
        }
    }

    private static void log(String message) {
        String line = TAG + ": " + message;
        XposedBridge.log(line);
        Log.i(TAG, message);
    }
}
