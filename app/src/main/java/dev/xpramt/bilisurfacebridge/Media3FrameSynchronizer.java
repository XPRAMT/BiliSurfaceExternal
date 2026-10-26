package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Media3 VideoFrameReleaseHelper-style video output synchronizer for Bilibili's IJK path.
 *
 * <p>This keeps the IJK render decision unchanged, but replaces an immediate rendering release
 * with a timed release. The release timeline, VSync sampler, display-rate changes, API 33
 * FrameTimeline path, prediction reset, frame-index handling, and VSync hysteresis mirror the
 * corresponding Media3 helper behavior as closely as the IJK hook surface allows.</p>
 */
final class Media3FrameSynchronizer {
    private static final String TAG = "BiliSurfaceBridge";
    private static final long MAX_ALLOWED_ADJUSTMENT_NS = 20_000_000L;
    private static final long LINE_TOLERANCE_NS = 3_000_000L;
    // NX809J's SurfaceFlinger never deferred on desiredPresentTime, so the timestamp only
    // adds an unknown; the call phase alone decides the slot.
    private static final boolean RELEASE_WITH_TIMESTAMP = false;
    // Experiment: release every frame as soon as IJK decides (through the handler) to tell a
    // thread effect from a hold effect on decoder output lateness.
    private static final boolean ZERO_HOLD_EXPERIMENT = false;
    // Preferred mode: IJK's refresh loop is steered from the native compute_target_delay
    // hook so it calls releaseOutputBuffer at the slot's call phase by itself.  The Java
    // hold-and-release path below is the fallback when that hook is unavailable.
    private static final boolean NATIVE_RELEASE_SCHEDULING = true;
    private static volatile boolean nativeSchedulingActive;
    private static volatile boolean nativeSchedulingRequested;
    private static long nativePassThroughReleases;
    private static final long MAX_CLOCK_DISCONTINUITY_US = 500_000L;
    private static final long MIN_FRAME_DURATION_NS = 1_000_000L;
    private static final long MAX_FRAME_DURATION_NS = 50_000_000L;
    private static final long MAX_VSYNC_SAMPLE_AGE_NS = 100_000_000L;
    private static final long MIN_VSYNC_DURATION_NS = 4_000_000L;
    private static final long MAX_VSYNC_DURATION_NS = 34_000_000L;
    private static final long VSYNC_OFFSET_PERCENT = 80L;
    // Lead applied to IJK's "render now" decision before snapping to a VSync.  Measured
    // Queue->PresentFence latency on NX809J is 1.3-2.2 periods, so one period is not enough
    // for the buffer to reach the target VSync's latch.
    // IJK's own release decisions jitter by only +-0.6 ms when nothing is held back, so the
    // smoothing line only needs CALL_LEAD plus a small margin: holds then range from -0.33p
    // (still inside the slot window) to +0.67p and the decoder's output pool is not starved.
    private static final long RELEASE_LEAD_PERCENT = 230L;
    // A frame can still make its slot when the call lands up to half a period after the
    // ideal call phase (the queue window is one period wide), so only targets closer than
    // CALL_LEAD - 0.5p (+1 ms) to "now" are unreachable.  Holding buffers longer than that
    // starves the decoder's output pool: measured 22-24 ms decode stalls at 1.66p holds.
    private static final long MIN_HOLD_LEAD_PERCENT = 135L;
    private static final long MIN_HOLD_LEAD_MARGIN_NS = 1_000_000L;
    // Measured on NX809J: latch->present is 1.17p and the release call reaches the queue
    // ~0.18p later, so calling 2.0p before the target tick queues ~4.9 ms before the latch
    // (window is one period wide), leaving margin on both sides.
    private static final long CALL_LEAD_BEFORE_PRESENT_PERCENT = 220L;
    private static final long GRID_ANCHOR_TOLERANCE_NS = 1_000_000L;
    // Keeps desiredPresentTime strictly before the target expected-presentation tick so
    // SurfaceFlinger's "is due" comparison selects exactly that tick.
    private static final long DESIRED_PRESENT_MARGIN_NS = 1_000_000L;
    private static final long DEFAULT_RELEASE_LEAD_PERIOD_NS = 8_333_333L;
    private static final long MAX_TELEMETRY_FILE_BYTES = 512L * 1024L * 1024L;
    private static final int CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC = 15;
    private static final long MAX_MATCHING_FRAME_DIFFERENCE_NS = 1_000_000L;
    private static final long MAX_IJK_EARLY_US_SAMPLE_AGE_NS = 1_000_000_000L;
    private static final int IJK_EARLY_US_SAMPLE_FIELD_COUNT = 4;
    private static final long CADENCE_DURATION_CHANGE_TOLERANCE_NS = 200_000L;
    private static final long CADENCE_PHASE_CORRECTION_TOLERANCE_NS = 100_000L;
    private static final long RENDER_CALLBACK_BURST_WINDOW_NS = 1_000_000L;
    private static final int MAX_PENDING_RENDER_TIMINGS = 4096;
    private static final long TIME_UNSET = Long.MIN_VALUE;
    private static final long INDEX_UNSET = -1L;
    private static final String QTI_SW_FENCE =
            "vendor.qti-ext-output-sw-fence-enable.value";
    private static final String QTI_OUTPUT_FENCE = "vendor.qti-ext-output-fence.enable";
    private static final String QTI_OUTPUT_FENCE_TYPE = "vendor.qti-ext-output-fence.fence_type";
    private static final List<String> QTI_FENCE_KEYS;
    private static final Map<MediaCodec, CodecState> CODECS =
            Collections.synchronizedMap(new WeakHashMap<MediaCodec, CodecState>());
    private static final Map<MediaCodec, PendingRenderListener> PENDING_RENDER_LISTENERS =
            Collections.synchronizedMap(new WeakHashMap<MediaCodec, PendingRenderListener>());
    private static final Set<MediaCodec> ACTIVE_VIDEO_CODECS =
            Collections.newSetFromMap(new WeakHashMap<MediaCodec, Boolean>());

    static {
        List<String> keys = new ArrayList<String>(3);
        keys.add(QTI_SW_FENCE);
        keys.add(QTI_OUTPUT_FENCE);
        keys.add(QTI_OUTPUT_FENCE_TYPE);
        QTI_FENCE_KEYS = Collections.unmodifiableList(keys);
    }

    private static boolean installed;
    private static HandlerThread vsyncThread;
    private static Handler vsyncHandler;
    private static HandlerThread renderCallbackThread;
    private static Handler renderCallbackHandler;
    private static volatile VSyncSampler vsyncSampler;
    private static volatile String vsyncSamplerMode = "unavailable";
    private static long timedReleases;
    private static long predictedDeadlines;
    private static long deadlineResets;
    private static long clockReanchors;
    private static long clockLateFrames;
    private static long vsyncSnaps;
    private static long ijkEarlyUsAnchors;
    private static long ijkEarlyUsMisses;
    private static long ijkEarlyUsStaleSamples;
    private static long cadenceAnchors;
    private static long cadenceExtraVsyncs;
    private static long cadencePhaseCorrections;
    private static long cadenceFallbacks;
    private static long holdLeadBumps;
    private static long slotCollisionShifts;
    private static long gridAnchorRejects;
    private static long scheduledCallReleases;
    private static long lateCallReleases;
    private static long cancelledCallReleases;
    private static HandlerThread releaseThread;
    private static Handler releaseHandler;
    private static long qtiFenceConfigurations;
    private static long surfaceFrameRateHints;
    private static long lastReportNs;
    private static long lastTimedReleaseNs;
    private static final String TELEMETRY_SESSION_ID =
            Long.toHexString(System.currentTimeMillis()) + "-" + Process.myPid();
    private static volatile long latestPlaybackPositionUs = TIME_UNSET;
    private static volatile long latestPlaybackPositionSampleNs = TIME_UNSET;
    private static volatile String latestPlayerLifecycle = "unknown";
    private static volatile boolean playbackClockHooksInstalled;
    private static volatile TelemetryWriter telemetryWriter;
    private static volatile long telemetryDroppedRecords;
    private static final ThreadLocal<Boolean> INTERNAL_TIMED_RELEASE =
            new ThreadLocal<Boolean>() {
                @Override
                protected Boolean initialValue() {
                    return Boolean.FALSE;
                }
            };
    private static final ThreadLocal<Boolean> INTERNAL_RENDER_LISTENER_SETUP =
            new ThreadLocal<Boolean>() {
                @Override
                protected Boolean initialValue() {
                    return Boolean.FALSE;
                }
            };

    private Media3FrameSynchronizer() {
    }

    static synchronized String install() {
        if (installed) {
            return "Media3 frame-sync hooks already installed";
        }
        try {
            XposedHelpers.findAndHookMethod(MediaCodec.class, "configure", MediaFormat.class,
                    Surface.class, MediaCrypto.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            beforeConfigure((MediaCodec) param.thisObject,
                                    (MediaFormat) param.args[0]);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) {
                                logOnce("decoder-configure-failed:" + codecName(
                                                (MediaCodec) param.thisObject),
                                        "decoder configure failed; MIME is not reported as active");
                                return;
                            }
                            afterConfigure((MediaCodec) param.thisObject,
                                    (MediaFormat) param.args[0], (Surface) param.args[1]);
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod(MediaCodec.class, "setOnFrameRenderedListener",
                        MediaCodec.OnFrameRenderedListener.class, Handler.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (Boolean.TRUE.equals(INTERNAL_RENDER_LISTENER_SETUP.get())) {
                                    return;
                                }
                                beforeSetOnFrameRenderedListener(
                                        (MediaCodec) param.thisObject,
                                        (MediaCodec.OnFrameRenderedListener) param.args[0],
                                        (Handler) param.args[1], param);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-render-listener-hook",
                        "MediaCodec render callback hook unavailable: "
                                + throwable.getClass().getSimpleName());
            }
            XposedHelpers.findAndHookMethod(MediaCodec.class, "dequeueOutputBuffer",
                    MediaCodec.BufferInfo.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result instanceof Integer) {
                                onOutputDequeued((MediaCodec) param.thisObject, (Integer) result,
                                        (MediaCodec.BufferInfo) param.args[0]);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(MediaCodec.class, "start", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    recordCodecLifecycle((MediaCodec) param.thisObject, "start", null);
                }
            });
            XposedHelpers.findAndHookMethod(MediaCodec.class, "releaseOutputBuffer", int.class,
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(INTERNAL_TIMED_RELEASE.get())) {
                                return;
                            }
                            int index = (Integer) param.args[0];
                            boolean render = (Boolean) param.args[1];
                            if (releaseAtAdjustedTime((MediaCodec) param.thisObject, index, render)) {
                                param.setResult(null);
                            }
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod(MediaCodec.class, "releaseOutputBuffer", int.class,
                        long.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!Boolean.TRUE.equals(INTERNAL_TIMED_RELEASE.get())) {
                                    recordDirectTimedRelease((MediaCodec) param.thisObject,
                                            (Integer) param.args[0], (Long) param.args[1]);
                                }
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-direct-timed-release-hook",
                        "direct timed release telemetry unavailable: "
                                + throwable.getClass().getSimpleName());
            }
            try {
                XposedHelpers.findAndHookMethod(MediaCodec.class, "setOutputSurface", Surface.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                recordCodecLifecycle((MediaCodec) param.thisObject,
                                        "setOutputSurface", param.args[0]);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-surface-change-hook",
                        "Surface change telemetry unavailable: "
                                + throwable.getClass().getSimpleName());
            }
            XC_MethodHook resetHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MediaCodec codec = (MediaCodec) param.thisObject;
                    String methodName = param.method.getName();
                    CodecState state = CODECS.get(codec);
                    long nowNs = System.nanoTime();
                    long sinceLastTimedReleaseMs = lastTimedReleaseNs == 0 ? 0
                            : (nowNs - lastTimedReleaseNs) / 1_000_000L;
                    XposedBridge.log(TAG + ": [DEBUG-FRAME-LOCATE] codecLifecycle="
                            + methodName
                            + " codec=" + codecName(codec)
                            + " sinceLastTimedReleaseMs=" + sinceLastTimedReleaseMs
                            + " timed=" + timedReleases
                            + " predicted=" + predictedDeadlines
                            + " resets=" + deadlineResets
                            + " clockReanchors=" + clockReanchors
                            + " vsyncSnaps=" + vsyncSnaps
                            + " vsyncMode=" + vsyncSamplerMode);
                    // Pending timed releases refer to buffer indices that are invalid after
                    // flush/stop/release; drop them before the codec state changes.
                    cancelScheduledReleases(codec);
                    NativeRefreshCap.invalidateFrameTimer();
                    if ("flush".equals(methodName)) {
                        if (state != null) {
                            synchronized (state) {
                                state.resetPosition();
                            }
                        }
                    } else {
                        removeCodec(codec, state);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(MediaCodec.class, "flush", resetHook);
            XposedHelpers.findAndHookMethod(MediaCodec.class, "stop", resetHook);
            XposedHelpers.findAndHookMethod(MediaCodec.class, "release", resetHook);
            ensureTelemetryWriter();
            installed = true;
            return "installed Media3 telemetry hooks and QTI configure hooks";
        } catch (Throwable throwable) {
            return "Media3 frame-sync hooks unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    private static void beforeConfigure(MediaCodec codec, MediaFormat format) {
        if (!isVideo(format) || !experimentsEnabled() || !ModulePreferences.loadQtiOutputFenceForHook()
                || !isQtiDecoder(codec)) {
            return;
        }
        List<String> supported = supportedVendorParameters(codec);
        if (!supported.containsAll(QTI_FENCE_KEYS)) {
            logOnce("qti-fence-unsupported:" + codecName(codec),
                    "QTI output fence skipped; decoder does not declare all keys=" + supported);
            return;
        }
        format.setInteger(QTI_SW_FENCE, 1);
        format.setInteger(QTI_OUTPUT_FENCE, 1);
        format.setInteger(QTI_OUTPUT_FENCE_TYPE, 1);
        qtiFenceConfigurations++;
        logOnce("qti-fence-enabled:" + codecName(codec),
                "enabled QTI output software fence for decoder=" + codecName(codec));
    }

    private static void afterConfigure(MediaCodec codec, MediaFormat format, Surface surface) {
        String mime = format == null || !format.containsKey(MediaFormat.KEY_MIME)
                ? null : format.getString(MediaFormat.KEY_MIME);
        if (isAudio(format)) {
            DecoderFormatRelay.publishConfiguredAudio(mime, formatBitrate(format),
                    formatSampleRate(format), codecName(codec));
            return;
        }
        if (!isVideo(format)) {
            return;
        }
        DecoderFormatRelay.publishConfiguredVideo(mime, codecName(codec));
        if (surface != null && ModulePreferences.loadMedia3FrameSyncForHook()) {
            // Direct SurfaceView: this Surface is what SurfaceFlinger composes, so its
            // compositor timing is the scheduler's latch grid.
            logOnce("compositor-window:" + codecName(codec),
                    NativeRefreshCap.setCompositorWindow(surface));
        }
        CodecState state = new CodecState(surface);
        CODECS.put(codec, state);
        synchronized (ACTIVE_VIDEO_CODECS) {
            ACTIVE_VIDEO_CODECS.add(codec);
        }
        ensureTelemetryWriter();
        recordCodecLifecycle(codec, "configure", surface);
        if (experimentsEnabled()) {
            installRenderCallbackListener(codec, state);
        }
        if (experimentsEnabled() && ModulePreferences.loadMedia3FrameSyncForHook()) {
            ensureVsyncSampler();
            requestVsyncRegistration();
        }
    }

    private static void beforeSetOnFrameRenderedListener(
            MediaCodec codec,
            MediaCodec.OnFrameRenderedListener applicationListener,
            Handler applicationHandler,
            XC_MethodHook.MethodHookParam param) {
        if (!experimentsEnabled()) {
            return;
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            PENDING_RENDER_LISTENERS.put(codec,
                    new PendingRenderListener(applicationListener, applicationHandler));
            return;
        }
        final long listenerGeneration;
        final MediaCodec.OnFrameRenderedListener wrapper;
        synchronized (state) {
            if (applicationListener == state.renderListenerWrapper) {
                return;
            }
            state.applicationRenderListener = applicationListener;
            listenerGeneration = ++state.renderListenerGeneration;
            wrapper = createRenderListener(codec, state, applicationListener, listenerGeneration);
            state.renderListenerWrapper = wrapper;
        }
        param.args[0] = wrapper;
        recordRenderListenerLifecycle(codec, "setOnFrameRenderedListener", applicationListener,
                applicationHandler, listenerGeneration);
    }

    private static void installRenderCallbackListener(MediaCodec codec, CodecState state) {
        PendingRenderListener pending = PENDING_RENDER_LISTENERS.remove(codec);
        MediaCodec.OnFrameRenderedListener applicationListener =
                pending == null ? null : pending.listener;
        Handler handler = pending == null || pending.handler == null
                ? ensureRenderCallbackHandler() : pending.handler;
        if (handler == null) {
            recordRenderListenerLifecycle(codec, "renderListenerInstallFailed", null, null,
                    state.renderListenerGeneration);
            return;
        }
        final long listenerGeneration;
        final MediaCodec.OnFrameRenderedListener wrapper;
        synchronized (state) {
            state.applicationRenderListener = applicationListener;
            listenerGeneration = ++state.renderListenerGeneration;
            wrapper = createRenderListener(codec, state, applicationListener, listenerGeneration);
            state.renderListenerWrapper = wrapper;
        }
        try {
            INTERNAL_RENDER_LISTENER_SETUP.set(Boolean.TRUE);
            codec.setOnFrameRenderedListener(wrapper, handler);
            recordRenderListenerLifecycle(codec, "renderListenerInstall", applicationListener, handler,
                    listenerGeneration);
        } catch (Throwable throwable) {
            recordRenderListenerLifecycle(codec, "renderListenerInstallFailed", applicationListener, handler,
                    listenerGeneration);
            logOnce("media3-render-listener-install:" + codecName(codec),
                    "MediaCodec render callback registration failed="
                            + throwable.getClass().getSimpleName());
        } finally {
            INTERNAL_RENDER_LISTENER_SETUP.set(Boolean.FALSE);
        }
    }

    private static MediaCodec.OnFrameRenderedListener createRenderListener(
            final MediaCodec codec,
            final CodecState state,
            final MediaCodec.OnFrameRenderedListener applicationListener,
            final long listenerGeneration) {
        return new MediaCodec.OnFrameRenderedListener() {
            @Override
            public void onFrameRendered(MediaCodec callbackCodec, long presentationTimeUs,
                                        long nanoTime) {
                if (nativeSchedulingActive) {
                    NativeRefreshCap.notePresent(nanoTime);
                }
                try {
                    recordFrameRendered(codec, state, listenerGeneration, presentationTimeUs,
                            nanoTime);
                } catch (Throwable throwable) {
                    logOnce("media3-render-callback:" + codecName(codec),
                            "render callback telemetry failed="
                                    + throwable.getClass().getSimpleName());
                }
                if (applicationListener != null) {
                    try {
                        applicationListener.onFrameRendered(callbackCodec, presentationTimeUs,
                                nanoTime);
                    } catch (Throwable throwable) {
                        logOnce("media3-render-listener-forward:" + codecName(codec),
                                "original render callback failed="
                                        + throwable.getClass().getSimpleName());
                    }
                }
            }
        };
    }

    private static synchronized Handler ensureRenderCallbackHandler() {
        if (renderCallbackHandler != null) {
            return renderCallbackHandler;
        }
        try {
            renderCallbackThread = new HandlerThread("Bili-Media3-RenderCallback",
                    Process.THREAD_PRIORITY_DISPLAY);
            renderCallbackThread.start();
            renderCallbackHandler = new Handler(renderCallbackThread.getLooper());
            return renderCallbackHandler;
        } catch (Throwable throwable) {
            renderCallbackThread = null;
            renderCallbackHandler = null;
            logOnce("media3-render-callback-handler",
                    "render callback handler unavailable="
                            + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static void onOutputDequeued(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        if (index < 0 || info == null) {
            return;
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            return;
        }
        long dequeuedAtNs = System.nanoTime();
        FrameTiming timing;
        synchronized (state) {
            timing = new FrameTiming(index, info.presentationTimeUs, state.nextFrameIndex++,
                    dequeuedAtNs, state.renderGeneration);
            state.presentationTimes.put(index, timing);
            state.addRenderCandidate(timing);
            state.frameRateEstimator.onNextFrame(info.presentationTimeUs * 1_000L);
            long estimatedFrameDurationNs = state.frameRateEstimator.getFrameDurationNs();
            if (estimatedFrameDurationNs != TIME_UNSET) {
                state.frameDurationNs = estimatedFrameDurationNs;
                maybeSetSurfaceFrameRate(state);
            }
        }
        recordFrameEvent(codec, index, timing, dequeuedAtNs, TIME_UNSET, TIME_UNSET,
                TIME_UNSET, TIME_UNSET, TIME_UNSET, "DEQUEUED", true,
                "dequeueOutputBuffer", "");
    }

    private static void recordFrameRendered(MediaCodec codec, CodecState state,
                                            long listenerGeneration, long presentationTimeUs,
                                            long renderedNanoTimeNs) {
        long callbackObservedNs = System.nanoTime();
        FrameTiming timing = null;
        int pendingBefore = 0;
        long callbackCount = 0L;
        long exactMatchCount = 0L;
        long unmatchedCount = 0L;
        long burstCount = 0L;
        long codecRenderGeneration = TIME_UNSET;
        synchronized (state) {
            pendingBefore = state.pendingRenderTimingCount();
            timing = state.takeRenderCandidate(presentationTimeUs, renderedNanoTimeNs);
            state.renderCallbackCount++;
            callbackCount = state.renderCallbackCount;
            if (timing == null) {
                state.renderCallbackUnmatchedCount++;
                unmatchedCount = state.renderCallbackUnmatchedCount;
            } else {
                state.renderCallbackExactMatchCount++;
                exactMatchCount = state.renderCallbackExactMatchCount;
                timing.renderedNanoTimeNs = renderedNanoTimeNs;
                timing.renderCallbackObservedNs = callbackObservedNs;
                timing.renderListenerGeneration = listenerGeneration;
                codecRenderGeneration = timing.renderGeneration;
            }
            if (state.lastRenderCallbackObservedNs != TIME_UNSET
                    && callbackObservedNs - state.lastRenderCallbackObservedNs
                    <= RENDER_CALLBACK_BURST_WINDOW_NS) {
                state.renderCallbackBurstCount++;
            }
            state.lastRenderCallbackObservedNs = callbackObservedNs;
            state.lastRenderCallbackNanoTimeNs = renderedNanoTimeNs;
            burstCount = state.renderCallbackBurstCount;
        }
        if (timing == null) {
            timing = new FrameTiming(-1, presentationTimeUs, INDEX_UNSET, TIME_UNSET,
                    codecRenderGeneration);
        }
        long callbackDispatchDelayNs = renderedNanoTimeNs <= 0L
                ? TIME_UNSET : callbackObservedNs - renderedNanoTimeNs;
        String match = timing.frameIndex == INDEX_UNSET ? "PTS_UNMATCHED" : "PTS_EXACT";
        String detail = "callbackNanoTimeNs=" + renderedNanoTimeNs
                + ";callbackObservedNs=" + callbackObservedNs
                + ";callbackDispatchDelayNs=" + callbackDispatchDelayNs
                + ";renderMatch=" + match
                + ";renderListenerGeneration=" + listenerGeneration
                + ";codecRenderGeneration=" + timing.renderGeneration
                + ";renderCallbackCount=" + callbackCount
                + ";renderCallbackExactMatchCount=" + exactMatchCount
                + ";renderCallbackUnmatchedCount=" + unmatchedCount
                + ";renderCallbackBurstCount=" + burstCount
                + ";pendingRenderFramesBefore=" + pendingBefore;
        recordFrameEvent(codec, timing.bufferIndex, timing, callbackObservedNs,
                currentPlaybackPositionUs(callbackObservedNs),
                playbackPositionAgeMs(callbackObservedNs),
                state.estimatedPositionUs(callbackObservedNs), TIME_UNSET,
                timing.scheduledReleaseNs, "FRAME_RENDERED", true,
                "MediaCodec.OnFrameRenderedListener", detail);
    }

    private static boolean releaseAtAdjustedTime(MediaCodec codec, int index, boolean render) {
        if (!render || !experimentsEnabled() || !ModulePreferences.loadMedia3FrameSyncForHook()) {
            recordReleaseRequest(codec, index, render, "PASS_THROUGH", "feature-disabled-or-not-render");
            return false;
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            recordReleaseRequest(codec, index, render, "PASS_THROUGH", "codec-state-missing");
            return false;
        }
        ensureVsyncSampler();
        requestVsyncRegistration();
        ensureNativeScheduling();
        final FrameTiming timing;
        synchronized (state) {
            timing = state.presentationTimes.remove(index);
            if (timing == null) {
                recordReleaseRequest(codec, index, render, "PASS_THROUGH", "frame-timing-missing");
                return false;
            }
        }
        long nowNs = System.nanoTime();
        if (nativeSchedulingActive) {
            // IJK's refresh loop already woke at the slot's call phase; every microsecond
            // spent here before the real release eats the Queue->Latch margin, so release
            // first and keep the bookkeeping afterwards.
            nativePassThroughReleases++;
            boolean fired;
            // Media3 mode: the scheduler picked a release timestamp (closest VSync - 80 % of a
            // period) and woke IJK 45 ms ahead of it.  Every path to SurfaceFlinger goes through
            // one more GL pass, so the timestamp has to reach that pass:
            //  - direct Surface: IJK's own GL redraw on this thread; taking the timestamp arms
            //    the native hook on its eglSwapBuffers;
            //  - Surface external: the codec renders into the main process's SurfaceTexture, so
            //    the buffer carries it (SurfaceTexture.getTimestamp()) to ijk_ext_render, whose
            //    swap is timestamped there (ExternalRenderTimestamps).
            long releaseTs = NativeRefreshCap.takeReleaseTimestamp();
            try {
                INTERNAL_TIMED_RELEASE.set(Boolean.TRUE);
                if (releaseTs > 0L) {
                    codec.releaseOutputBuffer(index, releaseTs);
                } else if (releaseTs == -2L) {
                    // Media3 skip: content faster than the panel, this VSync is taken.
                    codec.releaseOutputBuffer(index, false);
                } else {
                    codec.releaseOutputBuffer(index, true);
                }
                fired = true;
            } catch (Throwable throwable) {
                fired = false;
                logOnce("native-release-failed:" + codecName(codec),
                        "native-scheduled release failed=" + throwable.getClass().getSimpleName());
            } finally {
                INTERNAL_TIMED_RELEASE.set(Boolean.FALSE);
            }
            NativeRefreshCap.noteDisplay(nowNs);
            long afterNs = System.nanoTime();
            synchronized (state) {
                timing.releaseCallNs = nowNs;
                timing.scheduledReleaseNs = nowNs;
            }
            timedReleases++;
            lastTimedReleaseNs = nowNs;
            recordFrameEvent(codec, index, timing, nowNs, TIME_UNSET, TIME_UNSET, TIME_UNSET,
                    nowNs, nowNs, "TIMED_RELEASE_FIRED", render, "releaseOutputBuffer(bool)",
                    "reason=native;releaseTs=" + releaseTs
                            + ";releaseCallDurationNs=" + (afterNs - nowNs)
                            + ";nativeScheduling=true;nativePassThroughReleases="
                            + nativePassThroughReleases);
            maybeReport(nowNs);
            return fired;
        }
        IjkEarlyUsTiming ijkEarlyUsTiming = readIjkEarlyUsTiming(
                timing.presentationTimeUs, nowNs);
        boolean fixedCadenceEnabled = FIXED_CADENCE_COMPENSATION;
        VSyncSampler sampler = vsyncSampler;
        long sampledVsyncTimeNs = sampler == null
                ? TIME_UNSET : sampler.effectiveVsyncAnchorTimeNs(nowNs);
        long vsyncDurationNs = sampler == null ? TIME_UNSET : sampler.vsyncDurationNs;
        long releaseTimeNs;
        long clockReleaseTimeNs;
        long estimatedPositionUs;
        long playbackPositionUs;
        long playbackPositionAgeMs;
        CadenceTiming cadenceTiming = null;
        long ptsClockReleaseTimeNs;
        long releaseLeadNs = releaseLeadNs(vsyncDurationNs);
        synchronized (state) {
            // The PTS-anchored clock is telemetry only.  It is pinned to the first release and
            // never re-synchronises after a stall, so after a few seconds every timestamp it
            // produces lies in the past and SurfaceFlinger presents the buffer immediately,
            // which is indistinguishable from an untimed release.
            ptsClockReleaseTimeNs = state.videoClockReleaseTimeNs(timing, nowNs);
            if (ijkEarlyUsTiming != null) {
                clockReleaseTimeNs = ijkEarlyUsTiming.releaseTimeNs;
                state.anchorNativeClock(timing, clockReleaseTimeNs);
                ijkEarlyUsAnchors++;
            } else {
                // IJK's refresh loop has already waited until this frame is due on the audio
                // clock, so "now" is the frame's intended display time.  Add a fixed lead so
                // the snapped target VSync is still reachable after SurfaceFlinger's latch
                // deadline; the adjustment below removes the loop's wake-up jitter.
                clockReleaseTimeNs = nowNs + releaseLeadNs;
            }
            if (clockReleaseTimeNs < nowNs) {
                clockLateFrames++;
            }
            if (fixedCadenceEnabled) {
                cadenceTiming = state.fixedCadenceReleaseTime(timing, clockReleaseTimeNs, nowNs,
                        sampledVsyncTimeNs, vsyncDurationNs);
            }
            if (cadenceTiming != null) {
                releaseTimeNs = cadenceTiming.releaseTimeNs;
            } else {
                if (fixedCadenceEnabled) {
                    cadenceFallbacks++;
                }
                releaseTimeNs = adjustedReleaseTime(state, timing, clockReleaseTimeNs, nowNs);
            }
            estimatedPositionUs = state.estimatedPositionUs(nowNs);
        }
        playbackPositionUs = currentPlaybackPositionUs(nowNs);
        playbackPositionAgeMs = playbackPositionAgeMs(nowNs);
        long earlyUs = playbackPositionUs == TIME_UNSET
                ? TIME_UNSET : timing.presentationTimeUs - playbackPositionUs;
        String ijkEarlyUsDetail = ijkEarlyUsTiming == null
                ? "ijkEarlyUsAnchor=unavailable"
                : "ijkEarlyUsAnchor=active"
                        + ";ijkEarlyUsPtsUs=" + ijkEarlyUsTiming.matchedPtsUs
                        + ";ijkEarlyUsNanos=" + ijkEarlyUsTiming.earlyUsNanos
                        + ";ijkEarlyUsFilterDelayNanos="
                        + ijkEarlyUsTiming.filterDelayNanos
                        + ";ijkEarlyUsSampleNs=" + ijkEarlyUsTiming.sampleTimestampNs
                        + ";ijkEarlyUsAgeMs=" + ijkEarlyUsTiming.ageNs / 1_000_000L
                        + ";ijkEarlyUsReleaseNs=" + ijkEarlyUsTiming.releaseTimeNs;
        String cadenceDetail = cadenceTiming == null
                ? "fixedCadence=unavailable"
                : "fixedCadence=active"
                        + ";cadenceExtraVsyncs=" + cadenceTiming.extraVsyncs
                        + ";cadenceCorrectionIntervalFrames="
                        + cadenceTiming.correctionIntervalFrames
                        + ";cadencePhaseCorrections=" + cadencePhaseCorrections
                        + ";cadenceFrameDurationNs=" + cadenceTiming.frameDurationNs
                        + ";cadenceVsyncDurationNs=" + cadenceTiming.vsyncDurationNs;
        long samplerAgeNs = sampledVsyncAgeNs(sampler, nowNs);
        String schedulerDetail = "decisionNowNs=" + nowNs
                + ";clockReleaseTimeNs=" + clockReleaseTimeNs
                + ";ptsClockReleaseNs=" + ptsClockReleaseTimeNs
                + ";releaseLeadNs=" + releaseLeadNs
                + ";gridAnchorNs=" + state.gridAnchorNs
                + ";gridResidualNs=" + state.lastGridResidualNs
                + ";holdBumpPeriods=" + state.lastHoldBumpPeriods
                + ";holdLeadBumps=" + holdLeadBumps
                + ";slotShift=" + state.lastSlotCollisions
                + ";slotCollisionShifts=" + slotCollisionShifts
                + ";gridAnchorRejects=" + gridAnchorRejects
                + ";nativeScheduling=" + nativeSchedulingActive
                + ";nativePassThroughReleases=" + nativePassThroughReleases
                + ";scheduledCallReleases=" + scheduledCallReleases
                + ";lateCallReleases=" + lateCallReleases
                + ";cancelledCallReleases=" + cancelledCallReleases
                + ";scheduledReleaseNs=" + releaseTimeNs
                + ";clockReleaseMinusDecisionNs="
                + differenceOrUnset(clockReleaseTimeNs, nowNs)
                + ";scheduledReleaseMinusDecisionNs="
                + differenceOrUnset(releaseTimeNs, nowNs)
                + ";scheduledReleaseMinusClockReleaseNs="
                + differenceOrUnset(releaseTimeNs, clockReleaseTimeNs)
                + ";chosenTargetVsyncNs="
                + (cadenceTiming == null ? TIME_UNSET : cadenceTiming.targetVsyncTimeNs)
                + ";sampledVsyncAgeNs=" + samplerAgeNs
                + ";playbackPositionAvailable=" + (playbackPositionUs != TIME_UNSET)
                + ";playbackPositionAgeMs=" + playbackPositionAgeMs;
        if (cadenceTiming != null) {
            schedulerDetail += ";cadenceAnchorFrameIndex=" + cadenceTiming.anchorFrameIndex
                    + ";cadenceAnchorPtsUs=" + cadenceTiming.anchorPresentationTimeUs
                    + ";cadenceAnchorVsyncNs=" + cadenceTiming.anchorVsyncTimeNs
                    + ";cadenceFrameDelta=" + cadenceTiming.frameDelta
                    + ";cadenceFrameSlot=" + cadenceTiming.frameSlot
                    + ";cadenceDriftNs=" + cadenceTiming.driftNs
                    + ";cadencePhaseErrorNs=" + cadenceTiming.phaseErrorNs
                    + ";cadenceReanchored=" + cadenceTiming.reanchored
                    + ";cadenceReanchorReason=" + cadenceTiming.reanchorReason;
        }
        synchronized (state) {
            timing.releaseCallNs = nowNs;
            timing.unadjustedReleaseNs = clockReleaseTimeNs;
            timing.scheduledReleaseNs = releaseTimeNs;
        }
        recordFrameEvent(codec, index, timing, nowNs, playbackPositionUs, playbackPositionAgeMs,
                estimatedPositionUs, clockReleaseTimeNs, releaseTimeNs,
                "SCHEDULED_TIMED_RELEASE", render, "releaseOutputBuffer(bool->time)",
                "earlyUs=" + earlyUs
                        + ";lifecycle=" + latestPlayerLifecycle
                        + ";" + ijkEarlyUsDetail
                        + ";" + cadenceDetail
                        + ";" + schedulerDetail);
        // SurfaceFlinger on NX809J never deferred a buffer on desiredPresentTime (no
        // "not current desiredPresentTime" commit in any trace), so the present slot is
        // decided purely by when the buffer is queued.  Hold the frame and issue the release
        // call itself at a fixed phase before the target tick instead of trusting the
        // timestamp.
        long targetTickNs = releaseTimeNs + DESIRED_PRESENT_MARGIN_NS;
        long periodNs = vsyncDurationNs >= MIN_VSYNC_DURATION_NS
                && vsyncDurationNs <= MAX_VSYNC_DURATION_NS
                ? vsyncDurationNs : DEFAULT_RELEASE_LEAD_PERIOD_NS;
        long callTimeNs = targetTickNs - (periodNs * CALL_LEAD_BEFORE_PRESENT_PERCENT) / 100L;
        // Every release goes through the single release handler, whose queue is ordered by
        // call time, so a late frame can never overtake an earlier frame that is still
        // waiting for its slot.  Late frames are posted for "now".
        boolean late = ZERO_HOLD_EXPERIMENT || callTimeNs <= nowNs;
        if (late) {
            lateCallReleases++;
        }
        if (scheduleTimedRelease(codec, index, timing, releaseTimeNs,
                late ? nowNs : callTimeNs, late ? "immediate" : "scheduled")) {
            scheduledCallReleases++;
            return true;
        }
        return fireTimedRelease(codec, index, timing, releaseTimeNs, nowNs, "no-scheduler");
    }

    /** Calls the real release now; returns true when the original call must be swallowed. */
    private static boolean fireTimedRelease(MediaCodec codec, int index, FrameTiming timing,
                                            long releaseTimeNs, long decisionNowNs,
                                            String reason) {
        long nowNs = System.nanoTime();
        try {
            INTERNAL_TIMED_RELEASE.set(Boolean.TRUE);
            if (RELEASE_WITH_TIMESTAMP) {
                codec.releaseOutputBuffer(index, releaseTimeNs);
            } else {
                codec.releaseOutputBuffer(index, true);
            }
            timedReleases++;
            lastTimedReleaseNs = nowNs;
            recordFrameEvent(codec, index, timing, nowNs, TIME_UNSET, TIME_UNSET, TIME_UNSET,
                    timing.unadjustedReleaseNs, releaseTimeNs, "TIMED_RELEASE_FIRED", true,
                    "releaseOutputBuffer(time)", "reason=" + reason
                            + ";callLatenessNs=" + (timing.scheduledCallNs == TIME_UNSET
                                    ? TIME_UNSET : nowNs - timing.scheduledCallNs)
                            + ";fireMinusDecisionNs=" + (nowNs - decisionNowNs)
                            + ";fireToTargetTickNs="
                            + (releaseTimeNs + DESIRED_PRESENT_MARGIN_NS - nowNs));
            maybeReport(nowNs);
            return true;
        } catch (Throwable throwable) {
            recordFrameEvent(codec, index, timing, System.nanoTime(), TIME_UNSET, TIME_UNSET,
                    TIME_UNSET, timing.unadjustedReleaseNs, releaseTimeNs,
                    "TIMED_RELEASE_FAILED", true, "releaseOutputBuffer(time)",
                    throwable.getClass().getSimpleName() + ";reason=" + reason);
            logOnce("timed-release-failed:" + codecName(codec),
                    "timed MediaCodec release failed=" + throwable.getClass().getSimpleName());
            return true;
        } finally {
            INTERNAL_TIMED_RELEASE.set(Boolean.FALSE);
        }
    }

    private static synchronized void ensureReleaseScheduler() {
        if (releaseThread != null) {
            return;
        }
        releaseThread = new HandlerThread("Bili-Media3-Release",
                Process.THREAD_PRIORITY_URGENT_DISPLAY);
        releaseThread.start();
        releaseHandler = new Handler(releaseThread.getLooper());
    }

    private static boolean scheduleTimedRelease(final MediaCodec codec, final int index,
                                                final FrameTiming timing,
                                                final long releaseTimeNs, long callTimeNs,
                                                final String reason) {
        ensureReleaseScheduler();
        Handler handler = releaseHandler;
        if (handler == null) {
            return false;
        }
        final long decisionNowNs = System.nanoTime();
        timing.scheduledCallNs = callTimeNs;
        // uptimeMillis and nanoTime are both CLOCK_MONOTONIC; Handler timing is millisecond
        // granular, so round up to avoid firing a fraction early into the previous slot.
        long delayMs = (callTimeNs - decisionNowNs + 999_999L) / 1_000_000L;
        long uptimeMs = android.os.SystemClock.uptimeMillis() + delayMs;
        return handler.postAtTime(new Runnable() {
            @Override
            public void run() {
                if (CODECS.get(codec) == null) {
                    cancelledCallReleases++;
                    return;
                }
                fireTimedRelease(codec, index, timing, releaseTimeNs, decisionNowNs, reason);
            }
        }, codec, uptimeMs);
    }

    private static void cancelScheduledReleases(MediaCodec codec) {
        Handler handler = releaseHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(codec);
        }
    }

    private static long nativeSchedulingLastAttemptNs;

    private static long nativeGridAnchorNs = TIME_UNSET;

    /** Sampler thread: keep the native scheduler's grid fresh without touching IJK's thread. */
    private static void publishNativeGridSample(long expectedPresentationNs, long periodNs,
                                                long vsyncNs, long deadlineNs) {
        if (!nativeSchedulingActive || expectedPresentationNs == TIME_UNSET
                || periodNs < MIN_VSYNC_DURATION_NS || periodNs > MAX_VSYNC_DURATION_NS) {
            return;
        }
        if (nativeGridAnchorNs == TIME_UNSET) {
            nativeGridAnchorNs = expectedPresentationNs;
        } else {
            long residualNs = Math.floorMod(expectedPresentationNs - nativeGridAnchorNs, periodNs);
            if (residualNs > periodNs / 2L) {
                residualNs -= periodNs;
            }
            if (Math.abs(residualNs) <= GRID_ANCHOR_TOLERANCE_NS) {
                nativeGridAnchorNs = expectedPresentationNs;
            }
        }
        NativeRefreshCap.setVsyncGrid(nativeGridAnchorNs, periodNs, vsyncNs, deadlineNs);
    }

    private static synchronized void ensureNativeScheduling() {
        if (!NATIVE_RELEASE_SCHEDULING || nativeSchedulingActive) {
            return;
        }
        // The IJK refresh hooks are installed when the player service starts; the first
        // release can arrive earlier, so keep retrying (once per second) until the hook is up.
        long nowNs = System.nanoTime();
        if (nativeSchedulingRequested && nowNs - nativeSchedulingLastAttemptNs < 1_000_000_000L) {
            return;
        }
        nativeSchedulingRequested = true;
        nativeSchedulingLastAttemptNs = nowNs;
        // Direct SurfaceView: queue mid-way in SurfaceFlinger's latch window (2.2 periods).
        // External renderers redraw on the app's Choreographer VSync, so the frame must reach
        // the SurfaceTexture half a period before that callback instead (2.7 periods).
        ModulePreferences.RendererMode rendererMode =
                ModulePreferences.loadVideoDetailRendererModeForHook();
        int callLeadPercent = rendererMode == ModulePreferences.RendererMode.SURFACE
                ? NATIVE_CALL_LEAD_DIRECT_PERCENT
                : rendererMode == ModulePreferences.RendererMode.TEXTURE_EXTERNAL
                ? NATIVE_CALL_LEAD_TEXTURE_PERCENT : NATIVE_CALL_LEAD_EXTERNAL_PERCENT;
        // External renderers: target the arrival mid-way between two app VSyncs directly (the
        // app-VSync-to-present distance is fixed in ms, so a lead in periods only fits one
        // refresh rate: 60 fps content switches the panel to 60 Hz).
        // Direct SurfaceView: queue mid-way in SurfaceFlinger's latch window, derived from the
        // FrameTimeline deadline instead of a device-specific lead in periods.
        // Surface external draws immediately on arrival (ExternalRenderImmediate), so its GL
        // output is scheduled into SurfaceFlinger's latch window like a direct SurfaceView
        // buffer; Texture keeps the app-VSync arrival grid (HWUI consumes on the app VSync).
        // SurfaceView paths follow the Media3 release model; Texture composes through HWUI,
        // whose window buffer carries no per-frame timestamp, so it keeps the arrival grid.
        int gridMode = rendererMode == ModulePreferences.RendererMode.TEXTURE_EXTERNAL
                ? NativeRefreshCap.GRID_ARRIVAL : NativeRefreshCap.GRID_MEDIA3;
        NativeRefreshCap.setMedia3SkipSameVsync(
                rendererMode == ModulePreferences.RendererMode.SURFACE_EXTERNAL);
        String result = NativeRefreshCap.setReleaseScheduling(true, callLeadPercent, gridMode);
        nativeSchedulingActive = result != null && result.contains("active");
        logOnce("native-release-scheduling:" + nativeSchedulingActive, result
                + " (java fallback=" + (nativeSchedulingActive ? "off" : "on") + ")");
    }

    private static final int NATIVE_CALL_LEAD_DIRECT_PERCENT = 220;
    private static final int NATIVE_CALL_LEAD_EXTERNAL_PERCENT = 245;
    // Texture: the GL thread draws immediately on arrival (ExternalRenderImmediate) and the
    // TextureView is redrawn on every UI VSync (TextureViewVsyncInvalidator), so the GL output
    // only has to sit mid-way between two RenderThread updateTexImage calls: same phase as
    // Surface external.
    private static final int NATIVE_CALL_LEAD_TEXTURE_PERCENT = 245;

    private static long releaseLeadNs(long vsyncDurationNs) {
        long periodNs = vsyncDurationNs >= MIN_VSYNC_DURATION_NS
                && vsyncDurationNs <= MAX_VSYNC_DURATION_NS
                ? vsyncDurationNs : DEFAULT_RELEASE_LEAD_PERIOD_NS;
        return (periodNs * RELEASE_LEAD_PERCENT) / 100L;
    }

    private static long adjustedReleaseTime(CodecState state, FrameTiming timing,
                                             long unadjustedReleaseTimeNs, long nowNs) {
        if (timing.presentationTimeUs != state.pendingLastPresentationTimeUs) {
            state.lastAdjustedFrameIndex = state.pendingLastAdjustedFrameIndex;
            state.lastAdjustedReleaseTimeNs = state.pendingLastAdjustedReleaseTimeNs;
            state.lastAdjustedPresentationTimeUs = state.pendingLastPresentationTimeUs;
            state.lastVsyncHysteresisOffsetNs = state.pendingVsyncHysteresisOffsetNs;
        }

        long adjustedReleaseTimeNs = unadjustedReleaseTimeNs;
        if (state.lastAdjustedFrameIndex != INDEX_UNSET) {
            long elapsedReleaseTimeSinceLastFrameNs;
            if (state.frameDurationNs != TIME_UNSET) {
                long frameCount = timing.frameIndex - state.lastAdjustedFrameIndex;
                elapsedReleaseTimeSinceLastFrameNs = state.frameDurationNs * frameCount;
            } else {
                elapsedReleaseTimeSinceLastFrameNs =
                        (timing.presentationTimeUs - state.lastAdjustedPresentationTimeUs) * 1_000L;
            }
            long candidateAdjustedReleaseTimeNs =
                    state.lastAdjustedReleaseTimeNs + elapsedReleaseTimeSinceLastFrameNs;
            if (adjustmentAllowed(unadjustedReleaseTimeNs, candidateAdjustedReleaseTimeNs)) {
                adjustedReleaseTimeNs = candidateAdjustedReleaseTimeNs;
                predictedDeadlines++;
            } else {
                state.resetAdjustment();
            }
        }
        state.pendingLastAdjustedFrameIndex = timing.frameIndex;
        state.pendingLastAdjustedReleaseTimeNs = adjustedReleaseTimeNs;
        state.pendingLastPresentationTimeUs = timing.presentationTimeUs;

        VSyncSampler sampler = vsyncSampler;
        if (sampler == null) {
            return adjustedReleaseTimeNs;
        }
        // SurfaceFlinger holds a buffer only while desiredPresentTime is later than the
        // expectedPresentTime of the frame it is composing, so the grid to snap to is the
        // FrameTimeline expected-presentation grid, not the Choreographer frame-time grid that
        // Media3 uses with its 80% offset.  With that offset the timestamp is already due at
        // the previous composition and the buffer is shown as soon as it is queued, which
        // measured identically to an untimed release on NX809J.
        long vsyncDurationNs = sampler.vsyncDurationNs;
        long gridAnchorNs = state.updateGridAnchor(sampler.expectedPresentationTimeNs,
                vsyncDurationNs);
        if (gridAnchorNs == TIME_UNSET) {
            return adjustedReleaseTimeNs;
        }
        if (nativeSchedulingActive) {
            NativeRefreshCap.setVsyncGrid(gridAnchorNs, vsyncDurationNs,
                    sampler.sampledVsyncTimeNs, sampler.deadlineNs);
        }
        long snappedTimeNs = findClosestVsyncAndUpdateHysteresis(state, adjustedReleaseTimeNs,
                gridAnchorNs, vsyncDurationNs);
        vsyncSnaps++;
        // Slots must be strictly increasing: a re-anchored line may otherwise snap the next
        // frame onto the slot already taken by the previous one and SurfaceFlinger drops one.
        state.lastSlotCollisions = 0L;
        if (state.lastTargetTickNs != TIME_UNSET && snappedTimeNs <= state.lastTargetTickNs) {
            long shiftPeriods = (state.lastTargetTickNs - snappedTimeNs) / vsyncDurationNs + 1L;
            snappedTimeNs += shiftPeriods * vsyncDurationNs;
            state.pendingLastAdjustedReleaseTimeNs += shiftPeriods * vsyncDurationNs;
            state.lastSlotCollisions = shiftPeriods;
            slotCollisionShifts++;
        }
        // SurfaceFlinger commits about 1.25 periods before each present, so a target closer
        // than ~2.5 periods to "now" is already due at the next commit and is shown as soon
        // as it is queued.  Push such targets out by whole periods and move the smoothing
        // line with them so the following frames keep their spacing instead of all bumping.
        long minTargetNs = nowNs + (vsyncDurationNs * MIN_HOLD_LEAD_PERCENT) / 100L
                + MIN_HOLD_LEAD_MARGIN_NS;
        state.lastHoldBumpPeriods = 0L;
        if (snappedTimeNs < minTargetNs) {
            long bumpPeriods = (minTargetNs - snappedTimeNs + vsyncDurationNs - 1L)
                    / vsyncDurationNs;
            snappedTimeNs += bumpPeriods * vsyncDurationNs;
            state.pendingLastAdjustedReleaseTimeNs += bumpPeriods * vsyncDurationNs;
            state.lastHoldBumpPeriods = bumpPeriods;
            holdLeadBumps++;
        }
        state.lastTargetTickNs = snappedTimeNs;
        return snappedTimeNs - DESIRED_PRESENT_MARGIN_NS;
    }

    private static boolean adjustmentAllowed(long unadjustedReleaseTimeNs,
                                             long adjustedReleaseTimeNs) {
        // Media3 tolerates 20 ms here, but IJK's decisions jitter by only +-0.6 ms, and a
        // wide tolerance lets the line run ahead of late (decoder-starved) decisions, which
        // lengthens the hold and starves the decoder further.  Re-anchor early instead.
        return Math.abs(unadjustedReleaseTimeNs - adjustedReleaseTimeNs)
                <= LINE_TOLERANCE_NS;
    }

    private static long findClosestVsyncAndUpdateHysteresis(CodecState state, long releaseTimeNs,
                                                             long sampledVsyncTimeNs,
                                                             long vsyncDurationNs) {
        long vsyncCount = (releaseTimeNs - sampledVsyncTimeNs) / vsyncDurationNs;
        long snappedTimeNs = sampledVsyncTimeNs + vsyncDurationNs * vsyncCount;
        long snappedBeforeNs;
        long snappedAfterNs;
        if (releaseTimeNs <= snappedTimeNs) {
            snappedBeforeNs = snappedTimeNs - vsyncDurationNs;
            snappedAfterNs = snappedTimeNs;
        } else {
            snappedBeforeNs = snappedTimeNs;
            snappedAfterNs = snappedTimeNs + vsyncDurationNs;
        }
        long snappedAfterDiffNs = snappedAfterNs - releaseTimeNs;
        long snappedBeforeDiffNs = releaseTimeNs - snappedBeforeNs;
        long snappedDiffsDiffNs = Math.abs(snappedAfterDiffNs - snappedBeforeDiffNs);
        boolean shouldEvaluateHysteresis = snappedDiffsDiffNs < vsyncDurationNs / 2L;
        if (shouldEvaluateHysteresis) {
            long hysteresisRangeNs = vsyncDurationNs / 4L;
            boolean isInHysteresisRange = snappedDiffsDiffNs < hysteresisRangeNs;
            if (isInHysteresisRange) {
                if (state.lastVsyncHysteresisOffsetNs != 0) {
                    state.pendingVsyncHysteresisOffsetNs = state.lastVsyncHysteresisOffsetNs;
                } else {
                    state.pendingVsyncHysteresisOffsetNs = snappedAfterDiffNs < snappedBeforeDiffNs
                            ? -hysteresisRangeNs : hysteresisRangeNs;
                }
            } else {
                state.pendingVsyncHysteresisOffsetNs = 0;
            }
        } else {
            state.pendingVsyncHysteresisOffsetNs = state.lastVsyncHysteresisOffsetNs;
        }
        return snappedAfterDiffNs + state.pendingVsyncHysteresisOffsetNs < snappedBeforeDiffNs
                ? snappedAfterNs : snappedBeforeNs;
    }

    private static void maybeSetSurfaceFrameRate(CodecState state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || state.surface == null
                || state.frameDurationNs == TIME_UNSET) {
            return;
        }
        float frameRate = 1_000_000_000f / state.frameDurationNs;
        if (frameRate < 23f || frameRate > 240f
                || Math.abs(frameRate - state.lastSurfaceFrameRate) < 0.5f) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                state.surface.setFrameRate(frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                state.surface.setFrameRate(frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            state.lastSurfaceFrameRate = frameRate;
            surfaceFrameRateHints++;
        } catch (Throwable throwable) {
            logOnce("surface-frame-rate", "Surface frame-rate hint unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static void clearSurfaceFrameRate(CodecState state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || state.surface == null
                || state.lastSurfaceFrameRate == 0f) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                state.surface.setFrameRate(0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                state.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }
        } catch (Throwable ignored) {
            // The codec lifecycle must remain safe if the Surface has already been released.
        }
        state.lastSurfaceFrameRate = 0f;
    }

    private static void removeCodec(MediaCodec codec, CodecState state) {
        if (state != null) {
            synchronized (state) {
                clearSurfaceFrameRate(state);
                state.resetPosition();
            }
        }
        CODECS.remove(codec);
        PENDING_RENDER_LISTENERS.remove(codec);
        synchronized (ACTIVE_VIDEO_CODECS) {
            ACTIVE_VIDEO_CODECS.remove(codec);
            if (ACTIVE_VIDEO_CODECS.isEmpty()) {
                requestVsyncUnregistration();
            }
        }
    }

    /**
     * Per-frame CSV telemetry is a diagnostic: it builds a few hundred bytes of strings per
     * frame on the release thread, and the GC it caused showed up as 2-6 ms runnable delays on
     * the pipeline threads.  Only the debug overlay (the measurement profile) turns it on; the
     * release scheduling itself needs none of it.
     */
    private static boolean telemetryEnabled() {
        return ModulePreferences.loadPlaybackDebugOverlayForHook();
    }

    // Former user switches of the Java fallback path, fixed at the values every successful
    // measurement used.  The native scheduling path reads none of them.
    private static final boolean FIXED_CADENCE_COMPENSATION = false;
    private static final boolean IJK_EARLY_US_VSYNC_ANCHOR = false;

    private static boolean experimentsEnabled() {
        // Frame-sync release scheduling is a user feature, not a diagnostic; the overlay only
        // gates the telemetry-heavy experiments.
        return ModulePreferences.loadPlaybackDebugOverlayForHook()
                || ModulePreferences.loadMedia3FrameSyncForHook();
    }

    private static boolean isVideo(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_MIME)) {
            return false;
        }
        String mime = format.getString(MediaFormat.KEY_MIME);
        return mime != null && mime.startsWith("video/");
    }

    private static boolean isAudio(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_MIME)) {
            return false;
        }
        String mime = format.getString(MediaFormat.KEY_MIME);
        return mime != null && mime.startsWith("audio/");
    }

    private static int formatBitrate(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            return -1;
        }
        try {
            return format.getInteger(MediaFormat.KEY_BIT_RATE);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int formatSampleRate(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            return -1;
        }
        try {
            return format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isQtiDecoder(MediaCodec codec) {
        String name = codecName(codec).toLowerCase();
        return name.startsWith("c2.qti.") || name.startsWith("omx.qcom.");
    }

    private static String codecName(MediaCodec codec) {
        String name;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                name = codec.getName();
            } else {
                name = codec.getClass().getName();
            }
        } catch (Throwable ignored) {
            // Keep lifecycle hooks safe when a vendor codec rejects getName early.
            name = codec.getClass().getName();
        }
        return name + "#" + Integer.toHexString(System.identityHashCode(codec));
    }

    @SuppressWarnings("unchecked")
    private static List<String> supportedVendorParameters(MediaCodec codec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Collections.emptyList();
        }
        try {
            Object result = XposedHelpers.callMethod(codec, "getSupportedVendorParameters");
            if (!(result instanceof Collection)) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<String>();
            for (Object value : (Collection<Object>) result) {
                if (value instanceof String) {
                    names.add((String) value);
                }
            }
            return names;
        } catch (Throwable throwable) {
            logOnce("qti-fence-query:" + codecName(codec),
                    "QTI vendor-parameter query unavailable="
                            + throwable.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static synchronized void ensureVsyncSampler() {
        if (vsyncThread != null) {
            return;
        }
        Context context = currentApplicationContext();
        if (context == null) {
            vsyncSamplerMode = "no-context";
            return;
        }
        vsyncThread = new HandlerThread("Bili-Media3-VSync", Process.THREAD_PRIORITY_DISPLAY);
        vsyncThread.start();
        vsyncHandler = new Handler(vsyncThread.getLooper());
        vsyncHandler.post(new Runnable() {
            @Override
            public void run() {
                DisplayManager displayManager = (DisplayManager) context.getSystemService(
                        Context.DISPLAY_SERVICE);
                if (displayManager == null) {
                    vsyncSamplerMode = "no-display-manager";
                    return;
                }
                Choreographer choreographer;
                try {
                    choreographer = Choreographer.getInstance();
                } catch (RuntimeException exception) {
                    vsyncSamplerMode = "choreographer-error";
                    return;
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    vsyncSampler = new VSyncSamplerV33(choreographer, displayManager, vsyncHandler);
                    vsyncSamplerMode = "frame-timeline";
                } else {
                    vsyncSampler = new VSyncSamplerBase(choreographer, displayManager, vsyncHandler);
                    vsyncSamplerMode = "frame-callback";
                }
                synchronized (ACTIVE_VIDEO_CODECS) {
                    if (!ACTIVE_VIDEO_CODECS.isEmpty()) {
                        vsyncSampler.register();
                    }
                }
            }
        });
    }

    private static void requestVsyncRegistration() {
        Handler handler = vsyncHandler;
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                VSyncSampler sampler = vsyncSampler;
                if (sampler != null) {
                    synchronized (ACTIVE_VIDEO_CODECS) {
                        if (!ACTIVE_VIDEO_CODECS.isEmpty()) {
                            sampler.register();
                        }
                    }
                }
            }
        });
    }

    private static void requestVsyncUnregistration() {
        Handler handler = vsyncHandler;
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                VSyncSampler sampler = vsyncSampler;
                if (sampler != null) {
                    synchronized (ACTIVE_VIDEO_CODECS) {
                        if (ACTIVE_VIDEO_CODECS.isEmpty()) {
                            sampler.unregister();
                        }
                    }
                }
            }
        });
    }

    private static Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = XposedHelpers.callStaticMethod(activityThread,
                    "currentApplication");
            if (application instanceof Context) {
                return ((Context) application).getApplicationContext();
            }
        } catch (Throwable throwable) {
            logOnce("vsync-context", "VSync sampler context unavailable="
                    + throwable.getClass().getSimpleName());
        }
        return null;
    }

    static String installPlaybackClockHooks(ClassLoader classLoader) {
        if (playbackClockHooksInstalled) {
            return "IJK playback clock hooks already installed";
        }
        // The setSpeed/_setPropertyFloat hooks below are on the main-process API class.  In this (ijkservice)
        // process the speed arrives over AIDL at the native player's Java peer,
        // services.IjkMediaPlayerClient.setPropertyFloat(10003, rate) -> _setPropertyFloat;
        // IjkMediaPlayer.setSpeed never runs here, so without this the rate gate stayed 1.0.
        // Installed first and on its own: the IjkMediaPlayer hooks below can fail on a missing
        // method (NoSuchMethodError), which used to abort this one too.
        String clientHook;
        try {
            Class<?> clientClass = XposedHelpers.findClass(
                    "tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient", classLoader);
            hookPlayerMethod(clientClass, "setPropertyFloat",
                    new Class<?>[]{int.class, float.class}, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (((Integer) param.args[0]).intValue()
                                    == FFP_PROP_FLOAT_PLAYBACK_RATE) {
                                onClientPlaybackRate(param.thisObject,
                                        ((Number) param.args[1]).floatValue());
                            }
                        }
                    });
            clientHook = " +IjkMediaPlayerClient.setPropertyFloat";
        } catch (Throwable throwable) {
            clientHook = " (IjkMediaPlayerClient.setPropertyFloat unavailable="
                    + throwable.getClass().getSimpleName() + ")";
        }
        try {
            final Class<?> playerClass = XposedHelpers.findClass(
                    "tv.danmaku.ijk.media.player.IjkMediaPlayer", classLoader);
            hookPlayerMethod(playerClass, "getCurrentPosition", new Class<?>[0],
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result instanceof Number) {
                                latestPlaybackPositionUs = ((Number) result).longValue() * 1_000L;
                                latestPlaybackPositionSampleNs = System.nanoTime();
                            }
                        }
                    });
            hookPlayerLifecycle(playerClass, "start", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "pause", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "stop", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "release", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "prepareAsync", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "seekTo", new Class<?>[]{long.class});
            // Playback speed: setSpeed(float) posts FFP_PROP_FLOAT_PLAYBACK_RATE (10003) to the
            // native player; both entry points feed the native scheduler's rate gate.
            hookPlayerMethod(playerClass, "setSpeed", new Class<?>[]{float.class},
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            onPlaybackRate(((Number) param.args[0]).floatValue(), "setSpeed");
                        }
                    });
            hookPlayerMethod(playerClass, "_setPropertyFloat",
                    new Class<?>[]{int.class, float.class}, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (((Integer) param.args[0]).intValue() == FFP_PROP_FLOAT_PLAYBACK_RATE) {
                                onPlaybackRate(((Number) param.args[1]).floatValue(),
                                        "_setPropertyFloat");
                            }
                        }
                    });
            playbackClockHooksInstalled = true;
            return "installed IJK playback clock and lifecycle telemetry hooks" + clientHook;
        } catch (Throwable throwable) {
            playbackClockHooksInstalled = true;
            return "IJK playback clock telemetry unavailable="
                    + throwable.getClass().getSimpleName() + clientHook;
        }
    }

    private static final int FFP_PROP_FLOAT_PLAYBACK_RATE = 10003;
    private static float latestPlaybackRate = 1.0f;

    // Per native player: the service can host a preview player next to the main one, and the
    // scheduler gate is global, so it stays bypassed while any live player runs off 1.0x.
    private static final Map<Object, Float> CLIENT_RATES =
            Collections.synchronizedMap(new WeakHashMap<Object, Float>());

    private static void onClientPlaybackRate(Object client, float rate) {
        if (!(rate > 0f)) {
            return;
        }
        float effective = 1.0f;
        synchronized (CLIENT_RATES) {
            CLIENT_RATES.put(client, rate);
            for (Float value : CLIENT_RATES.values()) {
                if (value != null && Math.abs(value - 1.0f) >= 0.01f) {
                    effective = value;
                    break;
                }
            }
        }
        onPlaybackRate(effective, "IjkMediaPlayerClient.setPropertyFloat");
    }

    private static void onPlaybackRate(float rate, String source) {
        if (!(rate > 0f) || rate == latestPlaybackRate) {
            return;
        }
        latestPlaybackRate = rate;
        NativeRefreshCap.setPlaybackRate(rate);
        XposedBridge.log(TAG + ": playback rate " + rate + " via " + source
                + (Math.abs(rate - 1.0f) < 0.01f ? " (VSync scheduling on)"
                        : " (VSync scheduling bypassed)"));
    }

    private static void hookPlayerLifecycle(Class<?> playerClass, final String methodName,
                                            Class<?>[] parameterTypes) {
        try {
            hookPlayerMethod(playerClass, methodName, parameterTypes, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    latestPlayerLifecycle = methodName;
                    enqueueTelemetry(eventLine(System.nanoTime(), "PLAYER_LIFECYCLE", "", -1,
                            TIME_UNSET, TIME_UNSET, TIME_UNSET, latestPlaybackPositionUs,
                            playbackPositionAgeMs(System.nanoTime()), TIME_UNSET, TIME_UNSET,
                            TIME_UNSET, TIME_UNSET, methodName, false, methodName,
                            "args=" + param.args.length));
                }
            });
        } catch (Throwable throwable) {
            logOnce("player-lifecycle-hook:" + methodName,
                    "IJK lifecycle telemetry unavailable for " + methodName + ": "
                            + throwable.getClass().getSimpleName());
        }
    }

    private static void hookPlayerMethod(Class<?> playerClass, String methodName,
                                         Class<?>[] parameterTypes, XC_MethodHook hook) {
        Object[] hookArgs = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, hookArgs, 0, parameterTypes.length);
        hookArgs[parameterTypes.length] = hook;
        XposedHelpers.findAndHookMethod(playerClass, methodName, hookArgs);
    }

    private static void recordCodecLifecycle(MediaCodec codec, String lifecycle, Object surface) {
        if (!telemetryEnabled()) {
            return;
        }
        String detail = surface == null ? "surface=null" : "surface=" + surface.getClass().getName();
        enqueueTelemetry(eventLine(System.nanoTime(), "CODEC_LIFECYCLE", codecName(codec), -1,
                TIME_UNSET, TIME_UNSET, TIME_UNSET, latestPlaybackPositionUs,
                playbackPositionAgeMs(System.nanoTime()), TIME_UNSET, TIME_UNSET, TIME_UNSET,
                TIME_UNSET, lifecycle, false, lifecycle, detail));
    }

    private static void recordRenderListenerLifecycle(
            MediaCodec codec,
            String lifecycle,
            MediaCodec.OnFrameRenderedListener applicationListener,
            Handler handler,
            long listenerGeneration) {
        if (!telemetryEnabled()) {
            return;
        }
        String detail = "listenerPresent=" + (applicationListener != null)
                + ";handler=" + (handler == null ? "null" : handler.getClass().getName())
                + ";listenerGeneration=" + listenerGeneration;
        enqueueTelemetry(eventLine(System.nanoTime(), "CODEC_LIFECYCLE", codecName(codec), -1,
                TIME_UNSET, TIME_UNSET, TIME_UNSET, latestPlaybackPositionUs,
                playbackPositionAgeMs(System.nanoTime()), TIME_UNSET, TIME_UNSET, TIME_UNSET,
                TIME_UNSET, lifecycle, false, "MediaCodec.setOnFrameRenderedListener", detail));
    }

    private static void recordReleaseRequest(MediaCodec codec, int index, boolean render,
                                             String action, String detail) {
        if (!telemetryEnabled()) {
            return;
        }
        CodecState state = CODECS.get(codec);
        FrameTiming timing = state == null ? null : peekTiming(state, index);
        if (!render && state != null) {
            synchronized (state) {
                state.removeTiming(index);
            }
        }
        long nowNs = System.nanoTime();
        recordFrameEvent(codec, index, timing, nowNs, currentPlaybackPositionUs(nowNs),
                playbackPositionAgeMs(nowNs), state == null ? TIME_UNSET
                        : state.estimatedPositionUs(nowNs), TIME_UNSET, TIME_UNSET, action, render,
                "releaseOutputBuffer(bool)", detail);
    }

    private static void recordDirectTimedRelease(MediaCodec codec, int index, long releaseTimeNs) {
        if (!telemetryEnabled()) {
            return;
        }
        CodecState state = CODECS.get(codec);
        FrameTiming timing = state == null ? null : peekTiming(state, index);
        long nowNs = System.nanoTime();
        recordFrameEvent(codec, index, timing, nowNs, currentPlaybackPositionUs(nowNs),
                playbackPositionAgeMs(nowNs), state == null ? TIME_UNSET
                        : state.estimatedPositionUs(nowNs), TIME_UNSET, releaseTimeNs,
                "DIRECT_TIMED_RELEASE", true, "releaseOutputBuffer(time)", "");
    }

    private static FrameTiming peekTiming(CodecState state, int index) {
        synchronized (state) {
            FrameTiming timing = state.presentationTimes.get(index);
            return timing == null ? state.frameTimingsByBufferIndex.get(index) : timing;
        }
    }

    private static void recordFrameEvent(MediaCodec codec, int index, FrameTiming timing,
                                         long eventNs, long playbackPositionUs,
                                         long positionAgeMs, long estimatedPositionUs,
                                         long unadjustedReleaseNs, long releaseTimeNs,
                                         String action, boolean render, String api,
                                         String detail) {
        if (!telemetryEnabled()) {
            return;
        }
        long ptsUs = timing == null ? TIME_UNSET : timing.presentationTimeUs;
        long frameIndex = timing == null ? INDEX_UNSET : timing.frameIndex;
        long dequeuedAtNs = timing == null ? TIME_UNSET : timing.dequeuedAtNs;
        long earlyUs = playbackPositionUs == TIME_UNSET || ptsUs == TIME_UNSET
                ? TIME_UNSET : ptsUs - playbackPositionUs;
        VSyncSampler sampler = vsyncSampler;
        long sampledVsyncId = sampler == null ? TIME_UNSET : sampler.sampledVsyncId;
        long sampledVsyncNs = sampler == null ? TIME_UNSET : sampler.sampledVsyncTimeNs;
        long vsyncDurationNs = sampler == null ? TIME_UNSET : sampler.vsyncDurationNs;
        long expectedPresentationNs = sampler == null
                ? TIME_UNSET : sampler.expectedPresentationTimeNs;
        long frameDeadlineNs = sampler == null ? TIME_UNSET : sampler.deadlineNs;
        CodecState state = CODECS.get(codec);
        long frameDurationNs = TIME_UNSET;
        boolean fixedFrameRateSynced = false;
        if (state != null) {
            synchronized (state) {
                frameDurationNs = state.frameDurationNs;
                fixedFrameRateSynced = state.frameRateEstimator.isSynced();
            }
        }
        enqueueTelemetry(eventLine(eventNs, "FRAME", codecName(codec), index, ptsUs, frameIndex,
                dequeuedAtNs, playbackPositionUs, positionAgeMs, estimatedPositionUs, earlyUs,
                unadjustedReleaseNs, releaseTimeNs, action, render, api,
                detail + ";playerLifecycle=" + latestPlayerLifecycle
                        + ";sampledVsyncId=" + sampledVsyncId
                        + ";sampledVsyncNs=" + sampledVsyncNs
                        + ";vsyncDurationNs=" + vsyncDurationNs
                        + ";expectedPresentationNs=" + expectedPresentationNs
                        + ";frameDeadlineNs=" + frameDeadlineNs
                        + ";releaseToExpectedPresentationNs="
                        + differenceOrUnset(releaseTimeNs, expectedPresentationNs)
                        + ";releaseToSampledVsyncNs="
                        + differenceOrUnset(releaseTimeNs, sampledVsyncNs)
                        + ";frameDurationNs=" + frameDurationNs
                        + ";fixedFrameRateSynced=" + fixedFrameRateSynced));
    }

    static void recordWindowFrameMetrics(long metricsAvailableNs, long intendedVsyncNs,
                                         long vsyncTimestampNs, long deadlineNs,
                                         long totalDurationNs, long swapBuffersDurationNs,
                                         int dropCountSinceLastInvocation) {
        if (!telemetryEnabled()) {
            return;
        }
        VSyncSampler sampler = vsyncSampler;
        long sampledVsyncId = sampler == null ? TIME_UNSET : sampler.sampledVsyncId;
        long expectedPresentationNs = sampler == null
                ? TIME_UNSET : sampler.expectedPresentationTimeNs;
        String detail = "metricsAvailableNs=" + metricsAvailableNs
                + ";dropCountSinceLastInvocation=" + dropCountSinceLastInvocation
                + ";sampledVsyncId=" + sampledVsyncId
                + ";intendedVsyncNs=" + intendedVsyncNs
                + ";vsyncTimestampNs=" + vsyncTimestampNs
                + ";deadlineNs=" + deadlineNs
                + ";expectedPresentationNs=" + expectedPresentationNs
                + ";metricsAvailableMinusVsyncNs="
                + differenceOrUnset(metricsAvailableNs, vsyncTimestampNs)
                + ";expectedPresentationMinusVsyncNs="
                + differenceOrUnset(expectedPresentationNs, vsyncTimestampNs)
                + ";totalDurationNs=" + totalDurationNs
                + ";swapBuffersDurationNs=" + swapBuffersDurationNs;
        enqueueTelemetry(eventLine(metricsAvailableNs, "WINDOW_FRAME_METRICS", "", -1,
                TIME_UNSET, INDEX_UNSET, TIME_UNSET, TIME_UNSET, TIME_UNSET, TIME_UNSET,
                TIME_UNSET, TIME_UNSET, TIME_UNSET, "FRAME_METRICS", true,
                "Window.OnFrameMetricsAvailableListener", detail));
    }

    static void recordWindowFrameMetricsListenerState(String state) {
        if (!telemetryEnabled()) {
            return;
        }
        enqueueTelemetry(eventLine(System.nanoTime(), "WINDOW_FRAME_METRICS_LISTENER", "", -1,
                TIME_UNSET, INDEX_UNSET, TIME_UNSET, TIME_UNSET, TIME_UNSET, TIME_UNSET,
                TIME_UNSET, TIME_UNSET, TIME_UNSET, state, true,
                "Window.addOnFrameMetricsAvailableListener", "state=" + state));
    }

    private static long differenceOrUnset(long left, long right) {
        return left == TIME_UNSET || right == TIME_UNSET ? TIME_UNSET : left - right;
    }

    private static long sampledVsyncAgeNs(VSyncSampler sampler, long nowNs) {
        if (sampler == null || sampler.sampledVsyncTimeNs == TIME_UNSET
                || sampler.sampledVsyncTimeNs > nowNs) {
            return TIME_UNSET;
        }
        return nowNs - sampler.sampledVsyncTimeNs;
    }

    private static String eventLine(long eventNs, String event, String codec, int index,
                                    long ptsUs, long frameIndex, long dequeuedAtNs,
                                    long playbackPositionUs, long positionAgeMs,
                                    long estimatedPositionUs, long earlyUs,
                                    long unadjustedReleaseNs, long releaseTimeNs,
                                    String action, boolean render, String api, String detail) {
        StringBuilder builder = new StringBuilder(256);
        appendCsv(builder, telemetrySessionId());
        appendCsv(builder, String.valueOf(eventNs));
        appendCsv(builder, event);
        appendCsv(builder, codec);
        appendCsv(builder, String.valueOf(index));
        appendCsv(builder, String.valueOf(ptsUs));
        appendCsv(builder, String.valueOf(frameIndex));
        appendCsv(builder, String.valueOf(dequeuedAtNs));
        appendCsv(builder, String.valueOf(playbackPositionUs));
        appendCsv(builder, String.valueOf(positionAgeMs));
        appendCsv(builder, String.valueOf(estimatedPositionUs));
        appendCsv(builder, String.valueOf(earlyUs));
        appendCsv(builder, String.valueOf(unadjustedReleaseNs));
        appendCsv(builder, String.valueOf(releaseTimeNs));
        appendCsv(builder, action);
        appendCsv(builder, String.valueOf(render));
        appendCsv(builder, api);
        appendCsv(builder, detail);
        builder.setLength(builder.length() - 1);
        builder.append('\n');
        return builder.toString();
    }

    private static void appendCsv(StringBuilder builder, String value) {
        if (value == null) {
            value = "";
        }
        builder.append('"').append(value.replace("\"", "\"\""))
                .append('"').append(',');
    }

    private static String telemetrySessionId() {
        return TELEMETRY_SESSION_ID;
    }

    private static long currentPlaybackPositionUs(long nowNs) {
        return latestPlaybackPositionUs;
    }

    private static IjkEarlyUsTiming readIjkEarlyUsTiming(long presentationTimeUs,
                                                          long nowNs) {
        // Bilibili 9.13.0 has no earlyUs probe (the 9.5.0 struct offsets were never ported), so
        // the anchor is permanently off; the Java fallback path uses the VSync sampler alone.
        if (!IJK_EARLY_US_VSYNC_ANCHOR || presentationTimeUs == TIME_UNSET) {
            return null;
        }
        long[] sample = NativeRefreshCap.getIjkEarlyUsForPts(presentationTimeUs);
        if (sample == null || sample.length < IJK_EARLY_US_SAMPLE_FIELD_COUNT) {
            ijkEarlyUsMisses++;
            return null;
        }
        long sampleTimestampNs = sample[0];
        long earlyUsNanos = sample[2];
        if (sampleTimestampNs <= 0L || sampleTimestampNs > nowNs) {
            ijkEarlyUsStaleSamples++;
            return null;
        }
        long ageNs = nowNs - sampleTimestampNs;
        if (ageNs > MAX_IJK_EARLY_US_SAMPLE_AGE_NS) {
            ijkEarlyUsStaleSamples++;
            return null;
        }
        if ((earlyUsNanos > 0L && sampleTimestampNs > Long.MAX_VALUE - earlyUsNanos)
                || (earlyUsNanos < 0L && sampleTimestampNs < Long.MIN_VALUE - earlyUsNanos)) {
            ijkEarlyUsStaleSamples++;
            return null;
        }
        return new IjkEarlyUsTiming(sampleTimestampNs, sample[1], earlyUsNanos, sample[3],
                ageNs, sampleTimestampNs + earlyUsNanos);
    }

    private static long playbackPositionAgeMs(long nowNs) {
        if (latestPlaybackPositionSampleNs == TIME_UNSET) {
            return TIME_UNSET;
        }
        return Math.max(0L, (nowNs - latestPlaybackPositionSampleNs) / 1_000_000L);
    }

    private static void ensureTelemetryWriter() {
        if (telemetryWriter != null || !telemetryEnabled()) {
            return;
        }
        synchronized (Media3FrameSynchronizer.class) {
            if (telemetryWriter != null || !telemetryEnabled()) {
                return;
            }
            try {
                int userId = Process.myUid() / 100000;
                File directory = new File("/data/user/" + userId
                        + "/tv.danmaku.bili/files/bilisurfacebridge");
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    logOnce("media3-telemetry-directory", "telemetry directory unavailable");
                    return;
                }
                telemetryWriter = new TelemetryWriter(
                        new File(directory, "media3_frame_timing.csv"));
                logOnce("media3-telemetry-file", "Media3 frame telemetry path="
                        + new File(directory, "media3_frame_timing.csv").getAbsolutePath());
            } catch (Throwable throwable) {
                logOnce("media3-telemetry-writer", "telemetry writer unavailable="
                        + throwable.getClass().getSimpleName());
            }
        }
    }

    private static void enqueueTelemetry(String line) {
        if (!telemetryEnabled()) {
            return;
        }
        ensureTelemetryWriter();
        TelemetryWriter writer = telemetryWriter;
        if (writer != null) {
            writer.enqueue(line);
        }
    }

    private static void maybeReport(long nowNs) {
        if (nowNs - lastReportNs < 1_000_000_000L) {
            return;
        }
        lastReportNs = nowNs;
        VSyncSampler sampler = vsyncSampler;
        double vsyncMs = sampler == null || sampler.vsyncDurationNs == TIME_UNSET
                ? 0.0 : sampler.vsyncDurationNs / 1_000_000.0;
        if (nativeSchedulingActive) {
            XposedBridge.log(TAG + ": native release scheduling "
                    + NativeRefreshCap.releaseSchedulingStatus());
        }
        XposedBridge.log(TAG + ": Media3 sync timed=" + timedReleases
        + " predicted=" + predictedDeadlines
        + " resets=" + deadlineResets
                + " clockReanchors=" + clockReanchors
                + " clockLateFrames=" + clockLateFrames
                + " vsyncSnaps=" + vsyncSnaps
                + " ijkEarlyUsAnchors=" + ijkEarlyUsAnchors
                + " ijkEarlyUsMisses=" + ijkEarlyUsMisses
                + " ijkEarlyUsStale=" + ijkEarlyUsStaleSamples
                + " cadenceAnchors=" + cadenceAnchors
                + " cadenceExtraVsyncs=" + cadenceExtraVsyncs
                + " cadencePhaseCorrections=" + cadencePhaseCorrections
                + " cadenceFallbacks=" + cadenceFallbacks
                + " vsyncMode=" + vsyncSamplerMode
                + " vsyncMs=" + String.format(java.util.Locale.US, "%.3f", vsyncMs)
                + " qtiFenceConfigs=" + qtiFenceConfigurations
                + " surfaceRateHints=" + surfaceFrameRateHints);
    }

    private static void logOnce(String key, String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static final class TelemetryWriter {
        private static final int QUEUE_CAPACITY = 32768;
        private static final String HEADER =
                "sessionId,eventNs,event,codec,bufferIndex,ptsUs,frameIndex,dequeuedAtNs,"
                        + "playbackPositionUs,positionAgeMs,estimatedPositionUs,earlyUs,"
                        + "unadjustedReleaseNs,releaseTimeNs,action,render,api,detail\n";
        private final BlockingQueue<String> queue =
                new ArrayBlockingQueue<String>(QUEUE_CAPACITY);
        private final BufferedWriter writer;

        TelemetryWriter(File file) throws IOException {
            // The log grows by roughly 1 GB per hour of playback; a 17 GB file was found on
            // the device.  Start over instead of appending to an oversized file.
            if (file.exists() && file.length() > MAX_TELEMETRY_FILE_BYTES && !file.delete()) {
                throw new IOException("oversized telemetry file could not be deleted");
            }
            boolean writeHeader = !file.exists() || file.length() == 0L;
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), "UTF-8"));
            if (writeHeader) {
                writer.write(HEADER);
                writer.flush();
            }
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    writeLoop();
                }
            }, "BiliSurfaceBridge-Media3Telemetry");
            thread.setDaemon(true);
            thread.start();
        }

        void enqueue(String line) {
            if (!queue.offer(line)) {
                telemetryDroppedRecords++;
            }
        }

        private void writeLoop() {
            int pendingFlushes = 0;
            while (true) {
                try {
                    String line = queue.take();
                    writer.write(line);
                    pendingFlushes++;
                    if (pendingFlushes >= 16) {
                        writer.flush();
                        pendingFlushes = 0;
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException ioException) {
                    logOnce("media3-telemetry-write", "telemetry write failed="
                            + ioException.getClass().getSimpleName());
                    return;
                }
            }
        }
    }

    private static final class PendingRenderListener {
        final MediaCodec.OnFrameRenderedListener listener;
        final Handler handler;

        PendingRenderListener(MediaCodec.OnFrameRenderedListener listener, Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }
    }

    private static final class FrameTiming {
        final int bufferIndex;
        final long presentationTimeUs;
        final long frameIndex;
        final long dequeuedAtNs;
        final long renderGeneration;
        long releaseCallNs = TIME_UNSET;
        long unadjustedReleaseNs = TIME_UNSET;
        long scheduledReleaseNs = TIME_UNSET;
        volatile long scheduledCallNs = TIME_UNSET;
        long renderedNanoTimeNs = TIME_UNSET;
        long renderCallbackObservedNs = TIME_UNSET;
        long renderListenerGeneration = TIME_UNSET;

        FrameTiming(int bufferIndex, long presentationTimeUs, long frameIndex,
                    long dequeuedAtNs, long renderGeneration) {
            this.bufferIndex = bufferIndex;
            this.presentationTimeUs = presentationTimeUs;
            this.frameIndex = frameIndex;
            this.dequeuedAtNs = dequeuedAtNs;
            this.renderGeneration = renderGeneration;
        }
    }

    private static final class IjkEarlyUsTiming {
        final long sampleTimestampNs;
        final long matchedPtsUs;
        final long earlyUsNanos;
        final long filterDelayNanos;
        final long ageNs;
        final long releaseTimeNs;

        IjkEarlyUsTiming(long sampleTimestampNs, long matchedPtsUs, long earlyUsNanos,
                         long filterDelayNanos, long ageNs, long releaseTimeNs) {
            this.sampleTimestampNs = sampleTimestampNs;
            this.matchedPtsUs = matchedPtsUs;
            this.earlyUsNanos = earlyUsNanos;
            this.filterDelayNanos = filterDelayNanos;
            this.ageNs = ageNs;
            this.releaseTimeNs = releaseTimeNs;
        }
    }

    private static final class CadenceTiming {
        final long releaseTimeNs;
        final long targetVsyncTimeNs;
        final long extraVsyncs;
        final long correctionIntervalFrames;
        final long frameDurationNs;
        final long vsyncDurationNs;
        final long anchorFrameIndex;
        final long anchorPresentationTimeUs;
        final long anchorVsyncTimeNs;
        final long frameDelta;
        final long frameSlot;
        final long driftNs;
        final long phaseErrorNs;
        final boolean reanchored;
        final String reanchorReason;

        CadenceTiming(long releaseTimeNs, long targetVsyncTimeNs, long extraVsyncs,
                      long correctionIntervalFrames, long frameDurationNs, long vsyncDurationNs,
                      long anchorFrameIndex, long anchorPresentationTimeUs,
                      long anchorVsyncTimeNs, long frameDelta, long frameSlot, long driftNs,
                      long phaseErrorNs, boolean reanchored, String reanchorReason) {
            this.releaseTimeNs = releaseTimeNs;
            this.targetVsyncTimeNs = targetVsyncTimeNs;
            this.extraVsyncs = extraVsyncs;
            this.correctionIntervalFrames = correctionIntervalFrames;
            this.frameDurationNs = frameDurationNs;
            this.vsyncDurationNs = vsyncDurationNs;
            this.anchorFrameIndex = anchorFrameIndex;
            this.anchorPresentationTimeUs = anchorPresentationTimeUs;
            this.anchorVsyncTimeNs = anchorVsyncTimeNs;
            this.frameDelta = frameDelta;
            this.frameSlot = frameSlot;
            this.driftNs = driftNs;
            this.phaseErrorNs = phaseErrorNs;
            this.reanchored = reanchored;
            this.reanchorReason = reanchorReason;
        }
    }

    private static final class CodecState {
        final Map<Integer, FrameTiming> presentationTimes = new HashMap<Integer, FrameTiming>();
        final Map<Integer, FrameTiming> frameTimingsByBufferIndex =
                new HashMap<Integer, FrameTiming>();
        final Map<Long, ArrayDeque<FrameTiming>> pendingRenderTimings =
                new HashMap<Long, ArrayDeque<FrameTiming>>();
        final ArrayDeque<FrameTiming> pendingRenderOrder = new ArrayDeque<FrameTiming>();
        final Surface surface;
        final FixedFrameRateEstimator frameRateEstimator = new FixedFrameRateEstimator();
        long nextFrameIndex;
        long renderGeneration;
        long renderListenerGeneration;
        long renderCallbackCount;
        long renderCallbackExactMatchCount;
        long renderCallbackUnmatchedCount;
        long renderCallbackBurstCount;
        long lastRenderCallbackObservedNs = TIME_UNSET;
        long lastRenderCallbackNanoTimeNs = TIME_UNSET;
        MediaCodec.OnFrameRenderedListener applicationRenderListener;
        MediaCodec.OnFrameRenderedListener renderListenerWrapper;
        long frameDurationNs = TIME_UNSET;
        long pendingLastPresentationTimeUs = TIME_UNSET;
        long lastAdjustedPresentationTimeUs = TIME_UNSET;
        long pendingLastAdjustedReleaseTimeNs = TIME_UNSET;
        long lastAdjustedReleaseTimeNs = TIME_UNSET;
        long pendingLastAdjustedFrameIndex = INDEX_UNSET;
        long lastAdjustedFrameIndex = INDEX_UNSET;
        long pendingVsyncHysteresisOffsetNs;
        long lastVsyncHysteresisOffsetNs;
        long clockAnchorPresentationTimeUs = TIME_UNSET;
        long clockAnchorTimeNs = TIME_UNSET;
        long lastClockPresentationTimeUs = TIME_UNSET;
        float lastSurfaceFrameRate;
        long cadenceAnchorFrameIndex = INDEX_UNSET;
        long cadenceAnchorPresentationTimeUs = TIME_UNSET;
        long cadenceAnchorVsyncTimeNs = TIME_UNSET;
        long cadenceFrameDurationNs = TIME_UNSET;
        long cadenceVsyncDurationNs = TIME_UNSET;
        long cadenceLastFrameSlot = -1L;
        long cadenceLastExtraVsyncs;
        long gridAnchorNs = TIME_UNSET;
        long lastGridResidualNs = TIME_UNSET;
        long lastHoldBumpPeriods;
        long lastTargetTickNs = TIME_UNSET;
        long lastSlotCollisions;

        CodecState(Surface surface) {
            this.surface = surface;
        }

        /**
         * Keeps one expected-presentation grid.  SurfaceFlinger alternates its early/late
         * VSync configuration, so consecutive FrameTimeline samples can sit on grids about
         * half a period apart; snapping to whichever grid was sampled last makes targets
         * flip between slots.  Samples on the current grid refresh the anchor, others are
         * ignored.
         */
        long updateGridAnchor(long sampleNs, long periodNs) {
            if (periodNs < MIN_VSYNC_DURATION_NS || periodNs > MAX_VSYNC_DURATION_NS) {
                return TIME_UNSET;
            }
            if (sampleNs == TIME_UNSET) {
                return gridAnchorNs;
            }
            if (gridAnchorNs == TIME_UNSET) {
                gridAnchorNs = sampleNs;
                lastGridResidualNs = 0L;
                return gridAnchorNs;
            }
            long residualNs = Math.floorMod(sampleNs - gridAnchorNs, periodNs);
            if (residualNs > periodNs / 2L) {
                residualNs -= periodNs;
            }
            lastGridResidualNs = residualNs;
            if (Math.abs(residualNs) <= GRID_ANCHOR_TOLERANCE_NS) {
                gridAnchorNs = sampleNs;
            } else {
                gridAnchorRejects++;
            }
            return gridAnchorNs;
        }

        void addRenderCandidate(FrameTiming timing) {
            frameTimingsByBufferIndex.put(timing.bufferIndex, timing);
            ArrayDeque<FrameTiming> candidates = pendingRenderTimings.get(
                    timing.presentationTimeUs);
            if (candidates == null) {
                candidates = new ArrayDeque<FrameTiming>();
                pendingRenderTimings.put(timing.presentationTimeUs, candidates);
            }
            candidates.addLast(timing);
            pendingRenderOrder.addLast(timing);
            while (pendingRenderOrder.size() > MAX_PENDING_RENDER_TIMINGS) {
                FrameTiming evicted = pendingRenderOrder.removeFirst();
                removePendingRenderTiming(evicted);
            }
        }

        int pendingRenderTimingCount() {
            return pendingRenderOrder.size();
        }

        FrameTiming takeRenderCandidate(long presentationTimeUs, long renderedNanoTimeNs) {
            ArrayDeque<FrameTiming> candidates = pendingRenderTimings.get(presentationTimeUs);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            FrameTiming best = null;
            long bestDelta = Long.MAX_VALUE;
            for (FrameTiming candidate : candidates) {
                long anchorNs = candidate.scheduledReleaseNs != TIME_UNSET
                        ? candidate.scheduledReleaseNs : candidate.dequeuedAtNs;
                long delta = renderedNanoTimeNs <= 0L || anchorNs == TIME_UNSET
                        ? Long.MAX_VALUE : Math.abs(renderedNanoTimeNs - anchorNs);
                if (best == null || delta < bestDelta) {
                    best = candidate;
                    bestDelta = delta;
                }
            }
            if (best != null) {
                removePendingRenderTiming(best);
            }
            return best;
        }

        void removeTiming(int bufferIndex) {
            FrameTiming timing = frameTimingsByBufferIndex.remove(bufferIndex);
            presentationTimes.remove(bufferIndex);
            if (timing != null) {
                removePendingRenderTiming(timing);
            }
        }

        private void removePendingRenderTiming(FrameTiming timing) {
            ArrayDeque<FrameTiming> candidates = pendingRenderTimings.get(
                    timing.presentationTimeUs);
            if (candidates != null) {
                candidates.remove(timing);
                if (candidates.isEmpty()) {
                    pendingRenderTimings.remove(timing.presentationTimeUs);
                }
            }
            pendingRenderOrder.remove(timing);
            FrameTiming indexed = frameTimingsByBufferIndex.get(timing.bufferIndex);
            if (indexed == timing) {
                frameTimingsByBufferIndex.remove(timing.bufferIndex);
            }
        }

        void resetAdjustment() {
            pendingLastPresentationTimeUs = TIME_UNSET;
            lastAdjustedPresentationTimeUs = TIME_UNSET;
            pendingLastAdjustedReleaseTimeNs = TIME_UNSET;
            lastAdjustedReleaseTimeNs = TIME_UNSET;
            pendingLastAdjustedFrameIndex = INDEX_UNSET;
            lastAdjustedFrameIndex = INDEX_UNSET;
            pendingVsyncHysteresisOffsetNs = 0;
            lastVsyncHysteresisOffsetNs = 0;
            resetCadence();
        }

        private void resetCadence() {
            cadenceAnchorFrameIndex = INDEX_UNSET;
            cadenceAnchorPresentationTimeUs = TIME_UNSET;
            cadenceAnchorVsyncTimeNs = TIME_UNSET;
            cadenceFrameDurationNs = TIME_UNSET;
            cadenceVsyncDurationNs = TIME_UNSET;
            cadenceLastFrameSlot = -1L;
            cadenceLastExtraVsyncs = 0L;
        }

        void resetPosition() {
            lastTargetTickNs = TIME_UNSET;
            presentationTimes.clear();
            frameTimingsByBufferIndex.clear();
            pendingRenderTimings.clear();
            pendingRenderOrder.clear();
            frameRateEstimator.reset();
            frameDurationNs = TIME_UNSET;
            nextFrameIndex = 0;
            renderGeneration++;
            renderCallbackCount = 0L;
            renderCallbackExactMatchCount = 0L;
            renderCallbackUnmatchedCount = 0L;
            renderCallbackBurstCount = 0L;
            lastRenderCallbackObservedNs = TIME_UNSET;
            lastRenderCallbackNanoTimeNs = TIME_UNSET;
            clockAnchorPresentationTimeUs = TIME_UNSET;
            clockAnchorTimeNs = TIME_UNSET;
            lastClockPresentationTimeUs = TIME_UNSET;
            resetAdjustment();
        }

        long videoClockReleaseTimeNs(FrameTiming timing, long nowNs) {
            long presentationTimeUs = timing.presentationTimeUs;
            boolean noAnchor = clockAnchorPresentationTimeUs == TIME_UNSET
                    || clockAnchorTimeNs == TIME_UNSET;
            boolean backwards = lastClockPresentationTimeUs != TIME_UNSET
                    && presentationTimeUs < lastClockPresentationTimeUs;
            boolean discontinuity = lastClockPresentationTimeUs != TIME_UNSET
                    && presentationTimeUs - lastClockPresentationTimeUs
                    > MAX_CLOCK_DISCONTINUITY_US;
            if (noAnchor || backwards || discontinuity) {
                clockAnchorPresentationTimeUs = presentationTimeUs;
                clockAnchorTimeNs = nowNs;
                clockReanchors++;
            }
            lastClockPresentationTimeUs = presentationTimeUs;
            return clockAnchorTimeNs
                    + (presentationTimeUs - clockAnchorPresentationTimeUs) * 1_000L;
        }

        long estimatedPositionUs(long nowNs) {
            if (clockAnchorPresentationTimeUs == TIME_UNSET || clockAnchorTimeNs == TIME_UNSET) {
                return TIME_UNSET;
            }
            return clockAnchorPresentationTimeUs + (nowNs - clockAnchorTimeNs) / 1_000L;
        }

        CadenceTiming fixedCadenceReleaseTime(FrameTiming timing, long baseReleaseTimeNs,
                                               long nowNs, long sampledVsyncTimeNs,
                                               long vsyncDurationNs) {
            if (frameDurationNs == TIME_UNSET || frameDurationNs < MIN_FRAME_DURATION_NS
                    || frameDurationNs > MAX_FRAME_DURATION_NS
                    || sampledVsyncTimeNs == TIME_UNSET
                    || vsyncDurationNs < MIN_VSYNC_DURATION_NS
                    || vsyncDurationNs > MAX_VSYNC_DURATION_NS) {
                return null;
            }
            String reanchorReason = "none";
            boolean reset = cadenceAnchorFrameIndex == INDEX_UNSET;
            if (reset) {
                reanchorReason = "initial";
            } else if (timing.frameIndex < cadenceAnchorFrameIndex) {
                reset = true;
                reanchorReason = "frame_index_backwards";
            } else if (Math.abs(frameDurationNs - cadenceFrameDurationNs)
                    > CADENCE_DURATION_CHANGE_TOLERANCE_NS) {
                reset = true;
                reanchorReason = "frame_duration_changed";
            } else if (Math.abs(vsyncDurationNs - cadenceVsyncDurationNs)
                    > CADENCE_DURATION_CHANGE_TOLERANCE_NS) {
                reset = true;
                reanchorReason = "vsync_duration_changed";
            }
            if (reset) {
                long releaseOffsetNs = (vsyncDurationNs * VSYNC_OFFSET_PERCENT) / 100L;
                long earliestTargetVsyncNs = Math.max(baseReleaseTimeNs, nowNs)
                        + releaseOffsetNs;
                long firstSlot = VsyncCadenceMath.firstSlotAtOrAfter(sampledVsyncTimeNs,
                        earliestTargetVsyncNs, vsyncDurationNs);
                cadenceAnchorFrameIndex = timing.frameIndex;
                cadenceAnchorPresentationTimeUs = timing.presentationTimeUs;
                cadenceAnchorVsyncTimeNs = sampledVsyncTimeNs
                        + firstSlot * vsyncDurationNs;
                cadenceFrameDurationNs = frameDurationNs;
                cadenceVsyncDurationNs = vsyncDurationNs;
                cadenceLastFrameSlot = -1L;
                cadenceLastExtraVsyncs = 0L;
                cadenceAnchors++;
            }

            long frameDelta = timing.frameIndex - cadenceAnchorFrameIndex;
            if (frameDelta < 0L) {
                return null;
            }
            long driftPerFrameNs = cadenceFrameDurationNs - cadenceVsyncDurationNs;
            long driftNs = frameDelta * driftPerFrameNs;
            long extraVsyncs = driftNs >= 0L
                    ? driftNs / cadenceVsyncDurationNs
                    : -((-driftNs + cadenceVsyncDurationNs - 1L)
                            / cadenceVsyncDurationNs);
            long frameSlot = frameDelta + extraVsyncs;
            if (frameSlot <= cadenceLastFrameSlot) {
                return null;
            }
            if (extraVsyncs > cadenceLastExtraVsyncs) {
                cadenceExtraVsyncs += extraVsyncs - cadenceLastExtraVsyncs;
                cadenceLastExtraVsyncs = extraVsyncs;
            }
            cadenceLastFrameSlot = frameSlot;
            long targetVsyncTimeNs = cadenceAnchorVsyncTimeNs
                    + frameSlot * cadenceVsyncDurationNs;
            long sampledVsyncSlot = Math.round((double) (sampledVsyncTimeNs
                    - cadenceAnchorVsyncTimeNs) / cadenceVsyncDurationNs);
            long predictedSampledVsyncTimeNs = cadenceAnchorVsyncTimeNs
                    + sampledVsyncSlot * cadenceVsyncDurationNs;
            long phaseErrorNs = sampledVsyncTimeNs - predictedSampledVsyncTimeNs;
            long appliedPhaseCorrectionNs = 0L;
            if (Math.abs(phaseErrorNs) >= CADENCE_PHASE_CORRECTION_TOLERANCE_NS
                    && Math.abs(phaseErrorNs) < cadenceVsyncDurationNs / 2L) {
                cadenceAnchorVsyncTimeNs += phaseErrorNs;
                targetVsyncTimeNs += phaseErrorNs;
                appliedPhaseCorrectionNs = phaseErrorNs;
                cadencePhaseCorrections++;
            }
            long correctionIntervalFrames = driftPerFrameNs > 0L
                    ? Math.max(1L, Math.round((double) cadenceVsyncDurationNs
                            / driftPerFrameNs)) : 0L;
            return new CadenceTiming(
                    targetVsyncTimeNs - (cadenceVsyncDurationNs * VSYNC_OFFSET_PERCENT) / 100L,
                    targetVsyncTimeNs, extraVsyncs, correctionIntervalFrames,
                    cadenceFrameDurationNs, cadenceVsyncDurationNs, cadenceAnchorFrameIndex,
                    cadenceAnchorPresentationTimeUs, cadenceAnchorVsyncTimeNs, frameDelta,
                    frameSlot, driftNs, appliedPhaseCorrectionNs, reset, reanchorReason);
        }

        void anchorNativeClock(FrameTiming timing, long releaseTimeNs) {
            clockAnchorPresentationTimeUs = timing.presentationTimeUs;
            clockAnchorTimeNs = releaseTimeNs;
            lastClockPresentationTimeUs = timing.presentationTimeUs;
        }
    }

    /**
     * Media3 FixedFrameRateEstimator adapted from the official matcher algorithm.
     *
     * <p>The previous implementation used a per-frame 3:1 EMA. That can publish a misleading
     * frame rate after a seek, loop boundary, or irregular timestamp. Media3 requires a stable
     * run of matching timestamps before using the estimate for release prediction and Surface
     * frame-rate selection.</p>
     */
    private static final class FixedFrameRateEstimator {
        private Matcher currentMatcher = new Matcher();
        private Matcher candidateMatcher = new Matcher();
        private boolean candidateMatcherActive;
        private long lastFramePresentationTimeNs = TIME_UNSET;

        void reset() {
            currentMatcher.reset();
            candidateMatcher.reset();
            candidateMatcherActive = false;
            lastFramePresentationTimeNs = TIME_UNSET;
        }

        void onNextFrame(long framePresentationTimeNs) {
            if (framePresentationTimeNs == lastFramePresentationTimeNs) {
                return;
            }
            currentMatcher.onNextFrame(framePresentationTimeNs);
            if (currentMatcher.isSynced()) {
                candidateMatcherActive = false;
            } else if (lastFramePresentationTimeNs != TIME_UNSET) {
                if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier()) {
                    candidateMatcher.reset();
                    candidateMatcher.onNextFrame(lastFramePresentationTimeNs);
                }
                candidateMatcherActive = true;
                candidateMatcher.onNextFrame(framePresentationTimeNs);
            }
            if (candidateMatcherActive && candidateMatcher.isSynced()) {
                Matcher previousMatcher = currentMatcher;
                currentMatcher = candidateMatcher;
                candidateMatcher = previousMatcher;
                candidateMatcherActive = false;
            }
            lastFramePresentationTimeNs = framePresentationTimeNs;
        }

        long getFrameDurationNs() {
            return currentMatcher.isSynced() ? currentMatcher.getFrameDurationNs() : TIME_UNSET;
        }

        boolean isSynced() {
            return currentMatcher.isSynced();
        }

        private static final class Matcher {
            private long firstFramePresentationTimeNs;
            private long firstFrameDurationNs;
            private long lastFramePresentationTimeNs;
            private long frameCount;
            private long matchingFrameCount;
            private long matchingFrameDurationSumNs;
            private final boolean[] recentFrameOutlierFlags =
                    new boolean[CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC];
            private int recentFrameOutlierCount;

            Matcher() {
                reset();
            }

            void reset() {
                firstFramePresentationTimeNs = TIME_UNSET;
                firstFrameDurationNs = TIME_UNSET;
                lastFramePresentationTimeNs = TIME_UNSET;
                frameCount = 0;
                matchingFrameCount = 0;
                matchingFrameDurationSumNs = 0;
                recentFrameOutlierCount = 0;
                Arrays.fill(recentFrameOutlierFlags, false);
            }

            boolean isSynced() {
                return frameCount > CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC
                        && recentFrameOutlierCount == 0;
            }

            boolean isLastFrameOutlier() {
                if (frameCount == 0) {
                    return false;
                }
                return recentFrameOutlierFlags[getRecentFrameOutlierIndex(frameCount - 1)];
            }

            long getFrameDurationNs() {
                return matchingFrameCount == 0
                        ? TIME_UNSET : matchingFrameDurationSumNs / matchingFrameCount;
            }

            void onNextFrame(long framePresentationTimeNs) {
                if (frameCount == 0) {
                    firstFramePresentationTimeNs = framePresentationTimeNs;
                } else if (frameCount == 1) {
                    firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs;
                    matchingFrameDurationSumNs = firstFrameDurationNs;
                    matchingFrameCount = 1;
                } else {
                    long lastFrameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs;
                    int outlierIndex = getRecentFrameOutlierIndex(frameCount);
                    boolean isMatch = Math.abs(lastFrameDurationNs - firstFrameDurationNs)
                            <= MAX_MATCHING_FRAME_DIFFERENCE_NS;
                    if (isMatch) {
                        matchingFrameCount++;
                        matchingFrameDurationSumNs += lastFrameDurationNs;
                        if (recentFrameOutlierFlags[outlierIndex]) {
                            recentFrameOutlierFlags[outlierIndex] = false;
                            recentFrameOutlierCount--;
                        }
                    } else if (!recentFrameOutlierFlags[outlierIndex]) {
                        recentFrameOutlierFlags[outlierIndex] = true;
                        recentFrameOutlierCount++;
                    }
                }
                frameCount++;
                lastFramePresentationTimeNs = framePresentationTimeNs;
            }

            private static int getRecentFrameOutlierIndex(long frameCount) {
                return (int) (frameCount % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC);
            }
        }
    }

    private abstract static class VSyncSampler implements DisplayManager.DisplayListener {
        final Choreographer choreographer;
        final DisplayManager displayManager;
        final Handler handler;
        volatile long sampledVsyncId = TIME_UNSET;
        volatile long sampledVsyncTimeNs = TIME_UNSET;
        volatile long sampledAtNs = TIME_UNSET;
        volatile long vsyncDurationNs = TIME_UNSET;
        volatile long expectedPresentationTimeNs = TIME_UNSET;
        volatile long deadlineNs = TIME_UNSET;
        long previousExpectedPresentationTimeNs = TIME_UNSET;
        boolean registered;

        VSyncSampler(Choreographer choreographer, DisplayManager displayManager, Handler handler) {
            this.choreographer = choreographer;
            this.displayManager = displayManager;
            this.handler = handler;
        }

        abstract void register();

        abstract void unregister();

        final void publishNativeGrid() {
            publishNativeGridSample(expectedPresentationTimeNs, vsyncDurationNs,
                    sampledVsyncTimeNs, deadlineNs);
        }

        final long effectiveVsyncAnchorTimeNs(long nowNs) {
            return VsyncCadenceMath.projectFreshAnchor(sampledAtNs,
                    expectedPresentationTimeNs, sampledVsyncTimeNs, vsyncDurationNs, nowNs,
                    MAX_VSYNC_SAMPLE_AGE_NS, MIN_VSYNC_DURATION_NS, MAX_VSYNC_DURATION_NS);
        }

        @Override
        public final void onDisplayAdded(int displayId) {
            // No action required.
        }

        @Override
        public final void onDisplayRemoved(int displayId) {
            // No action required.
        }
    }

    private static final class VSyncSamplerBase extends VSyncSampler
            implements Choreographer.FrameCallback {
        VSyncSamplerBase(Choreographer choreographer, DisplayManager displayManager,
                         Handler handler) {
            super(choreographer, displayManager, handler);
        }

        @Override
        void register() {
            if (registered) {
                return;
            }
            registered = true;
            displayManager.registerDisplayListener(this, handler);
            choreographer.removeFrameCallback(this);
            choreographer.postFrameCallback(this);
            vsyncDurationNs = displayRefreshPeriodNs(displayManager);
        }

        @Override
        void unregister() {
            if (!registered) {
                return;
            }
            registered = false;
            displayManager.unregisterDisplayListener(this);
            choreographer.removeFrameCallback(this);
            sampledVsyncId = TIME_UNSET;
            sampledVsyncTimeNs = TIME_UNSET;
            sampledAtNs = TIME_UNSET;
            vsyncDurationNs = TIME_UNSET;
            expectedPresentationTimeNs = TIME_UNSET;
            deadlineNs = TIME_UNSET;
            previousExpectedPresentationTimeNs = TIME_UNSET;
        }

        @Override
        public void doFrame(long vsyncTimeNs) {
            sampledVsyncId = TIME_UNSET;
            sampledVsyncTimeNs = vsyncTimeNs;
            sampledAtNs = System.nanoTime();
            expectedPresentationTimeNs = vsyncTimeNs;
            deadlineNs = TIME_UNSET;
            choreographer.removeFrameCallback(this);
            choreographer.postFrameCallback(this);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && registered) {
                choreographer.removeFrameCallback(this);
                choreographer.postFrameCallback(this);
                vsyncDurationNs = displayRefreshPeriodNs(displayManager);
            }
        }
    }

    private static final class VSyncSamplerV33 extends VSyncSampler
            implements Choreographer.VsyncCallback {
        VSyncSamplerV33(Choreographer choreographer, DisplayManager displayManager,
                        Handler handler) {
            super(choreographer, displayManager, handler);
        }

        @Override
        void register() {
            if (registered) {
                return;
            }
            registered = true;
            displayManager.registerDisplayListener(this, handler);
            choreographer.removeVsyncCallback(this);
            choreographer.postVsyncCallback(this);
        }

        @Override
        void unregister() {
            if (!registered) {
                return;
            }
            registered = false;
            displayManager.unregisterDisplayListener(this);
            handler.removeCallbacksAndMessages(null);
            choreographer.removeVsyncCallback(this);
            sampledVsyncId = TIME_UNSET;
            sampledVsyncTimeNs = TIME_UNSET;
            sampledAtNs = TIME_UNSET;
            vsyncDurationNs = TIME_UNSET;
            expectedPresentationTimeNs = TIME_UNSET;
            deadlineNs = TIME_UNSET;
            previousExpectedPresentationTimeNs = TIME_UNSET;
        }

        @Override
        public void onVsync(Choreographer.FrameData data) {
            sampledVsyncTimeNs = data.getFrameTimeNanos();
            sampledAtNs = System.nanoTime();
            Choreographer.FrameTimeline preferred = data.getPreferredFrameTimeline();
            if (preferred == null) {
                sampledVsyncId = TIME_UNSET;
                expectedPresentationTimeNs = sampledVsyncTimeNs;
                deadlineNs = TIME_UNSET;
            } else {
                sampledVsyncId = preferred.getVsyncId();
                expectedPresentationTimeNs = preferred.getExpectedPresentationTimeNanos();
                deadlineNs = preferred.getDeadlineNanos();
            }
            Choreographer.FrameTimeline[] timelines = data.getFrameTimelines();
            long measuredDurationNs = TIME_UNSET;
            if (timelines.length >= 2) {
                measuredDurationNs = timelines[1].getExpectedPresentationTimeNanos()
                        - timelines[0].getExpectedPresentationTimeNanos();
            } else if (previousExpectedPresentationTimeNs != TIME_UNSET
                    && expectedPresentationTimeNs > previousExpectedPresentationTimeNs) {
                measuredDurationNs = expectedPresentationTimeNs
                        - previousExpectedPresentationTimeNs;
            }
            if (measuredDurationNs >= MIN_VSYNC_DURATION_NS
                    && measuredDurationNs <= MAX_VSYNC_DURATION_NS) {
                vsyncDurationNs = measuredDurationNs;
            }
            previousExpectedPresentationTimeNs = expectedPresentationTimeNs;
            publishNativeGrid();
            if (registered) {
                choreographer.postVsyncCallback(this);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && registered) {
                handler.removeCallbacksAndMessages(null);
                choreographer.removeVsyncCallback(this);
                choreographer.postVsyncCallback(this);
            }
        }
    }

    private static long displayRefreshPeriodNs(DisplayManager displayManager) {
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getRefreshRate() <= 0f) {
            return TIME_UNSET;
        }
        return (long) (1_000_000_000.0 / display.getRefreshRate());
    }
}
