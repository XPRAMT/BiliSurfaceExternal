package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runtime-only Media3 proof of concept for the official Bilibili player.
 *
 * <p>The bridge deliberately uses reflection and the target application's class loader. The
 * official APK already contains Media3, so bundling another Media3 copy in this module would
 * create class-loader identity conflicts. This class only replaces the video/audio playback
 * start path; danmaku, subtitles, statistics and the official player UI remain untouched.</p>
 */
final class Media3MinimalPlayerBridge {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String IJK_PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer";
    private static final String IJK_ITEM = "tv.danmaku.ijk.media.player.IjkMediaPlayerItem";
    private static final String IJK_ASSET = "tv.danmaku.ijk.media.player.IjkMediaAsset";
    private static final String IJK_CONFIG = "tv.danmaku.ijk.media.player.IjkMediaConfigParams";
    private static final String LIVE_P0_PLAY_URL_SELECTOR =
            "com.bilibili.bililive.liveplayer.params.LiveP0PlayUrlSelector";
    private static final String LIVE_PLAY_URL_V2 =
            "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.LivePlayUrlV2";
    private static final String LIVE_ROOM_QN =
            "com.bilibili.bililive.blps.liveplayer.apis.beans.url.v2.LiveRoomQn";
    private static final String MEDIA3_FRAME_METADATA_LISTENER =
            "androidx.media3.exoplayer.video.VideoFrameMetadataListener";
    private static final String MEDIA3_PLAYER_LISTENER = "androidx.media3.common.Player$Listener";

    private static final Map<Object, PlayerState> STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, PlayerState>());
    private static final Map<Object, SourcePair> SOURCES_BY_ITEM =
            Collections.synchronizedMap(new WeakHashMap<Object, SourcePair>());
    private static final Set<String> REPORTED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Object TRACK_SELECTION_LOCK = new Object();
    private static final Map<Integer, String> AUDIO_LABELS_BY_ID = new HashMap<Integer, String>();
    private static final Map<Integer, String> KNOWN_AUDIO_LABELS_BY_ID =
            new HashMap<Integer, String>();
    private static final Map<Integer, List<String>> AUDIO_URLS_BY_ID =
            new HashMap<Integer, List<String>>();
    private static final Map<Integer, String> VIDEO_LABELS_BY_ID = new HashMap<Integer, String>();
    private static volatile String currentVideoLabel = "unknown";
    private static volatile String currentAudioLabel = "無音訊";

    private static volatile boolean installed;
    private static volatile boolean sourceCaptureInstalled;
    private static volatile boolean trackSwitchHooksInstalled;
    private static volatile boolean decoderInfoProbeInstalled;
    private static volatile boolean liveQualityHookInstalled;
    private static volatile ClassLoader targetClassLoader;
    private static final Handler DECODER_INFO_HANDLER = new Handler(Looper.getMainLooper());
    private static final int LIVE_AUDIO_SAMPLE_MAX_BYTES = 8 * 1024 * 1024;
    private static final long LIVE_AUDIO_SAMPLE_WINDOW_MS = 6_000L;
    private static final int VOD_AUDIO_SAMPLE_MAX_SEGMENTS = 6;
    private static final int VOD_AUDIO_SAMPLE_MAX_BYTES = 2 * 1024 * 1024;

    private Media3MinimalPlayerBridge() {
    }

    static synchronized String install(ClassLoader classLoader) {
        if (installed) {
            return "minimal Media3 player hooks already installed";
        }
        try {
            targetClassLoader = classLoader;
            final Class<?> playerClass = XposedHelpers.findClass(IJK_PLAYER, classLoader);
            final Class<?> itemClass = XposedHelpers.findClass(IJK_ITEM, classLoader);
            final Class<?> assetClass = XposedHelpers.findClass(IJK_ASSET, classLoader);
            final Class<?> configClass = XposedHelpers.findClass(IJK_CONFIG, classLoader);

            installLiveQualitySelectionHook(classLoader);
            boolean itemInitHooked = installSourceCapture(itemClass, assetClass, configClass);
            boolean itemBindingHooked = hookItemBinding(playerClass, itemClass);
            installDecoderInfoProbe(playerClass, false);
            boolean surfaceHooked = hookSurface(playerClass);
            hookVideoDisplayCallback(playerClass);
            installTrackSwitchHooks(playerClass);

            tryHookSetDisplay(playerClass);
            boolean startHooked = hookLifecycleMethod(playerClass, "start", true);
            hookLifecycleMethod(playerClass, "pause", false);
            hookLifecycleMethod(playerClass, "release", false);
            hookItemStart(itemClass);

            if (!itemBindingHooked || !startHooked) {
                return "minimal Media3 player hooks unavailable=required-hook-missing"
                        + ",itemInit=" + itemInitHooked
                        + ",itemBinding=" + itemBindingHooked
                        + ",surface=" + surfaceHooked
                        + ",start=" + startHooked;
            }
            installed = true;
            return "installed runtime Media3 minimal player hooks"
                    + ",itemInit=" + itemInitHooked
                    + ",surface=" + surfaceHooked;
        } catch (Throwable throwable) {
            return "minimal Media3 player hooks unavailable="
                    + describe(throwable);
        }
    }

    /**
     * Installs only the read-only IJK asset observer needed by the stream overlay. It deliberately
     * does not hook start, Surface, MediaCodec, or any Media3 lifecycle method.
     */
    static synchronized String installStreamSelectionProbe(ClassLoader classLoader) {
        if (sourceCaptureInstalled) {
            return "stream selection probe already installed";
        }
        try {
            targetClassLoader = classLoader;
            installLiveQualitySelectionHook(classLoader);
            Class<?> itemClass = XposedHelpers.findClass(IJK_ITEM, classLoader);
            Class<?> assetClass = XposedHelpers.findClass(IJK_ASSET, classLoader);
            Class<?> configClass = XposedHelpers.findClass(IJK_CONFIG, classLoader);
            Class<?> playerClass = XposedHelpers.findClass(IJK_PLAYER, classLoader);
            installTrackSwitchHooks(playerClass);
            installDecoderInfoProbe(playerClass, true);
            return installSourceCapture(itemClass, assetClass, configClass)
                    ? "installed read-only IJK stream selection probe"
                    : "stream selection probe unavailable=item-init-hook-missing";
        } catch (Throwable throwable) {
            return "stream selection probe unavailable=" + describe(throwable);
        }
    }

    private static boolean installSourceCapture(Class<?> itemClass, Class<?> assetClass,
                                                Class<?> configClass) {
        if (sourceCaptureInstalled) {
            return true;
        }
        boolean hooked = hookItemInit(itemClass, assetClass, configClass);
        if (hooked) {
            sourceCaptureInstalled = true;
        }
        return hooked;
    }

    /**
     * Live playback chooses one complete HLS/FLV URL before IJK is created. It does not expose
     * the VOD DASH audio-track IDs used by switchDashAudioStream(). Raise the requested
     * LiveRoomQn to the highest URL candidate only when the independent preference is enabled;
     * when disabled, this hook leaves Bilibili's argument untouched.
     */
    private static synchronized void installLiveQualitySelectionHook(ClassLoader classLoader) {
        if (liveQualityHookInstalled) {
            return;
        }
        try {
            Class<?> selectorClass = XposedHelpers.findClass(
                    LIVE_P0_PLAY_URL_SELECTOR, classLoader);
            Class<?> playUrlClass = XposedHelpers.findClass(LIVE_PLAY_URL_V2, classLoader);
            Class<?> roomQnClass = XposedHelpers.findClass(LIVE_ROOM_QN, classLoader);
            int hooked = 0;
            for (final Method method : allMethods(selectorClass)) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean legacy = Modifier.isStatic(method.getModifiers())
                        && "d".equals(method.getName())
                        && parameterTypes.length == 6
                        && parameterTypes[0] == playUrlClass
                        && parameterTypes[1] == Integer.TYPE
                        && parameterTypes[2] == roomQnClass
                        && parameterTypes[3] == Integer.TYPE
                        && "kotlin.Pair".equals(parameterTypes[4].getName())
                        && parameterTypes[5] == Context.class;
                boolean current = !Modifier.isStatic(method.getModifiers())
                        && "startPlayUrlSelect".equals(method.getName())
                        && parameterTypes.length == 7
                        && parameterTypes[0] == playUrlClass
                        && parameterTypes[1] == Integer.TYPE
                        && parameterTypes[2] == Integer.TYPE
                        && parameterTypes[3] == roomQnClass
                        && parameterTypes[4] == Integer.TYPE
                        && "kotlin.Pair".equals(parameterTypes[5].getName())
                        && parameterTypes[6] == Context.class;
                if (!legacy && !current) {
                    continue;
                }
                final int roomQnArgument = current ? 3 : 2;
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ModulePreferences.loadAutoSelectBestAudioForHook()) {
                                adjustLiveQualityArgument(param, roomQnArgument);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ModulePreferences.loadAutoSelectBestAudioForHook()
                                    || ModulePreferences.loadPlaybackStreamOverlayForHook()) {
                                reportLiveQualityResult(param);
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable throwable) {
                    logOnce("live-quality-hook:" + method.toGenericString(),
                            "live quality selector hook failed=" + describe(throwable));
                }
            }
            if (hooked > 0) {
                liveQualityHookInstalled = true;
                logOnce("live-quality-hook-installed",
                        "hooked LiveP0PlayUrlSelector methods=" + hooked);
            } else {
                logOnce("live-quality-hook-missing",
                        "LiveP0PlayUrlSelector method signature not found");
            }
        } catch (Throwable throwable) {
            logOnce("live-quality-hook-unavailable",
                    "live quality selector unavailable=" + describe(throwable));
        }
    }

    private static void adjustLiveQualityArgument(XC_MethodHook.MethodHookParam param,
                                                  int roomQnArgument) {
        try {
            Object playUrls = param.args == null || param.args.length == 0
                    ? null : param.args[0];
            Object requested = param.args == null || param.args.length <= roomQnArgument
                    ? null : param.args[roomQnArgument];
            int currentQnId = roomQnInt(requested, "getQnId", 0);
            List<LiveUrlCandidate> urlCandidates = liveUrlCandidates(playUrls);
            List<LiveQualitySelector.Candidate> candidates = liveQualityCandidates(playUrls);
            LiveUrlCandidate selectedUrl = chooseHighestLiveUrl(urlCandidates, currentQnId);
            LiveQualitySelector.Candidate selected = selectedUrl == null
                    ? null : selectedUrl.candidate;
            if (selected == null) {
                Log.i(TAG, "[DEBUG-live-quality-selection] no usable live quality candidate"
                        + " requested=" + describeRoomQn(requested)
                        + " candidates=" + describeLiveCandidates(candidates));
                return;
            }
            if (requested == null) {
                return;
            }
            int currentQn = roomQnInt(requested, "getQn", 0);
            if (currentQn == selected.qn && currentQnId == selected.qnId) {
                Log.i(TAG, "[DEBUG-live-quality-selection] already-highest"
                        + " requested=" + describeRoomQn(requested)
                        + " selected=" + describeCandidate(selected)
                        + " candidates=" + describeLiveCandidates(candidates)
                        + " urlFormats=" + describeLiveUrlFormats(urlCandidates));
                return;
            }
            setRoomQn(requested, selected);
            Log.i(TAG, "[DEBUG-live-quality-selection] override-request"
                    + " requestedQn=" + currentQn + " requestedQnId=" + currentQnId
                    + " selected=" + describeCandidate(selected)
                    + " candidates=" + describeLiveCandidates(candidates)
                    + " urlFormats=" + describeLiveUrlFormats(urlCandidates));
        } catch (Throwable throwable) {
            logOnce("live-quality-selection-error",
                    "live quality selection failed=" + describe(throwable));
        }
    }

    private static void reportLiveQualityResult(XC_MethodHook.MethodHookParam param) {
        try {
            Object result = param.getResult();
            Object qnInfo = result == null ? null : invoke(result, "getQnInfo");
            int qn = roomQnInt(qnInfo, "getQn", 0);
            int qnId = roomQnInt(qnInfo, "getQnId", 0);
            int hdrType = roomQnInt(qnInfo, "getHdrType", 0);
            int urlBandwidth = result == null ? -1 : targetInt(result, "getBw", -1);
            int codec = result == null ? -1 : targetInt(result, "getCodec", -1);
            String format = result == null ? "" : targetString(result, "getFormat", "");
            Log.i(TAG, "[DEBUG-live-quality-selection] selected-result"
                    + " qn=" + qn + " qnId=" + qnId + " hdrType=" + hdrType
                    + " urlBw=" + urlBandwidth + " codec=" + codec
                    + " format=" + normalizeLiveFormat(format)
                    + " resultPresent=" + (result != null));
            if (ModulePreferences.loadPlaybackStreamOverlayForHook()) {
                String sourceUrl = liveSourceUrl(result);
                if (!sourceUrl.isEmpty()) {
                    PlaybackStreamOverlay.beginLiveAudioBitrateMeasurementForSource(sourceUrl);
                    probeLiveAudioBitrateOnce(sourceUrl);
                } else {
                    PlaybackStreamOverlay.beginLiveAudioBitrateMeasurement();
                    logOnce("live-bitrate-source-missing",
                            "[DEBUG-live-bitrate] selected live URL unavailable");
                }
            }
        } catch (Throwable throwable) {
            logOnce("live-quality-result-error",
                    "live quality result unavailable=" + describe(throwable));
        }
    }

    private static List<LiveQualitySelector.Candidate> liveQualityCandidates(Object playUrls) {
        List<LiveQualitySelector.Candidate> candidates =
                new ArrayList<LiveQualitySelector.Candidate>();
        if (playUrls == null) {
            return candidates;
        }
        for (LiveUrlCandidate candidate : liveUrlCandidates(playUrls)) {
            candidates.add(candidate.candidate);
        }
        addLiveRoomQnCandidates(candidates, fieldValue(playUrls, "k"));
        addLiveRoomQnCandidates(candidates, fieldValue(playUrls, "l"));
        addLiveRoomQnCandidates(candidates, fieldValue(playUrls, "n"));
        return candidates;
    }

    private static List<LiveUrlCandidate> liveUrlCandidates(Object playUrls) {
        List<LiveUrlCandidate> candidates = new ArrayList<LiveUrlCandidate>();
        if (playUrls == null) {
            return candidates;
        }
        addLiveUrlCandidates(candidates, fieldValue(playUrls, "c"));
        addLiveUrlCandidates(candidates, fieldValue(playUrls, "d"));
        return candidates;
    }

    private static void addLiveUrlCandidates(List<LiveUrlCandidate> candidates, Object value) {
        if (!(value instanceof Iterable)) {
            return;
        }
        for (Object url : (Iterable<?>) value) {
            if (url == null) {
                continue;
            }
            try {
                Object qnInfo = invoke(url, "getQnInfo");
                int qn = roomQnInt(qnInfo, "getQn", 0);
                int qnId = roomQnInt(qnInfo, "getQnId", 0);
                int hdrType = roomQnInt(qnInfo, "getHdrType", 0);
                candidates.add(new LiveUrlCandidate(
                        new LiveQualitySelector.Candidate(
                                qn, qnId, hdrType, targetInt(url, "getBw", -1),
                                targetInt(url, "getCodec", -1)),
                        targetString(url, "getFormat", "")));
            } catch (Throwable ignored) {
                // An optional malformed URL candidate must not break the official selector.
            }
        }
    }

    private static LiveUrlCandidate chooseHighestLiveUrl(
            List<LiveUrlCandidate> candidates, int currentQnId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<LiveQualitySelector.Candidate> qualities =
                new ArrayList<LiveQualitySelector.Candidate>();
        for (LiveUrlCandidate candidate : candidates) {
            if (candidate != null) {
                qualities.add(candidate.candidate);
            }
        }
        LiveQualitySelector.Candidate selected = LiveQualitySelector.chooseHighest(
                qualities, currentQnId);
        if (selected == null) {
            return null;
        }
        for (LiveUrlCandidate candidate : candidates) {
            if (candidate != null && sameLiveCandidate(candidate.candidate, selected)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameLiveCandidate(LiveQualitySelector.Candidate left,
                                             LiveQualitySelector.Candidate right) {
        return left.qn == right.qn
                && left.qnId == right.qnId
                && left.hdrType == right.hdrType
                && left.urlBandwidth == right.urlBandwidth
                && left.codec == right.codec;
    }

    private static void addLiveRoomQnCandidates(
            List<LiveQualitySelector.Candidate> candidates, Object value) {
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                addLiveRoomQnCandidate(candidates, item);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                addLiveRoomQnCandidate(candidates, item);
            }
        }
    }

    private static void addLiveRoomQnCandidate(
            List<LiveQualitySelector.Candidate> candidates, Object qnInfo) {
        if (qnInfo == null) {
            return;
        }
        int qn = roomQnInt(qnInfo, "getQn", 0);
        int qnId = roomQnInt(qnInfo, "getQnId", 0);
        int hdrType = roomQnInt(qnInfo, "getHdrType", 0);
        candidates.add(new LiveQualitySelector.Candidate(qn, qnId, hdrType, -1, -1));
    }

    private static Object fieldValue(Object receiver, String fieldName) {
        try {
            return findField(receiver.getClass(), fieldName).get(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int roomQnInt(Object receiver, String methodName, int fallback) {
        return targetInt(receiver, methodName, fallback);
    }

    private static int targetInt(Object receiver, String methodName, int fallback) {
        if (receiver == null) {
            return fallback;
        }
        try {
            Object value = invoke(receiver, methodName);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String targetString(Object receiver, String methodName, String fallback) {
        if (receiver == null) {
            return fallback;
        }
        try {
            Object value = invoke(receiver, methodName);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setRoomQn(Object receiver, LiveQualitySelector.Candidate selected)
            throws Exception {
        Method setQn = findMethod(receiver.getClass(), "setQn", new Class<?>[]{int.class});
        Method setHdrType = findMethod(receiver.getClass(), "setHdrType",
                new Class<?>[]{int.class});
        setQn.setAccessible(true);
        setHdrType.setAccessible(true);
        setQn.invoke(receiver, Integer.valueOf(selected.qn));
        setHdrType.invoke(receiver, Integer.valueOf(selected.hdrType));
    }

    private static String describeRoomQn(Object qnInfo) {
        if (qnInfo == null) {
            return "none";
        }
        return "qn=" + roomQnInt(qnInfo, "getQn", 0)
                + "/qnId=" + roomQnInt(qnInfo, "getQnId", 0)
                + "/hdr=" + roomQnInt(qnInfo, "getHdrType", 0);
    }

    private static String describeCandidate(LiveQualitySelector.Candidate candidate) {
        return "qn=" + candidate.qn + "/qnId=" + candidate.qnId
                + "/hdr=" + candidate.hdrType + "/urlBw=" + candidate.urlBandwidth
                + "/codec=" + candidate.codec;
    }

    private static String describeLiveCandidates(
            List<LiveQualitySelector.Candidate> candidates) {
        StringBuilder result = new StringBuilder();
        for (LiveQualitySelector.Candidate candidate : candidates) {
            if (candidate == null || candidate.qn <= 0 || candidate.qnId <= 0) {
                continue;
            }
            if (result.length() > 0) {
                result.append(';');
            }
            result.append(describeCandidate(candidate));
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static String describeLiveUrlFormats(List<LiveUrlCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "none";
        }
        Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
        for (LiveUrlCandidate candidate : candidates) {
            if (candidate == null || candidate.candidate.qn <= 0
                    || candidate.candidate.qnId <= 0) {
                continue;
            }
            String format = normalizeLiveFormat(candidate.format);
            Integer count = counts.get(format);
            counts.put(format, count == null ? 1 : count + 1);
        }
        if (counts.isEmpty()) {
            return "none";
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (result.length() > 0) {
                result.append(';');
            }
            result.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return result.toString();
    }

    private static String normalizeLiveFormat(String format) {
        if (format == null || format.trim().isEmpty()) {
            return "unknown";
        }
        return format.trim().toLowerCase(java.util.Locale.US);
    }

    private static boolean hookItemInit(Class<?> itemClass, Class<?> assetClass,
                                        Class<?> configClass) {
        int hooked = 0;
        for (Method method : allMethods(itemClass)) {
            if (!"init".equals(method.getName()) || method.getParameterTypes().length != 2) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!canCarry(parameterTypes[0], assetClass)
                    && !canCarry(parameterTypes[1], assetClass)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object asset = findAssetArgument(param.args, assetClass);
                        if (ModulePreferences.loadAutoSelectBestAudioForHook()) {
                            preferHighestBandwidthAudio(asset);
                        } else {
                            logOnce("highest-audio-disabled",
                                    "IJK best-audio selection disabled; preserving defaultAudioId");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object asset = findAssetArgument(param.args, assetClass);
                        SourcePair pair = extractSourcePair(asset);
                        if (pair != null && pair.hasVideo()) {
                            SOURCES_BY_ITEM.put(param.thisObject, pair);
                            logOnce("source:" + pair.key(),
                                    "captured video/audio source videoSegments="
                                            + pair.videoUrls.size() + " audioSegments="
                                            + pair.audioUrls.size() + " details="
                                            + pair.describe());
                        }
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                logOnce("item-init-hook:" + method.toGenericString(),
                        "IJK item init hook failed=" + describe(throwable));
            }
        }
        if (hooked == 0) {
            logOnce("item-init-missing", "IJK item init method not found");
        }
        return hooked > 0;
    }

    /**
     * IJK's live audio is normally decoded by FFmpeg in the service, not by MediaCodec.  The
     * public IjkMediaPlayer.getMediaInfo() bridge exposes the native codec metadata in the main
     * process, so observe it instead of assuming that a missing MediaCodec audio callback means
     * that the stream has no audio.
     */
    private static synchronized void installDecoderInfoProbe(Class<?> playerClass,
                                                              boolean scheduleAfterStart) {
        if (decoderInfoProbeInstalled) {
            return;
        }
        int hooked = 0;
        for (Method method : allMethods(playerClass)) {
            if ("getMediaInfo".equals(method.getName())
                    && method.getParameterTypes().length == 0) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && param.getResult() != null) {
                                inspectActualAudioDecoder(param.thisObject, param.getResult());
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable throwable) {
                    logOnce("decoder-info-hook:" + method.toGenericString(),
                            "IJK getMediaInfo hook failed=" + describe(throwable));
                }
            } else if (scheduleAfterStart && "start".equals(method.getName())
                    && method.getParameterTypes().length == 0) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            scheduleActualAudioDecoderProbe(param.thisObject);
                        }
                    });
                    hooked++;
                } catch (Throwable throwable) {
                    logOnce("decoder-info-start-hook:" + method.toGenericString(),
                            "IJK audio decoder probe start hook failed=" + describe(throwable));
                }
            }
        }
        decoderInfoProbeInstalled = hooked > 0;
        if (decoderInfoProbeInstalled) {
            logOnce("decoder-info-probe", "installed IJK native audio decoder metadata probe");
        } else {
            logOnce("decoder-info-probe-missing",
                    "IJK native audio decoder metadata probe methods not found");
        }
    }

    private static void scheduleActualAudioDecoderProbe(final Object player) {
        if (!ModulePreferences.loadPlaybackStreamOverlayForHook() || player == null) {
            return;
        }
        final WeakReference<Object> reference = new WeakReference<Object>(player);
        long[] delaysMs = new long[]{0L, 300L, 1000L, 2500L, 5000L};
        for (long delayMs : delaysMs) {
            DECODER_INFO_HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Object target = reference.get();
                    if (target != null) {
                        // The getMediaInfo after-hook consumes the returned value.
                        try {
                            invoke(target, "getMediaInfo");
                        } catch (Throwable throwable) {
                            logOnce("scheduled-audio-info-error",
                                    "IJK audio metadata query failed=" + describe(throwable));
                        }
                    }
                }
            }, delayMs);
        }
    }

    private static void inspectActualAudioDecoder(Object player, Object mediaInfo) {
        if (!ModulePreferences.loadPlaybackStreamOverlayForHook() || player == null) {
            return;
        }
        try {
            if (mediaInfo == null) {
                return;
            }
            Object info = mediaInfo;
            if (ModulePreferences.loadAutoSelectBestAudioForHook()) {
                selectHighestNativeAudioStream(player, info);
            }
            String decoder = fieldString(info, "mAudioDecoder");
            String implementation = fieldString(info, "mAudioDecoderImpl");
            String label = StreamCodecLabel.audioForDecoderName(decoder, implementation);
            if ("UNKNOWN".equals(label)) {
                return;
            }
            Object audioStream = activeAudioStream(player, info);
            if (audioStream == null) {
                return;
            }
            long bitrate = fieldLong(audioStream, "mBitrate", -1L);
            int sampleRateHz = fieldInt(audioStream, "mSampleRate", -1);
            PlaybackStreamOverlay.updateActualDecoderAudioLabel(label, bitrate, sampleRateHz);
            logOnce("actual-audio-decoder:" + decoder + ':' + implementation + ':' + bitrate
                            + ':' + sampleRateHz,
                    "observed IJK audio decoder=" + decoder + " implementation="
                            + implementation + " bitrate=" + bitrate + " sampleRateHz="
                            + sampleRateHz + " label=" + label);
        } catch (Throwable throwable) {
            logOnce("actual-audio-decoder-error",
                    "IJK native audio decoder metadata unavailable=" + describe(throwable));
        }
    }

    /**
     * Live playback may use a normal/container stream instead of a DASH asset.  In that path
     * defaultAudioId is not consulted.  IJK exposes the native stream indexes and the parsed
     * per-stream bitrates through MediaInfo, so select the highest real audio stream there.
     */
    private static void selectHighestNativeAudioStream(Object player, Object mediaInfo) {
        try {
            Object meta = findField(mediaInfo.getClass(), "mMeta").get(mediaInfo);
            if (meta == null) {
                return;
            }
            Object streamObject = findField(meta.getClass(), "mStreams").get(meta);
            if (!(streamObject instanceof List)) {
                return;
            }
            List<NativeAudioTrackSelector.Track> tracks = new ArrayList<NativeAudioTrackSelector.Track>();
            for (Object stream : (List<?>) streamObject) {
                if (stream == null || !"audio".equalsIgnoreCase(
                        fieldString(stream, "mType"))) {
                    continue;
                }
                int index = fieldInt(stream, "mIndex", -1);
                long bitrate = fieldLong(stream, "mBitrate", -1L);
                tracks.add(new NativeAudioTrackSelector.Track(index, bitrate));
            }
            int[] availableIndexes = nativeAudioStreamIndexes(player);
            int currentIndex = nativeAudioIndex(player, "getCurrentAudioIndex", -1);
            int selectedIndex = NativeAudioTrackSelector.chooseHighestBitrateIndex(
                    tracks, availableIndexes, currentIndex);
            if (selectedIndex < 0) {
                return;
            }
            if (selectedIndex == currentIndex) {
                logOnce("live-audio-selection-already:" + selectedIndex,
                        "native audio selection already highest index=" + selectedIndex
                                + " bitrate=" + bitrateForTrack(tracks, selectedIndex)
                                + " candidates=" + describeTracks(tracks)
                                + " available=" + describeIndexes(availableIndexes)
                                + " current=" + currentIndex);
                return;
            }
            Object result = invoke(player, "switchAudioStream",
                    new Class<?>[]{int.class}, new Object[]{Integer.valueOf(selectedIndex)});
            int resultCode = result instanceof Number ? ((Number) result).intValue() : -1;
            int currentAfter = nativeAudioIndex(player, "getCurrentAudioIndex", -1);
            Log.i(TAG, "[DEBUG-live-audio-selection] candidates=" + describeTracks(tracks)
                    + " available=" + describeIndexes(availableIndexes)
                    + " current=" + currentIndex
                    + " selected=" + selectedIndex
                    + " selectedBitrate=" + bitrateForTrack(tracks, selectedIndex)
                    + " switchResult=" + resultCode
                    + " currentAfter=" + currentAfter);
        } catch (Throwable throwable) {
            logOnce("live-audio-selection-error",
                    "native highest-bandwidth audio selection failed=" + describe(throwable));
        }
    }

    private static int[] nativeAudioStreamIndexes(Object player) {
        try {
            Object value = invoke(player, "getAudioStreamsIndex");
            return value instanceof int[] ? (int[]) value : new int[0];
        } catch (Throwable ignored) {
            return new int[0];
        }
    }

    private static int nativeAudioIndex(Object player, String methodName, int fallback) {
        try {
            Object value = invoke(player, methodName);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int fieldInt(Object receiver, String fieldName, int fallback) {
        try {
            Object value = findField(receiver.getClass(), fieldName).get(receiver);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long fieldLong(Object receiver, String fieldName, long fallback) {
        try {
            Object value = findField(receiver.getClass(), fieldName).get(receiver);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long bitrateForTrack(List<NativeAudioTrackSelector.Track> tracks, int index) {
        for (NativeAudioTrackSelector.Track track : tracks) {
            if (track.index == index) {
                return track.bitrate;
            }
        }
        return -1L;
    }

    private static String describeTracks(List<NativeAudioTrackSelector.Track> tracks) {
        StringBuilder result = new StringBuilder();
        for (NativeAudioTrackSelector.Track track : tracks) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(track.index).append(':').append(track.bitrate);
        }
        return result.toString();
    }

    private static String describeIndexes(int[] indexes) {
        if (indexes == null || indexes.length == 0) {
            return "none";
        }
        StringBuilder result = new StringBuilder();
        for (int index : indexes) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(index);
        }
        return result.toString();
    }

    private static Object activeAudioStream(Object player, Object mediaInfo) {
        try {
            Object meta = findField(mediaInfo.getClass(), "mMeta").get(mediaInfo);
            if (meta == null) {
                return null;
            }
            Object audioStream = findField(meta.getClass(), "mAudioStream").get(meta);
            if (audioStream == null) {
                return null;
            }
            int metadataIndex = fieldInt(audioStream, "mIndex", -1);
            int activeIndex = nativeAudioIndex(player, "getCurrentAudioIndex", -1);
            // A stream switch may finish between MediaInfo.parse() and this callback.
            // Do not publish the previous track's sample rate as the new track's rate.
            if (metadataIndex >= 0 && activeIndex >= 0 && metadataIndex != activeIndex) {
                return null;
            }
            return audioStream;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String fieldString(Object receiver, String fieldName) {
        try {
            Object value = findField(receiver.getClass(), fieldName).get(receiver);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void installTrackSwitchHooks(Class<?> playerClass) {
        if (trackSwitchHooksInstalled) {
            return;
        }
        int hooked = 0;
        for (final Method method : allMethods(playerClass)) {
            final boolean audio = "switchDashAudioStream".equals(method.getName());
            final boolean video = "switchDashVideoStream".equals(method.getName());
            if ((!audio && !video) || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != Integer.TYPE) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // A failed switch call does not establish that IJK actually changed its
                        // active stream.  Keep the existing label rather than reporting the
                        // requested track as if it had become the decoder input.
                        if (param.hasThrowable()) {
                            logTrackSwitch("failed", audio, param,
                                    ((param.args != null && param.args.length > 0
                                            && param.args[0] instanceof Number)
                                            ? ((Number) param.args[0]).intValue() : -1));
                            return;
                        }
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Number)) {
                            return;
                        }
                        int qualityId = ((Number) param.args[0]).intValue();
                        logTrackSwitch("returned", audio, param, qualityId);
                        updateTrackSelection(audio, qualityId);
                        if (audio) {
                            // The old MediaInfo may still describe the previous stream at return.
                            // Probe again after the decoder has applied the audio switch.
                            scheduleActualAudioDecoderProbe(param.thisObject);
                        }
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                logOnce("track-switch-hook:" + method.toGenericString(),
                        "IJK track switch hook failed=" + describe(throwable));
            }
        }
        if (hooked > 0) {
            trackSwitchHooksInstalled = true;
            logOnce("track-switch-hooks", "installed IJK audio/video track switch hooks=" + hooked);
        } else {
            logOnce("track-switch-hooks-missing", "IJK audio/video track switch methods not found");
        }
    }

    private static boolean hookItemBinding(Class<?> playerClass, Class<?> itemClass) {
        int hooked = 0;
        for (Method method : allMethods(playerClass)) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!"setIjkMediaPlayerItem".equals(method.getName())
                    || parameterTypes.length != 1
                    || !canCarry(parameterTypes[0], itemClass)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        PlayerState state = stateFor(param.thisObject);
                        state.item = param.args[0];
                        state.sources = SOURCES_BY_ITEM.get(param.args[0]);
                        if (state.sources != null) {
                            logOnce("bind:" + state.sources.key(),
                                    "bound captured source to IJK player pid="
                                            + android.os.Process.myPid()
                                            + " source=" + state.sources.describe());
                        } else {
                            logOnce("bind-missing-source",
                                    "IJK player item bound before source capture");
                        }
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                logOnce("item-binding-hook:" + method.toGenericString(),
                        "IJK item binding hook failed=" + describe(throwable));
            }
        }
        if (hooked == 0) {
            logOnce("item-binding-missing", "IJK setIjkMediaPlayerItem method not found");
        }
        return hooked > 0;
    }

    private static boolean hookSurface(Class<?> playerClass) {
        int hooked = 0;
        for (Method method : allMethods(playerClass)) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!"setSurface".equals(method.getName()) || parameterTypes.length != 1
                    || parameterTypes[0] != Surface.class) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        PlayerState state = stateFor(param.thisObject);
                        state.surface = (Surface) param.args[0];
                        attachSurface(state);
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                logOnce("surface-hook:" + method.toGenericString(),
                        "IJK setSurface hook failed=" + describe(throwable));
            }
        }
        if (hooked == 0) {
            logOnce("surface-missing", "IJK setSurface(Surface) method not found");
        }
        return hooked > 0;
    }

    private static void hookVideoDisplayCallback(Class<?> playerClass) {
        int hooked = 0;
        for (Method method : allMethods(playerClass)) {
            if (!"setOnVideoDisplayCallback".equals(method.getName())
                    || method.getParameterTypes().length != 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        PlayerState state = stateFor(param.thisObject);
                        Object callback = readVideoDisplayCallback(param.thisObject);
                        if (callback == null && param.args != null && param.args.length > 0) {
                            callback = param.args[0];
                        }
                        state.videoDisplayCallback = callback;
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                logOnce("video-display-callback-hook:" + method.toGenericString(),
                        "IJK video-display callback hook failed=" + describe(throwable));
            }
        }
        if (hooked == 0) {
            logOnce("video-display-callback-missing",
                    "IJK setOnVideoDisplayCallback method not found");
        }
    }

    private static boolean hookLifecycleMethod(Class<?> playerClass, String name,
                                               boolean required) {
        int hooked = 0;
        for (Method method : allMethods(playerClass)) {
            if (!name.equals(method.getName()) || method.getParameterTypes().length != 0) {
                continue;
            }
            try {
                if ("start".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (startMedia3(stateFor(param.thisObject))) {
                                param.setResult(null);
                            }
                        }
                    });
                } else if ("pause".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            PlayerState state = stateFor(param.thisObject);
                            if (state.media3Player != null
                                    && setPlayWhenReady(state.media3Player, false)) {
                                param.setResult(null);
                            }
                        }
                    });
                } else {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            PlayerState state = stateFor(param.thisObject);
                            releaseMedia3(state);
                            STATES.remove(param.thisObject);
                        }
                    });
                }
                hooked++;
            } catch (Throwable throwable) {
                logOnce(name + "-hook:" + method.toGenericString(),
                        "IJK " + name + " hook failed=" + describe(throwable));
            }
        }
        if (hooked == 0) {
            logOnce(name + "-missing", "IJK " + name + "() method not found");
        }
        return !required || hooked > 0;
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> methods = new ArrayList<Method>();
        Set<String> seen = new HashSet<String>();
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                String signature = method.toGenericString();
                if (seen.add(signature)) {
                    methods.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    private static boolean canCarry(Class<?> parameterType, Class<?> expectedType) {
        return parameterType.isAssignableFrom(expectedType)
                || expectedType.isAssignableFrom(parameterType);
    }

    private static Object findAssetArgument(Object[] args, Class<?> assetClass) {
        if (args == null) {
            return null;
        }
        for (Object argument : args) {
            if (argument != null && assetClass.isInstance(argument)) {
                return argument;
            }
        }
        return null;
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current.getClass().getName().contains("InvocationTarget")
                || current.getClass().getName().contains("ExceptionInInitializer"))) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + message);
    }

    private static void tryHookSetDisplay(Class<?> playerClass) {
        try {
            XposedHelpers.findAndHookMethod(playerClass, "setDisplay", SurfaceHolder.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            PlayerState state = stateFor(param.thisObject);
                            SurfaceHolder holder = (SurfaceHolder) param.args[0];
                            state.surface = holder == null ? null : holder.getSurface();
                            attachSurface(state);
                        }
                    });
        } catch (Throwable throwable) {
            logOnce("set-display", "IJK setDisplay hook unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static void hookItemStart(Class<?> itemClass) {
        try {
            XposedHelpers.findAndHookMethod(itemClass, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    PlayerState state = stateForItem(param.thisObject);
                    if (state != null && startMedia3(state)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable throwable) {
            logOnce("item-start", "IJK item start hook unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static PlayerState stateFor(Object player) {
        synchronized (STATES) {
            PlayerState state = STATES.get(player);
            if (state == null) {
                state = new PlayerState(player);
                STATES.put(player, state);
            }
            return state;
        }
    }

    private static PlayerState stateForItem(Object item) {
        synchronized (STATES) {
            for (PlayerState state : STATES.values()) {
                if (state != null && state.item == item) {
                    return state;
                }
            }
        }
        return null;
    }

    private static boolean startMedia3(PlayerState state) {
        if (!ModulePreferences.loadMinimalMedia3PlayerForHook()) {
            return false;
        }
        if (state.sources == null || !state.sources.hasVideo()) {
            logOnce("source-missing:" + android.os.Process.myPid(),
                    "minimal Media3 start fell back: IJK source unavailable pid="
                            + android.os.Process.myPid());
            return false;
        }
        try {
            logOnce("start-attempt:" + android.os.Process.myPid(),
                    "minimal Media3 start attempt pid=" + android.os.Process.myPid()
                            + " player=" + state.player.getClass().getName()
                            + " surface=" + describeSurface(state.surface)
                            + " source=" + state.sources.describe());
            captureVideoDisplayCallback(state);
            probeSourceAccessOnce(state.sources.videoUrls.get(0));
            if (state.media3Player == null) {
                state.media3Player = buildMedia3Player(state.sources, state);
                attachSurface(state);
                invoke(state.media3Player, "prepare");
            }
            if (!setPlayWhenReady(state.media3Player, true)) {
                releaseMedia3(state);
                return false;
            }
            state.media3Active = true;
            logOnce("started:" + android.os.Process.myPid(),
                    "Media3 minimal player started; IJK start suppressed pid="
                            + android.os.Process.myPid()
                            + " player=" + state.media3Player.getClass().getName()
                            + " surface=" + describeSurface(state.surface));
            return true;
        } catch (Throwable throwable) {
            releaseMedia3(state);
            logOnce("start-error:" + android.os.Process.myPid(),
                    "Media3 minimal player failed pid=" + android.os.Process.myPid()
                            + " error=" + describe(throwable));
            return false;
        }
    }

    private static Object buildMedia3Player(SourcePair pair, PlayerState state) throws Exception {
        ClassLoader loader = targetClassLoader;
        Context context = currentApplicationContext();
        if (context == null) {
            throw new IllegalStateException("application context unavailable");
        }

        Class<?> builderClass = targetClass("androidx.media3.exoplayer.ExoPlayer$Builder");
        Object builder = builderClass.getConstructor(Context.class).newInstance(context);
        Object player = invoke(builder, "a");

        Class<?> dataSourceFactoryClass = targetClass("androidx.media3.datasource.DataSource$Factory");
        Class<?> httpFactoryClass = targetClass("androidx.media3.datasource.DefaultHttpDataSource$Factory");
        Object httpFactory = httpFactoryClass.getConstructor().newInstance();
        addRequestHeaders(httpFactory, pair.videoUrls.get(0));

        Class<?> extractorsFactoryClass = targetClass("androidx.media3.extractor.ExtractorsFactory");
        Object extractorsFactory = targetClass("androidx.media3.extractor.DefaultExtractorsFactory")
                .getConstructor().newInstance();
        Class<?> sourceFactoryClass = targetClass(
                "androidx.media3.exoplayer.source.ProgressiveMediaSource$Factory");
        Object sourceFactory = sourceFactoryClass.getConstructor(
                dataSourceFactoryClass, extractorsFactoryClass).newInstance(
                httpFactory, extractorsFactory);

        Class<?> mediaSourceClass = targetClass("androidx.media3.exoplayer.source.MediaSource");
        Object videoSource = createSourceForUrls(sourceFactory, mediaSourceClass, pair.videoUrls);
        Object source = videoSource;
        if (!pair.audioUrls.isEmpty()) {
            Object audioSource = createSourceForUrls(sourceFactory, mediaSourceClass, pair.audioUrls);
            Object sourceArray = Array.newInstance(mediaSourceClass, 2);
            Array.set(sourceArray, 0, videoSource);
            Array.set(sourceArray, 1, audioSource);
            Class<?> mergingClass = targetClass(
                    "androidx.media3.exoplayer.source.MergingMediaSource");
            source = mergingClass.getConstructor(sourceArray.getClass()).newInstance(sourceArray);
        }

        invoke(player, "b", new Class<?>[]{mediaSourceClass}, new Object[]{source});
        installMedia3Callbacks(player, state);
        logOnce("player-built:" + android.os.Process.myPid(),
                "Media3 player built pid=" + android.os.Process.myPid()
                        + " player=" + player.getClass().getName()
                        + " source=" + pair.describe());
        return player;
    }

    private static Object createSourceForUrls(Object sourceFactory, Class<?> mediaSourceClass,
                                              List<String> urls) throws Exception {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("media source URL list is empty");
        }
        if (urls.size() == 1) {
            return createProgressiveSource(sourceFactory, urls.get(0));
        }
        List<Object> childSources = new ArrayList<Object>(urls.size());
        for (int index = 0; index < urls.size(); index++) {
            childSources.add(createProgressiveSource(sourceFactory, urls.get(index)));
        }
        Class<?> concatenatingClass = targetClass(
                "androidx.media3.exoplayer.source.ConcatenatingMediaSource");
        Object emptyConcatenatingSource = tryCreateEmptyConcatenatingSource(
                concatenatingClass, mediaSourceClass, childSources);
        if (emptyConcatenatingSource != null) {
            return emptyConcatenatingSource;
        }
        Object sourceArray = Array.newInstance(mediaSourceClass, childSources.size());
        for (int index = 0; index < childSources.size(); index++) {
            Array.set(sourceArray, index, childSources.get(index));
        }
        for (Constructor<?> constructor : concatenatingClass.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 0
                    || !isMediaSourceArray(parameterTypes[parameterTypes.length - 1], mediaSourceClass)) {
                continue;
            }
            Object[] arguments = new Object[parameterTypes.length];
            for (int index = 0; index < parameterTypes.length - 1; index++) {
                arguments[index] = defaultConstructorArgument(parameterTypes[index], urls.size());
            }
            arguments[parameterTypes.length - 1] = sourceArray;
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (Throwable throwable) {
                logOnce("concat-constructor:" + constructor.toGenericString(),
                        "ConcatenatingMediaSource constructor failed=" + describe(throwable));
            }
        }
        throw new NoSuchMethodException("ConcatenatingMediaSource constructor");
    }

    private static Object tryCreateEmptyConcatenatingSource(Class<?> concatenatingClass,
                                                            Class<?> mediaSourceClass,
                                                            List<Object> childSources) {
        try {
            Constructor<?> constructor = concatenatingClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object concatenatingSource = constructor.newInstance();
            Method addMany = null;
            Method addOne = null;
            for (Method method : concatenatingClass.getMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 1) {
                    continue;
                }
                if ("addMediaSources".equals(method.getName())
                        && List.class.isAssignableFrom(parameterTypes[0])) {
                    addMany = method;
                    break;
                }
                if ("addMediaSource".equals(method.getName())
                        && accepts(parameterTypes[0], mediaSourceClass)) {
                    addOne = method;
                }
            }
            if (addMany != null) {
                addMany.setAccessible(true);
                addMany.invoke(concatenatingSource, childSources);
                return concatenatingSource;
            }
            if (addOne != null) {
                addOne.setAccessible(true);
                for (Object childSource : childSources) {
                    addOne.invoke(concatenatingSource, childSource);
                }
                return concatenatingSource;
            }
        } catch (Throwable throwable) {
            logOnce("concat-empty-source", "empty ConcatenatingMediaSource path unavailable="
                    + describe(throwable));
        }
        return null;
    }

    private static Object defaultConstructorArgument(Class<?> type, int sourceCount)
            throws Exception {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(sourceCount);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        if ("androidx.media3.exoplayer.source.ShuffleOrder".equals(type.getName())) {
            Class<?> shuffleClass = targetClass(
                    "androidx.media3.exoplayer.source.DefaultShuffleOrder");
            for (Constructor<?> constructor : shuffleClass.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                constructor.setAccessible(true);
                if (parameterTypes.length == 0) {
                    return constructor.newInstance();
                }
                if (parameterTypes.length == 1 && parameterTypes[0] == Integer.TYPE) {
                    return constructor.newInstance(Integer.valueOf(sourceCount));
                }
            }
        }
        return null;
    }

    private static boolean isMediaSourceArray(Class<?> type, Class<?> mediaSourceClass) {
        return type.isArray()
                && (type.getComponentType().isAssignableFrom(mediaSourceClass)
                || mediaSourceClass.isAssignableFrom(type.getComponentType()));
    }

    private static Object createProgressiveSource(Object sourceFactory, String url) throws Exception {
        Class<?> mediaItemBuilderClass = targetClass("androidx.media3.common.MediaItem$Builder");
        Object mediaItemBuilder = mediaItemBuilderClass.getConstructor().newInstance();
        Field uriField = findField(mediaItemBuilderClass, "b");
        uriField.set(mediaItemBuilder, Uri.parse(url));
        Object mediaItem = invoke(mediaItemBuilder, "a");
        Class<?> mediaItemClass = targetClass("androidx.media3.common.MediaItem");
        return invoke(sourceFactory, "c", new Class<?>[]{mediaItemClass},
                new Object[]{mediaItem});
    }

    private static void addRequestHeaders(Object httpFactory, String sourceUrl) {
        Map<String, String> requestHeaders = new java.util.LinkedHashMap<String, String>();
        requestHeaders.put("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 "
                + "Chrome/120.0 Mobile Safari/537.36");
        requestHeaders.put("Referer", "https://www.bilibili.com/");
        requestHeaders.put("Origin", "https://www.bilibili.com");
        requestHeaders.put("Accept", "*/*");
        requestHeaders.put("Accept-Encoding", "identity");
        try {
            String cookie = CookieManager.getInstance().getCookie(sourceUrl);
            if (cookie != null && !cookie.isEmpty()) {
                requestHeaders.put("Cookie", cookie);
            }
        } catch (Throwable throwable) {
            logOnce("cookie", "Media3 cookie lookup unavailable="
                    + throwable.getClass().getSimpleName());
        }
        try {
            Method publicSetter = findMapRequestPropertiesMethod(httpFactory.getClass());
            if (publicSetter != null) {
                publicSetter.setAccessible(true);
                publicSetter.invoke(httpFactory, requestHeaders);
                logOnce("headers-public", "Media3 request headers configured via factory method="
                        + publicSetter.getName());
                return;
            }
            if (applyRequestHeadersToMapFields(httpFactory, requestHeaders,
                    new IdentityHashMap<Object, Boolean>(), 0)) {
                logOnce("headers-private",
                        "Media3 request headers configured via reflective Map field fallback");
                return;
            }
            logOnce("headers-unavailable-members",
                    "Media3 request headers unavailable; no Map setter or field found");
        } catch (Throwable throwable) {
            logOnce("headers", "Media3 request headers unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static void probeSourceAccessOnce(final String sourceUrl) {
        final String key = "source-probe:" + sourceUrl.hashCode();
        if (REPORTED.contains(key)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!REPORTED.add(key)) {
                    return;
                }
                String cookie = null;
                try {
                    cookie = CookieManager.getInstance().getCookie(sourceUrl);
                } catch (Throwable ignored) {
                    // Cookie lookup is optional for this diagnostic.
                }
                int plain = probeSourceVariant(sourceUrl, false, false, cookie);
                int referer = probeSourceVariant(sourceUrl, true, false, cookie);
                int range = probeSourceVariant(sourceUrl, true, true, null);
                int cookieRange = probeSourceVariant(sourceUrl, true, true, cookie);
                logOnce("source-probe-result:" + sourceUrl.hashCode(),
                        "Media3 source probe status plain=" + plain
                                + " referer=" + referer
                                + " range=" + range
                                + " cookieRange=" + cookieRange
                                + " cookiePresent=" + (cookie != null && !cookie.isEmpty()));
            }
        }, "BiliSurfaceBridge-SourceProbe").start();
    }

    private static int probeSourceVariant(String sourceUrl, boolean withReferer,
                                          boolean withRange, String cookie) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 "
                            + "Chrome/120.0 Mobile Safari/537.36");
            if (withReferer) {
                connection.setRequestProperty("Referer", "https://www.bilibili.com/");
                connection.setRequestProperty("Origin", "https://www.bilibili.com");
            }
            if (withRange) {
                connection.setRequestProperty("Range", "bytes=0-0");
                connection.setRequestProperty("Accept-Encoding", "identity");
            }
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            int status = connection.getResponseCode();
            try {
                connection.getInputStream().close();
            } catch (Throwable ignored) {
                // Error streams are not part of the probe result.
            }
            return status;
        } catch (Throwable throwable) {
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String liveSourceUrl(Object result) {
        if (result == null) {
            return "";
        }
        String[] methods = new String[]{"getUrl", "getMasterUrl"};
        for (String method : methods) {
            try {
                Object value = invoke(result, method);
                if (value instanceof String) {
                    String sourceUrl = ((String) value).trim();
                    if (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
                        return sourceUrl;
                    }
                }
            } catch (Throwable ignored) {
                // The URL accessor varies between APK revisions; try the next known accessor.
            }
        }
        return "";
    }

    /** Reads one bounded live FLV sample and measures AAC payload, without persisting the URL. */
    private static void probeLiveAudioBitrateOnce(final String sourceUrl) {
        final String key = "live-bitrate-probe:" + sourceUrl.hashCode();
        if (REPORTED.contains(key)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!REPORTED.add(key)) {
                    return;
                }
                probeLiveAudioBitrate(sourceUrl);
            }
        }, "BiliSurfaceBridge-LiveBitrate").start();
    }

    private static void probeLiveAudioBitrate(String sourceUrl) {
        HttpURLConnection connection = null;
        long startedAtMs = System.currentTimeMillis();
        int status = -1;
        String contentType = "";
        ByteArrayOutputStream sample = new ByteArrayOutputStream(128 * 1024);
        try {
            connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(1_500);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 "
                            + "Chrome/120.0 Mobile Safari/537.36");
            connection.setRequestProperty("Referer", "https://live.bilibili.com/");
            connection.setRequestProperty("Origin", "https://live.bilibili.com");
            connection.setRequestProperty("Accept-Encoding", "identity");
            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie(sourceUrl);
            } catch (Throwable ignored) {
                // The selected signed URL normally carries its own authorization.
            }
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            status = connection.getResponseCode();
            contentType = String.valueOf(connection.getHeaderField("Content-Type"));
            if (status < 200 || status >= 300) {
                logLiveBitrateResult(sourceUrl, status, contentType, sample.size(),
                        FlvAudioBitrateEstimator.Estimate.empty(),
                        System.currentTimeMillis() - startedAtMs);
                return;
            }

            InputStream input = connection.getInputStream();
            try {
                byte[] buffer = new byte[32 * 1024];
                long deadline = System.currentTimeMillis() + LIVE_AUDIO_SAMPLE_WINDOW_MS;
                while (sample.size() < LIVE_AUDIO_SAMPLE_MAX_BYTES
                        && System.currentTimeMillis() < deadline) {
                    int remaining = LIVE_AUDIO_SAMPLE_MAX_BYTES - sample.size();
                    int readLimit = Math.min(buffer.length, remaining);
                    try {
                        int read = input.read(buffer, 0, readLimit);
                        if (read <= 0) {
                            break;
                        }
                        sample.write(buffer, 0, read);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                }
            } finally {
                input.close();
            }
            byte[] sampleBytes = sample.toByteArray();
            String playlistText = new String(sampleBytes, java.nio.charset.StandardCharsets.UTF_8);
            HlsPlaylistBitrateEstimator.Estimate hlsEstimate =
                    HlsPlaylistBitrateEstimator.estimate(playlistText);
            if (playlistText.trim().startsWith("#EXTM3U")) {
                logOnce("live-bitrate-hls-result:" + sourceUrl.hashCode(),
                        "[DEBUG-live-bitrate] HLS playlist master=" + hlsEstimate.masterPlaylist
                                + " segments=" + hlsEstimate.segmentCount
                                + " segmentsWithAuxBytes=" + hlsEstimate.segmentsWithBytes
                                + " mapPresent=" + !hlsEstimate.mapUri.isEmpty()
                                + " recentBytes=" + hlsEstimate.recentBytes
                                + " recentDurationSec=" + hlsEstimate.recentDurationSec
                                + " streamBitrateBps=" + hlsEstimate.bitrateBitsPerSecond
                                + " streamDisplay=" + AudioBitrateFormatter.format(
                                hlsEstimate.bitrateBitsPerSecond));
                probeHlsAudioSegments(sourceUrl, cookie, hlsEstimate);
                return;
            }
            FlvAudioBitrateEstimator.Estimate estimate =
                    FlvAudioBitrateEstimator.estimate(sampleBytes);
            logLiveBitrateResult(sourceUrl, status, contentType, sample.size(), estimate,
                    System.currentTimeMillis() - startedAtMs);
            if (estimate.hasUsableBitrate()) {
                reportMeasuredAudioBitrate(sourceUrl, "AAC", estimate.bitrateBitsPerSecond());
            }
        } catch (Throwable throwable) {
            logOnce("live-bitrate-error:" + sourceUrl.hashCode(),
                    "[DEBUG-live-bitrate] probe failed status=" + status
                            + " error=" + describe(throwable));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void probeHlsAudioSegments(String playlistUrl, String cookie,
                                               HlsPlaylistBitrateEstimator.Estimate playlist) {
        if (playlist.masterPlaylist || playlist.recentSegments.isEmpty()) {
            logOnce("live-bitrate-hls-audio-missing:" + playlistUrl.hashCode(),
                    "[DEBUG-live-bitrate] HLS AAC segment probe skipped master="
                            + playlist.masterPlaylist + " segments=" + playlist.segmentCount);
            return;
        }
        long audioBytes = 0L;
        double durationSec = 0.0d;
        int parsedSegments = 0;
        int audioPid = -1;
        int audioTrackId = -1;
        int totalSegments = 0;
        StringBuilder segmentStatus = new StringBuilder();
        try {
            URL playlistBase = new URL(playlistUrl);
            Fmp4AacBitrateEstimator.Track audioTrack = null;
            HttpSample initSample = new HttpSample(-1, "", new byte[0]);
            if (!playlist.mapUri.isEmpty()) {
                URL initUrl = resolveHlsSegmentUrl(playlistBase, playlist.mapUri);
                initSample = readHttpSample(initUrl.toString(), playlistUrl, cookie,
                        1 * 1024 * 1024, 4_000);
                audioTrack = Fmp4AacBitrateEstimator.findAudioTrack(initSample.bytes);
                if (audioTrack != null) {
                    audioTrackId = audioTrack.trackId;
                }
            }
            for (HlsPlaylistBitrateEstimator.Segment segment : playlist.recentSegments) {
                if (segment.uri == null || segment.uri.isEmpty() || segment.durationSec <= 0.0d) {
                    continue;
                }
                URL segmentUrl = resolveHlsSegmentUrl(playlistBase, segment.uri);
                HttpSample sample = readHttpSample(segmentUrl.toString(), playlistUrl, cookie,
                        2 * 1024 * 1024, 4_000);
                totalSegments++;
                if (segmentStatus.length() > 0) {
                    segmentStatus.append(',');
                }
                segmentStatus.append(sample.status).append('/').append(sample.bytes.length)
                        .append('/').append(segmentFormat(sample.bytes));
                MpegTsAacBitrateEstimator.Estimate estimate =
                        MpegTsAacBitrateEstimator.estimate(sample.bytes);
                long segmentAudioBytes = estimate.mpegTs ? estimate.audioPayloadBytes : 0L;
                if (segmentAudioBytes > 0L) {
                    audioPid = estimate.audioPid;
                } else if (audioTrack != null) {
                    Fmp4AacBitrateEstimator.Estimate fmp4Estimate =
                            Fmp4AacBitrateEstimator.estimate(sample.bytes, audioTrack);
                    segmentAudioBytes = fmp4Estimate.audioPayloadBytes;
                }
                if (segmentAudioBytes <= 0L) {
                    continue;
                }
                audioBytes += segmentAudioBytes;
                durationSec += segment.durationSec;
                parsedSegments++;
            }
            long bitrate = audioBytes > 0L && durationSec > 0.0d
                    ? Math.round(audioBytes * 8.0d / durationSec) : -1L;
            logOnce("live-bitrate-hls-audio-result:" + playlistUrl.hashCode(),
                    "[DEBUG-live-bitrate] HLS AAC segments=" + parsedSegments + "/"
                            + totalSegments + " audioPid=" + audioPid
                            + " audioTrackId=" + audioTrackId
                            + " initStatus=" + initSample.status + "/" + initSample.bytes.length
                            + " segmentStatus=" + segmentStatus
                            + " audioPayloadBytes=" + audioBytes
                            + " durationSec=" + durationSec
                            + " bitrateBps=" + bitrate
                            + " display=" + AudioBitrateFormatter.format(bitrate));
            if (bitrate > 0L) {
                reportMeasuredAudioBitrate(playlistUrl, "AAC", bitrate);
            }
        } catch (Throwable throwable) {
            logOnce("live-bitrate-hls-audio-error:" + playlistUrl.hashCode(),
                    "[DEBUG-live-bitrate] HLS AAC segment probe failed=" + describe(throwable));
        }
    }

    private static URL resolveHlsSegmentUrl(URL playlistBase, String segmentUri)
            throws Exception {
        URL resolved = new URL(playlistBase, segmentUri);
        if (resolved.getQuery() == null && playlistBase.getQuery() != null
                && !playlistBase.getQuery().isEmpty()) {
            return new URL(resolved.toString() + "?" + playlistBase.getQuery());
        }
        return resolved;
    }

    private static String segmentFormat(byte[] data) {
        if (data == null || data.length == 0) {
            return "empty";
        }
        if ((data[0] & 0xff) == 0x47) {
            return "ts";
        }
        if (data.length >= 8 && data[4] == 'f' && data[5] == 't'
                && data[6] == 'y' && data[7] == 'p') {
            return "fmp4-init";
        }
        if (data.length >= 8 && data[4] == 'm' && data[5] == 'o'
                && data[6] == 'o' && data[7] == 'f') {
            return "fmp4";
        }
        return "other";
    }

    private static HttpSample readHttpSample(String sourceUrl, String referer, String cookie,
                                              int maxBytes, int readTimeoutMs) throws Exception {
        return readHttpSample(sourceUrl, referer, cookie, maxBytes, readTimeoutMs, false);
    }

    private static HttpSample readHttpSample(String sourceUrl, String referer, String cookie,
                                              int maxBytes, int readTimeoutMs,
                                              boolean appCdnProfile) throws Exception {
        HttpURLConnection connection = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 128 * 1024));
        try {
            connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod("GET");
            if (appCdnProfile) {
                connection.setRequestProperty("User-Agent", "Bilibili Freedoooooom/MarkII");
                connection.setRequestProperty("Connection", "Keep-Alive");
            } else {
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 "
                                + "Chrome/120.0 Mobile Safari/537.36");
                if (referer != null) {
                    connection.setRequestProperty("Referer", referer);
                    connection.setRequestProperty("Origin", referer.contains("live.bilibili.com")
                            ? "https://live.bilibili.com" : "https://www.bilibili.com");
                }
            }
            if (!appCdnProfile && (referer == null || !referer.contains("live.bilibili.com"))) {
                // Some VOD CDN mirrors reject an unbounded probe but accept the same signed URL
                // as a bounded media request.  The parser only needs the first few MB.
                connection.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));
            }
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return new HttpSample(status, String.valueOf(connection.getHeaderField(
                        "Content-Type")), new byte[0]);
            }
            InputStream input = connection.getInputStream();
            try {
                byte[] buffer = new byte[32 * 1024];
                while (output.size() < maxBytes) {
                    int read = input.read(buffer, 0, Math.min(buffer.length,
                            maxBytes - output.size()));
                    if (read <= 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                }
            } finally {
                input.close();
            }
            return new HttpSample(status, String.valueOf(connection.getHeaderField(
                    "Content-Type")), output.toByteArray());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Reads a bounded sample of the selected VOD audio segments and measures encoded payload rate. */
    private static void probeVodAudioBitrateOnce(final List<String> audioUrls,
                                                  final String audioLabel,
                                                  final long measurementGeneration) {
        if (audioUrls == null || audioUrls.isEmpty()) {
            return;
        }
        final String firstUrl = audioUrls.get(0);
        final String key = "vod-audio-bitrate-probe:" + firstUrl.hashCode()
                + ":generation=" + measurementGeneration;
        if (REPORTED.contains(key)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!REPORTED.add(key)) {
                    return;
                }
                probeVodAudioBitrate(audioUrls, audioLabel, measurementGeneration);
            }
        }, "BiliSurfaceBridge-VodBitrate").start();
    }

    private static void probeVodAudioBitrate(List<String> audioUrls, String audioLabel,
                                             long measurementGeneration) {
        String cookie = null;
        try {
            cookie = CookieManager.getInstance().getCookie(audioUrls.get(0));
        } catch (Throwable ignored) {
            // Signed media URLs normally carry their own authorization.
        }
        List<HttpSample> samples = new ArrayList<HttpSample>();
        StringBuilder statuses = new StringBuilder();
        int limit = Math.min(VOD_AUDIO_SAMPLE_MAX_SEGMENTS, audioUrls.size());
        try {
            for (int index = 0; index < limit; index++) {
                String sourceUrl = audioUrls.get(index);
                if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
                    continue;
                }
                HttpSample sample = readHttpSample(sourceUrl, "https://www.bilibili.com/",
                        cookie, VOD_AUDIO_SAMPLE_MAX_BYTES, 4_000);
                if (sample.status >= 400) {
                    HttpSample appSample = readHttpSample(sourceUrl, null, null,
                            VOD_AUDIO_SAMPLE_MAX_BYTES, 4_000, true);
                    if (appSample.status >= 200 && appSample.status < 300) {
                        sample = appSample;
                    }
                }
                samples.add(sample);
                if (statuses.length() > 0) {
                    statuses.append(',');
                }
                statuses.append(sample.status).append('/').append(sample.bytes.length)
                        .append('/').append(segmentFormat(sample.bytes));
            }

            Fmp4AacBitrateEstimator.Track audioTrack = null;
            for (HttpSample sample : samples) {
                if (audioTrack == null) {
                    audioTrack = Fmp4AacBitrateEstimator.findAudioTrack(sample.bytes);
                }
            }
            long audioBytes = 0L;
            long durationTicks = 0L;
            long sampleCount = 0L;
            long timescale = audioTrack == null ? -1L : audioTrack.timescale;
            int parsedSegments = 0;
            for (HttpSample sample : samples) {
                if (audioTrack == null) {
                    continue;
                }
                Fmp4AacBitrateEstimator.Estimate estimate =
                        Fmp4AacBitrateEstimator.estimate(sample.bytes, audioTrack);
                if (estimate.audioPayloadBytes <= 0L || estimate.durationTicks <= 0L) {
                    continue;
                }
                audioBytes += estimate.audioPayloadBytes;
                durationTicks += estimate.durationTicks;
                sampleCount += estimate.sampleCount;
                parsedSegments++;
            }
            long bitrate = audioBytes > 0L && durationTicks > 0L && timescale > 0L
                    ? Math.round(audioBytes * 8.0d * timescale / durationTicks) : -1L;
            String measuredLabel = audioMeasurementLabel(audioLabel);
            Log.i(TAG, "[DEBUG-vod-bitrate] segments=" + parsedSegments + "/" + samples.size()
                    + " audioTrackId=" + (audioTrack == null ? -1 : audioTrack.trackId)
                    + " timescale=" + timescale
                    + " sampleRateHz=" + (audioTrack == null ? -1 : audioTrack.sampleRateHz)
                    + " audioPayloadBytes=" + audioBytes
                    + " sampleCount=" + sampleCount
                    + " durationTicks=" + durationTicks
                    + " bitrateBps=" + bitrate
                    + " display=" + AudioBitrateFormatter.format(bitrate)
                    + " statuses=" + statuses);
            if (bitrate > 0L) {
                reportMeasuredAudioBitrate(audioUrls.get(0), measurementGeneration,
                        measuredLabel, bitrate,
                        audioTrack == null ? -1 : audioTrack.sampleRateHz);
            } else {
                PlaybackStreamOverlay.finishAudioBitrateMeasurementForSourceAndGeneration(
                        audioUrls.get(0), measurementGeneration);
            }
        } catch (Throwable throwable) {
            if (audioUrls != null && !audioUrls.isEmpty()) {
                PlaybackStreamOverlay.finishAudioBitrateMeasurementForSourceAndGeneration(
                        audioUrls.get(0), measurementGeneration);
            }
            logOnce("vod-bitrate-error:" + audioUrls.get(0).hashCode(),
                    "[DEBUG-vod-bitrate] probe failed error=" + describe(throwable));
        }
    }

    private static String audioMeasurementLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "AAC";
        }
        String normalized = label.trim();
        int separator = normalized.indexOf(' ');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }

    private static void reportMeasuredAudioBitrate(String sourceUrl, String label, long bitrate) {
        PlaybackStreamOverlay.updateMeasuredAudioBitrateForSource(sourceUrl, label, bitrate);
        DecoderFormatRelay.publishMeasuredAudioBitrate(label, bitrate, sourceUrl);
    }

    private static void reportMeasuredAudioBitrate(String sourceUrl, long measurementGeneration,
                                                   String label, long bitrate, int sampleRateHz) {
        boolean applied = PlaybackStreamOverlay.updateMeasuredAudioBitrateForSourceAndGeneration(
                sourceUrl, measurementGeneration, label, bitrate, sampleRateHz);
        if (applied) {
            DecoderFormatRelay.publishMeasuredAudioBitrate(label, bitrate, sourceUrl,
                    measurementGeneration);
        }
    }

    private static final class HttpSample {
        final int status;
        final String contentType;
        final byte[] bytes;

        HttpSample(int status, String contentType, byte[] bytes) {
            this.status = status;
            this.contentType = contentType;
            this.bytes = bytes;
        }
    }

    private static void logLiveBitrateResult(String sourceUrl, int status, String contentType,
                                             int sampleBytes,
                                             FlvAudioBitrateEstimator.Estimate estimate,
                                             long elapsedMs) {
        long bitrate = estimate.bitrateBitsPerSecond();
        logOnce("live-bitrate-result:" + sourceUrl.hashCode(),
                "[DEBUG-live-bitrate] status=" + status
                        + " contentType=" + contentType
                        + " sampleBytes=" + sampleBytes
                        + " rawAacTags=" + estimate.rawAacTagCount
                        + " rawAacPayloadBytes=" + estimate.rawAacPayloadBytes
                        + " firstTsMs=" + estimate.firstTimestampMs
                        + " lastTsMs=" + estimate.lastTimestampMs
                        + " bitrateBps=" + bitrate
                        + " display=" + AudioBitrateFormatter.format(bitrate)
                        + " elapsedMs=" + elapsedMs);
    }

    private static boolean applyRequestHeadersToMapFields(Object object,
                                                           Map<String, String> requestHeaders,
                                                           IdentityHashMap<Object, Boolean> visited,
                                                           int depth) throws IllegalAccessException {
        if (object == null || depth > 3 || visited.put(object, Boolean.TRUE) != null) {
            return false;
        }
        boolean configured = false;
        for (Field field : allFields(object.getClass())) {
            if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(object);
            if (value instanceof Map) {
                ((Map<Object, Object>) value).putAll(requestHeaders);
                configured = true;
            } else if (value != null && !value.getClass().getName().startsWith("java.")) {
                configured |= applyRequestHeadersToMapFields(value, requestHeaders, visited, depth + 1);
            }
        }
        return configured;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<Field>();
        Set<String> seen = new HashSet<String>();
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (seen.add(field.toGenericString())) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Method findMapRequestPropertiesMethod(Class<?> type) {
        Method fallback = null;
        for (Method method : allMethods(type)) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1 || parameterTypes[0] != Map.class) {
                continue;
            }
            String name = method.getName().toLowerCase();
            if (name.contains("request") || name.contains("header")) {
                return method;
            }
            fallback = method;
        }
        return fallback;
    }

    private static void installMedia3Callbacks(final Object player, final PlayerState state) {
        installMedia3FrameMetadataCallback(player, state);
        installMedia3PlayerListener(player);
    }

    private static void installMedia3FrameMetadataCallback(final Object player,
                                                            final PlayerState state) {
        try {
            final Class<?> listenerClass = targetClass(MEDIA3_FRAME_METADATA_LISTENER);
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getName().contains("VideoFrame") && args != null
                                    && args.length >= 2) {
                                long presentationTimeUs = numberAt(args, 0);
                                long releaseTimeNs = numberAt(args, 1);
                                relayVideoDisplayCallback(state, presentationTimeUs, releaseTimeNs);
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
            Method setter = findSingleArgumentMethod(player.getClass(), listenerClass, null);
            if (setter == null) {
                throw new NoSuchMethodException("video frame metadata listener setter");
            }
            setter.setAccessible(true);
            setter.invoke(player, listener);
            state.media3FrameMetadataListener = listener;
            logOnce("media3-frame-callback:" + android.os.Process.myPid(),
                    "Media3 video-frame callback connected to IJK callback pid="
                            + android.os.Process.myPid() + " method=" + setter.getName());
        } catch (Throwable throwable) {
            logOnce("media3-frame-callback-error", "Media3 video-frame callback unavailable="
                    + describe(throwable));
        }
    }

    private static void installMedia3PlayerListener(final Object player) {
        try {
            final Class<?> listenerClass = targetClass(MEDIA3_PLAYER_LISTENER);
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onPlayerError".equals(method.getName()) && args != null
                                    && args.length > 0 && args[0] != null) {
                                logOnce("media3-player-error:" + args[0].getClass().getName(),
                                        "Media3 player error=" + describeObject(args[0]));
                            } else if ("onPlaybackStateChanged".equals(method.getName())
                                    && args != null && args.length > 0) {
                                logOnce("media3-state:" + String.valueOf(args[0]),
                                        "Media3 playback state=" + String.valueOf(args[0]));
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
            Method addListener = findSingleArgumentMethod(player.getClass(), listenerClass,
                    "listener");
            if (addListener == null) {
                throw new NoSuchMethodException("Media3 Player.Listener registration");
            }
            addListener.setAccessible(true);
            addListener.invoke(player, listener);
            logOnce("media3-player-listener:" + android.os.Process.myPid(),
                    "Media3 player listener connected pid=" + android.os.Process.myPid()
                            + " method=" + addListener.getName());
        } catch (Throwable throwable) {
            logOnce("media3-player-listener-error", "Media3 player listener unavailable="
                    + describe(throwable));
        }
    }

    private static Method findSingleArgumentMethod(Class<?> type, Class<?> argumentType,
                                                   String nameHint) {
        Method fallback = null;
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1
                    || !acceptsListenerParameter(parameterTypes[0], argumentType)) {
                continue;
            }
            if (nameHint == null || method.getName().toLowerCase().contains(nameHint)) {
                return method;
            }
            fallback = method;
        }
        return fallback;
    }

    private static boolean acceptsListenerParameter(Class<?> parameterType,
                                                     Class<?> argumentType) {
        return parameterType == argumentType || argumentType.isAssignableFrom(parameterType);
    }

    private static boolean accepts(Class<?> parameterType, Class<?> argumentType) {
        return parameterType.isAssignableFrom(argumentType)
                || argumentType.isAssignableFrom(parameterType);
    }

    private static long numberAt(Object[] args, int index) {
        if (args == null || index >= args.length || !(args[index] instanceof Number)) {
            return 0L;
        }
        return ((Number) args[index]).longValue();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        return null;
    }

    private static void relayVideoDisplayCallback(PlayerState state, long presentationTimeUs,
                                                  long releaseTimeNs) {
        if (state == null || state.videoDisplayCallback == null) {
            return;
        }
        try {
            Method callbackMethod = state.videoDisplayCallback.getClass().getMethod(
                    "onVideoDisplay", double.class, double.class);
            double presentationSeconds = presentationTimeUs / 1_000_000.0D;
            double releaseSeconds = releaseTimeNs > 0L
                    ? releaseTimeNs / 1_000_000_000.0D : presentationSeconds;
            callbackMethod.invoke(state.videoDisplayCallback,
                    Double.valueOf(presentationSeconds), Double.valueOf(releaseSeconds));
        } catch (Throwable throwable) {
            logOnce("media3-video-display-relay-error",
                    "Media3 video-display callback relay failed=" + describe(throwable));
        }
    }

    private static Object readVideoDisplayCallback(Object player) {
        try {
            return findField(player.getClass(), "mOnVideoDisplayCallback").get(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void captureVideoDisplayCallback(PlayerState state) {
        if (state != null && state.videoDisplayCallback == null) {
            state.videoDisplayCallback = readVideoDisplayCallback(state.player);
        }
    }

    private static String describeObject(Object object) {
        try {
            Method getMessage = object.getClass().getMethod("getMessage");
            Object message = getMessage.invoke(object);
            return object.getClass().getSimpleName() + (message == null ? "" : ":" + message);
        } catch (Throwable ignored) {
            return object.getClass().getSimpleName();
        }
    }

    private static void attachSurface(PlayerState state) {
        if (state == null || state.media3Player == null) {
            return;
        }
        try {
            invoke(state.media3Player, "I", new Class<?>[]{Surface.class},
                    new Object[]{state.surface});
            logOnce("surface-attached:" + android.os.Process.myPid(),
                    "Media3 Surface attached pid=" + android.os.Process.myPid()
                            + " surface=" + describeSurface(state.surface));
        } catch (Throwable throwable) {
            logOnce("surface", "Media3 Surface attach failed="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static String describeSurface(Surface surface) {
        if (surface == null) {
            return "null";
        }
        try {
            return "present=" + surface.isValid() + ",identity="
                    + Integer.toHexString(System.identityHashCode(surface));
        } catch (Throwable ignored) {
            return "present=unknown";
        }
    }

    private static boolean setPlayWhenReady(Object player, boolean ready) {
        try {
            invoke(player, "K", new Class<?>[]{int.class, boolean.class},
                    new Object[]{Integer.valueOf(1), Boolean.valueOf(ready)});
            return true;
        } catch (Throwable throwable) {
            logOnce("play-when-ready", "Media3 playWhenReady hook unavailable="
                    + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static void releaseMedia3(PlayerState state) {
        if (state == null || state.media3Player == null) {
            return;
        }
        try {
            invoke(state.media3Player, "release");
        } catch (Throwable throwable) {
            logOnce("release", "Media3 release failed="
                    + throwable.getClass().getSimpleName());
        } finally {
            state.media3Player = null;
            state.media3Active = false;
        }
    }

    private static SourcePair extractSourcePair(Object asset) {
        if (asset == null) {
            return null;
        }
        try {
            Object streamListObject = invoke(asset, "getStreamList");
            if (!(streamListObject instanceof List)) {
                return null;
            }
            int defaultVideoId = streamInt(asset, "getDefaultVideoId", -1);
            int defaultAudioId = streamInt(asset, "getDefaultAudioId", -1);
            Object selectedVideo = null;
            Object selectedAudio = null;
            int selectedAudioBandwidth = -1;
            for (Object stream : (List<?>) streamListObject) {
                if (stream == null) {
                    continue;
                }
                String type = String.valueOf(invoke(stream, "getStreamType"));
                Object segmentsObject = invoke(stream, "getMediaAssertSegments");
                if (!(segmentsObject instanceof List) || ((List<?>) segmentsObject).isEmpty()) {
                    continue;
                }
                if (isVideoStreamType(type)) {
                    int qualityId = streamInt(stream, "getQualityId", -1);
                    if (selectedVideo == null || qualityId == defaultVideoId) {
                        selectedVideo = stream;
                    }
                } else if (isAudioStreamType(type)) {
                    int bandwidth = streamInt(stream, "getBandWidth", -1);
                    int qualityId = streamInt(stream, "getQualityId", -1);
                    if (qualityId == defaultAudioId) {
                        selectedAudio = stream;
                        selectedAudioBandwidth = bandwidth;
                    }
                }
            }
            if (selectedVideo == null) {
                return null;
            }
            List<String> video = streamUrls(selectedVideo);
            List<String> audio = selectedAudio == null
                    ? Collections.<String>emptyList() : streamUrls(selectedAudio);
            if (video.isEmpty()) {
                return null;
            }
            String selectedVideoLabel = videoCodecLabel(selectedVideo);
            String selectedAudioLabel = audioLabel(selectedAudio, selectedAudioBandwidth);
            String displaySelection = selectedVideoLabel + " " + selectedAudioLabel;
            if (ModulePreferences.loadPlaybackStreamOverlayForHook() && !audio.isEmpty()) {
                long measurementGeneration =
                        PlaybackStreamOverlay.beginAudioBitrateMeasurementForSource(
                                audio.get(0), selectedAudioLabel);
                probeVodAudioBitrateOnce(audio, selectedAudioLabel, measurementGeneration);
            }
            publishTrackLabels((List<?>) streamListObject, selectedVideo, selectedAudio,
                    displaySelection);
            PlaybackStreamOverlay.updateAssetSelection(selectedVideoLabel, selectedAudioLabel);
            Log.i(TAG, "[DEBUG-stream-selection-5c30] asset-init asset="
                    + Integer.toHexString(System.identityHashCode(asset))
                    + " defaultVideoId=" + defaultVideoId
                    + " selectedVideoId=" + streamInt(selectedVideo, "getQualityId", -1)
                    + " selectedVideoCodec=" + streamEnumName(selectedVideo, "getVideoCodecType")
                    + " defaultAudioId=" + defaultAudioId
                    + " selectedAudioId=" + streamInt(selectedAudio, "getQualityId", -1)
                    + " selectedAudioQuality=" + streamEnumName(selectedAudio, "getAudioQualityType")
                    + " selectedAudioCodec=" + streamEnumName(selectedAudio, "getAudioCodecType")
                    + " selectedAudioBandwidth=" + selectedAudioBandwidth
                    + " selection=" + displaySelection);
            logOnce("source-stream:" + displaySelection,
                    "selected IJK stream=" + displaySelection
                            + " videoId=" + streamInt(selectedVideo, "getQualityId", -1)
                            + " audioId=" + streamInt(selectedAudio, "getQualityId", -1)
                            + " audioBandwidth=" + selectedAudioBandwidth);
            return new SourcePair(video, audio, displaySelection);
        } catch (Throwable throwable) {
            logOnce("source-extract", "IJK asset source extraction failed="
                    + throwable.getClass().getSimpleName());
            return null;
        }
    }

    /** Replaces the official first-list-item default before IJK parses the asset. */
    private static void preferHighestBandwidthAudio(Object asset) {
        if (asset == null) {
            return;
        }
        try {
            Object streamListObject = invoke(asset, "getStreamList");
            if (!(streamListObject instanceof List)) {
                return;
            }
            Object selectedAudio = null;
            int highestBandwidth = -1;
            for (Object stream : (List<?>) streamListObject) {
                if (stream == null || !isAudioStreamType(
                        String.valueOf(invoke(stream, "getStreamType")))) {
                    continue;
                }
                int bandwidth = streamInt(stream, "getBandWidth", -1);
                if (selectedAudio == null || bandwidth > highestBandwidth) {
                    selectedAudio = stream;
                    highestBandwidth = bandwidth;
                }
            }
            if (selectedAudio == null) {
                return;
            }
            int selectedId = streamInt(selectedAudio, "getQualityId", -1);
            if (selectedId <= 0) {
                return;
            }
            int previousId = streamInt(asset, "getDefaultAudioId", -1);
            findField(asset.getClass(), "defaultAudioId").setInt(asset, selectedId);
            logOnce("highest-audio:" + previousId + ':' + selectedId,
                    "IJK defaultAudioId replaced " + previousId + " -> " + selectedId
                            + " selection=" + audioLabel(selectedAudio, highestBandwidth));
        } catch (Throwable throwable) {
            logOnce("highest-audio-error", "IJK highest-bandwidth audio selection failed="
                    + describe(throwable));
        }
    }

    private static void publishTrackLabels(List<?> streams, Object selectedVideo,
                                           Object selectedAudio, String selection) {
        synchronized (TRACK_SELECTION_LOCK) {
            AUDIO_LABELS_BY_ID.clear();
            AUDIO_URLS_BY_ID.clear();
            VIDEO_LABELS_BY_ID.clear();
            for (Object stream : streams) {
                if (stream == null) {
                    continue;
                }
                String type = streamEnumName(stream, "getStreamType");
                int id = streamInt(stream, "getQualityId", -1);
                if (id <= 0) {
                    continue;
                }
                if (isAudioStreamType(type)) {
                    String label = audioLabel(stream, streamInt(stream, "getBandWidth", -1));
                    AUDIO_LABELS_BY_ID.put(id, label);
                    KNOWN_AUDIO_LABELS_BY_ID.put(id, label);
                    try {
                        List<String> urls = streamUrls(stream);
                        if (!urls.isEmpty()) {
                            AUDIO_URLS_BY_ID.put(id,
                                    Collections.unmodifiableList(new ArrayList<String>(urls)));
                        }
                    } catch (Throwable ignored) {
                        // The label remains useful even when this optional probe source is absent.
                    }
                } else if (isVideoStreamType(type)) {
                    VIDEO_LABELS_BY_ID.put(id, videoCodecLabel(stream));
                }
            }
            currentVideoLabel = videoCodecLabel(selectedVideo);
            currentAudioLabel = audioLabel(selectedAudio,
                    selectedAudio == null ? -1 : streamInt(selectedAudio, "getBandWidth", -1));
        }
    }

    private static void updateTrackSelection(boolean audio, int qualityId) {
        String display;
        String label;
        List<String> audioUrls = Collections.emptyList();
        synchronized (TRACK_SELECTION_LOCK) {
            label = audio ? AudioTrackLabelResolver.resolve(qualityId, AUDIO_LABELS_BY_ID,
                    KNOWN_AUDIO_LABELS_BY_ID)
                    : VIDEO_LABELS_BY_ID.get(qualityId);
            if (label == null) {
                return;
            }
            if (audio) {
                currentAudioLabel = label;
                List<String> selectedUrls = AUDIO_URLS_BY_ID.get(qualityId);
                if (selectedUrls != null) {
                    audioUrls = new ArrayList<String>(selectedUrls);
                }
            } else {
                currentVideoLabel = label;
            }
            display = currentVideoLabel + " " + currentAudioLabel;
        }
        if (audio) {
            if (ModulePreferences.loadPlaybackStreamOverlayForHook() && !audioUrls.isEmpty()) {
                long measurementGeneration =
                        PlaybackStreamOverlay.restartAudioBitrateMeasurementForSource(
                                audioUrls.get(0), label);
                probeVodAudioBitrateOnce(audioUrls, label, measurementGeneration);
            } else if (ModulePreferences.loadPlaybackStreamOverlayForHook()) {
                // A successful switch with no exposed segment URL must not leave the previous
                // source's asynchronous probe in the permanent "計算中..." state.
                PlaybackStreamOverlay.cancelAudioBitrateMeasurement(label);
            }
            PlaybackStreamOverlay.updateSelectedAudio(label);
        } else {
            PlaybackStreamOverlay.updateSelectedVideoCandidate(label);
        }
        logOnce("track-switch:" + display, "IJK active track changed=" + display);
    }

    /**
     * Emits only track identifiers and method outcome.  It deliberately omits URLs, cookies and
     * account data.  This distinguishes a new IJK asset from a successful in-place track switch.
     */
    private static void logTrackSwitch(String outcome, boolean audio,
                                       XC_MethodHook.MethodHookParam param, int qualityId) {
        String label;
        synchronized (TRACK_SELECTION_LOCK) {
            label = audio ? AUDIO_LABELS_BY_ID.get(qualityId) : VIDEO_LABELS_BY_ID.get(qualityId);
        }
        Log.i(TAG, "[DEBUG-stream-selection-5c30] switch-" + outcome
                + " kind=" + (audio ? "audio" : "video")
                + " qualityId=" + qualityId
                + " mappedLabel=" + (label == null ? "unknown" : label)
                + " throwable=" + (param.hasThrowable()
                        ? param.getThrowable().getClass().getSimpleName() : "none"));
    }

    private static List<String> streamUrls(Object stream) throws Exception {
        Object segmentsObject = invoke(stream, "getMediaAssertSegments");
        if (!(segmentsObject instanceof List)) {
            return Collections.emptyList();
        }
        List<String> urls = new ArrayList<String>();
        for (Object segment : (List<?>) segmentsObject) {
            Object urlObject = invoke(segment, "getUrl");
            if (urlObject instanceof String && !((String) urlObject).trim().isEmpty()) {
                urls.add(((String) urlObject).trim());
            }
        }
        return urls;
    }

    private static int streamInt(Object receiver, String methodName, int fallback) {
        if (receiver == null) {
            return fallback;
        }
        try {
            Object value = invoke(receiver, methodName);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isAudioStreamType(String type) {
        return "AUDIO".equals(type) || "DASH_AUDIO".equals(type)
                || (type != null && type.endsWith("_AUDIO"));
    }

    private static boolean isVideoStreamType(String type) {
        return "NORMAL".equals(type) || "VIDEO".equals(type) || "DASH_VIDEO".equals(type)
                || (type != null && type.endsWith("_VIDEO"));
    }

    private static String videoCodecLabel(Object stream) {
        return StreamCodecLabel.videoForEnumName(streamEnumName(stream, "getVideoCodecType"));
    }

    private static String audioLabel(Object stream, int bandwidth) {
        if (stream == null) {
            return "";
        }
        String quality = streamEnumName(stream, "getAudioQualityType");
        String codec = streamEnumName(stream, "getAudioCodecType");
        String codecLabel = StreamCodecLabel.audioForEnumName(quality, codec);
        if (bandwidth <= 0) {
            return codecLabel;
        }
        return codecLabel + " " + AudioBitrateFormatter.format(bandwidth);
    }

    private static String streamEnumName(Object stream, String methodName) {
        if (stream == null) {
            return "";
        }
        try {
            Object value = invoke(stream, methodName);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Context currentApplicationContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object application = activityThread.getDeclaredMethod("currentApplication").invoke(null);
        return application instanceof Context ? ((Context) application).getApplicationContext() : null;
    }

    private static Class<?> targetClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, targetClassLoader);
    }

    private static Object invoke(Object receiver, String name) throws Exception {
        return invoke(receiver, name, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object receiver, String name, Class<?>[] parameterTypes,
                                 Object[] args) throws Exception {
        Method method = findMethod(receiver.getClass(), name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void logOnce(String key, String message) {
        if (REPORTED.add(key)) {
            String line = "[minimal-media3] " + message;
            XposedBridge.log(TAG + ": " + line);
            Log.i(TAG, line);
        }
    }

    private static String describeMethods(Object object) {
        if (object == null) {
            return "null";
        }
        Set<String> names = new HashSet<String>();
        Class<?> current = object.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().startsWith("get")) {
                    names.add(method.getName());
                }
            }
            current = current.getSuperclass();
        }
        return names.toString();
    }

    private static final class LiveUrlCandidate {
        final LiveQualitySelector.Candidate candidate;
        final String format;

        LiveUrlCandidate(LiveQualitySelector.Candidate candidate, String format) {
            this.candidate = candidate;
            this.format = format;
        }
    }

    private static final class PlayerState {
        final Object player;
        Object item;
        SourcePair sources;
        Surface surface;
        Object media3Player;
        Object videoDisplayCallback;
        Object media3FrameMetadataListener;
        boolean media3Active;

        PlayerState(Object player) {
            this.player = player;
        }
    }

    private static final class SourcePair {
        final List<String> videoUrls;
        final List<String> audioUrls;
        final String displaySelection;

        SourcePair(List<String> videoUrls, List<String> audioUrls, String displaySelection) {
            this.videoUrls = Collections.unmodifiableList(new ArrayList<String>(videoUrls));
            this.audioUrls = Collections.unmodifiableList(new ArrayList<String>(audioUrls));
            this.displaySelection = displaySelection;
        }

        boolean hasVideo() {
            return !videoUrls.isEmpty();
        }

        String key() {
            return displaySelection + "|videoSegments=" + videoUrls.size()
                    + "|audioSegments=" + audioUrls.size();
        }

        String describe() {
            return "selection=" + displaySelection + ",video=" + describeUrls(videoUrls)
                    + ",audio=" + describeUrls(audioUrls);
        }

        private static String describeUrls(List<String> urls) {
            List<String> descriptions = new ArrayList<String>(urls.size());
            for (String url : urls) {
                try {
                    Uri uri = Uri.parse(url);
                    descriptions.add(uri.getScheme() + "://" + uri.getHost()
                            + "?query=" + (uri.getQueryParameterNames().size() > 0)
                            + ",length=" + url.length());
                } catch (Throwable ignored) {
                    descriptions.add("invalid");
                }
            }
            return descriptions.toString();
        }
    }
}
