package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** A separate, opt-in one-line overlay for the selected audio and actual platform video decoder. */
final class PlaybackStreamOverlay {
    private static final Map<Activity, OutlinedTextView> OVERLAYS =
            Collections.synchronizedMap(new WeakHashMap<Activity, OutlinedTextView>());
    private static final Map<Activity, Boolean> CONTROL_VISIBILITY =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, Boolean> POSITION_READY =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, Boolean> POSITION_RETRY_PENDING =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, Integer> POSITION_RETRY_COUNT =
            Collections.synchronizedMap(new WeakHashMap<Activity, Integer>());
    private static final int MAX_POSITION_RETRIES = 20;
    private static final long POSITION_RETRY_DELAY_MS = 100L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile String candidateVideoLabel = "unknown";
    private static volatile String confirmedDecoderVideoLabel;
    private static volatile String currentAudioLabel = "";
    private static volatile String actualDecoderAudioLabel = "";
    private static volatile int actualAudioSampleRateHz = -1;
    private static volatile String measuredAudioCodecLabel = "";
    private static volatile long measuredAudioBitrate = -1L;
    private static final AudioBitrateMeasurementState AUDIO_MEASUREMENT_STATE =
            new AudioBitrateMeasurementState();
    private static volatile String selectedAudioFormatLabel = "AAC";
    private static volatile String selectedAudioFallbackLabel = "AAC";
    private static volatile String latestSelection = "解碼器初始化中";

    private PlaybackStreamOverlay() {
    }

    static void show(final Activity activity) {
        if (activity == null) {
            return;
        }
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                OutlinedTextView label = OVERLAYS.get(activity);
                ViewGroup overlayHost = findPlaybackOverlayHost(activity);
                if (label == null) {
                    label = new OutlinedTextView(activity);
                    label.setTextColor(Color.WHITE);
                    label.setTextSize(11.0f);
                    label.setOutlineWidthPx(dp(activity, 1));
                    label.setGravity(Gravity.CENTER);
                    label.setPadding(dp(activity, 2), dp(activity, 1), dp(activity, 2), dp(activity, 1));
                    label.setBackgroundColor(Color.TRANSPARENT);
                    label.setClickable(false);
                    label.setFocusable(false);
                    OVERLAYS.put(activity, label);
                }
                attachOverlayLabel(activity, overlayHost, label);
                label.setText(latestSelection);
                applyPlaybackControlVisibility(activity, label);
                schedulePositionRetryIfNeeded(activity, overlayHost);
            }
        });
    }

    /**
     * Place the label above the player's complete view hierarchy. Its bounds still follow the
     * stable control_container, while visibility is driven by ControlContainerService show/hide.
     */
    private static ViewGroup findPlaybackOverlayHost(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (decor instanceof ViewGroup) {
            return (ViewGroup) decor;
        }
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            return (ViewGroup) content;
        }
        return null;
    }

    private static ViewGroup findPlaybackControlContainer(Activity activity) {
        int controlsId = activity.getResources().getIdentifier(
                "control_container", "id", activity.getPackageName());
        View controls = controlsId == 0 ? null : activity.findViewById(controlsId);
        if (controls instanceof ViewGroup) {
            return (ViewGroup) controls;
        }
        return null;
    }

    private static void attachOverlayLabel(Activity activity, ViewGroup host,
            OutlinedTextView label) {
        if (host == null) {
            POSITION_READY.put(activity, Boolean.FALSE);
            return;
        }
        ViewGroup target = findOrCreateOverlayLayer(activity, host, label);
        ViewParent currentParent = label.getParent();
        if (currentParent != target && currentParent instanceof ViewGroup) {
            ViewGroup oldParent = (ViewGroup) currentParent;
            oldParent.removeView(label);
        }
        if (currentParent != target) {
            FrameLayout.LayoutParams parameters = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            parameters.topMargin = dp(activity, 15.5f);
            target.addView(label, parameters);
        }
        promoteOverlay(activity, host, target, label);
    }

    private static ViewGroup findOrCreateOverlayLayer(Activity activity, ViewGroup host,
            OutlinedTextView label) {
        ViewParent currentParent = label.getParent();
        if (currentParent instanceof ViewGroup
                && ((ViewGroup) currentParent).getParent() == host) {
            ViewGroup overlayLayer = (ViewGroup) currentParent;
            updateOverlayLayerBounds(activity, host, overlayLayer);
            return overlayLayer;
        }
        FrameLayout overlayLayer = new FrameLayout(activity);
        overlayLayer.setClickable(false);
        overlayLayer.setFocusable(false);
        overlayLayer.setClipChildren(false);
        host.addView(overlayLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateOverlayLayerBounds(activity, host, overlayLayer);
        return overlayLayer;
    }

    private static void updateOverlayLayerBounds(Activity activity, ViewGroup host,
            ViewGroup overlayLayer) {
        ViewGroup controls = findPlaybackControlContainer(activity);
        boolean detailActivity = PlaybackActivityScope.isVideoDetailActivity(
                activity.getClass().getName());
        if (!(host instanceof FrameLayout) || controls == null) {
            overlayLayer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            POSITION_READY.put(activity, Boolean.valueOf(!detailActivity));
            return;
        }
        if (controls.getWidth() <= 0 || controls.getHeight() <= 0) {
            overlayLayer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            POSITION_READY.put(activity, Boolean.FALSE);
            return;
        }
        int[] hostLocation = new int[2];
        int[] controlLocation = new int[2];
        host.getLocationInWindow(hostLocation);
        controls.getLocationInWindow(controlLocation);
        FrameLayout.LayoutParams parameters = new FrameLayout.LayoutParams(
                controls.getWidth(), controls.getHeight());
        parameters.leftMargin = controlLocation[0] - hostLocation[0];
        parameters.topMargin = controlLocation[1] - hostLocation[1];
        overlayLayer.setLayoutParams(parameters);
        POSITION_READY.put(activity, Boolean.TRUE);
        POSITION_RETRY_COUNT.remove(activity);
        POSITION_RETRY_PENDING.remove(activity);
    }

    private static void promoteOverlay(Activity activity, ViewGroup host, ViewGroup overlayLayer,
            OutlinedTextView label) {
        float foregroundElevation = dp(activity, 100);
        overlayLayer.setElevation(foregroundElevation);
        overlayLayer.setTranslationZ(foregroundElevation);
        label.setElevation(foregroundElevation);
        label.setTranslationZ(foregroundElevation);
        host.bringChildToFront(overlayLayer);
        overlayLayer.bringChildToFront(label);
        host.invalidate();
    }

    static void setPlaybackControlsVisible(final Activity activity, final boolean visible) {
        if (activity == null) {
            return;
        }
        CONTROL_VISIBILITY.put(activity, Boolean.valueOf(visible));
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                OutlinedTextView label = OVERLAYS.get(activity);
                if (label != null) {
                    attachOverlayLabel(activity, findPlaybackOverlayHost(activity), label);
                    applyPlaybackControlVisibility(activity, label);
                    schedulePositionRetryIfNeeded(activity,
                            findPlaybackOverlayHost(activity));
                }
            }
        });
    }

    private static void schedulePositionRetryIfNeeded(final Activity activity,
            final ViewGroup host) {
        if (host == null || !Boolean.TRUE.equals(CONTROL_VISIBILITY.get(activity))
                || Boolean.TRUE.equals(POSITION_READY.get(activity))
                || Boolean.TRUE.equals(POSITION_RETRY_PENDING.get(activity))) {
            return;
        }
        Integer previousCount = POSITION_RETRY_COUNT.get(activity);
        int retryCount = previousCount == null ? 0 : previousCount.intValue();
        if (retryCount >= MAX_POSITION_RETRIES) {
            return;
        }
        POSITION_RETRY_COUNT.put(activity, Integer.valueOf(retryCount + 1));
        POSITION_RETRY_PENDING.put(activity, Boolean.TRUE);
        host.postDelayed(new Runnable() {
            @Override
            public void run() {
                POSITION_RETRY_PENDING.remove(activity);
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    show(activity);
                }
            }
        }, POSITION_RETRY_DELAY_MS);
    }

    private static void applyPlaybackControlVisibility(Activity activity,
            OutlinedTextView label) {
        boolean visible = PlaybackOverlayVisibilityState.shouldShow(
                CONTROL_VISIBILITY.get(activity),
                Boolean.TRUE.equals(POSITION_READY.get(activity)));
        label.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Updates manifest/asset data, which supplies the audio label only.  The decoder result stays
     * authoritative because asset and decoder events arrive from separate processes with no
     * guaranteed ordering.
     */
    static void updateAssetSelection(String videoLabel, String audioLabel) {
        candidateVideoLabel = normalize(videoLabel, "unknown");
        if (AUDIO_MEASUREMENT_STATE.isPending() || AUDIO_MEASUREMENT_STATE.isMeasured()) {
            currentAudioLabel = normalize(actualDecoderAudioLabel,
                    normalize(audioLabel, selectedAudioFallbackLabel));
        } else {
            currentAudioLabel = normalize(audioLabel, "");
        }
        publish();
    }

    /** Shows an audio placeholder until the encoded stream bitrate is measured. */
    static void beginAudioBitrateMeasurement() {
        AudioBitrateMeasurementState.Started started =
                AUDIO_MEASUREMENT_STATE.begin("", false);
        if (!started.started) {
            return;
        }
        selectedAudioFormatLabel = "AAC";
        selectedAudioFallbackLabel = "AAC";
        clearAudioDetails();
        actualDecoderAudioLabel = "AAC 計算中...";
        currentAudioLabel = actualDecoderAudioLabel;
        publish();
    }

    /** Starts a new measurement only when the selected encoded source changes. */
    static long beginAudioBitrateMeasurementForSource(String sourceUrl, String audioLabel) {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            beginAudioBitrateMeasurement();
            return AUDIO_MEASUREMENT_STATE.generation();
        }
        String sourceKey = Integer.toHexString(sourceUrl.hashCode());
        AudioBitrateMeasurementState.Started started =
                AUDIO_MEASUREMENT_STATE.begin(sourceKey, false);
        if (!started.started) {
            return started.generation;
        }
        selectedAudioFormatLabel = audioCodecLabel(audioLabel);
        selectedAudioFallbackLabel = normalize(audioLabel, selectedAudioFormatLabel);
        clearAudioDetails();
        actualDecoderAudioLabel = selectedAudioFormatLabel + " 計算中...";
        currentAudioLabel = actualDecoderAudioLabel;
        publish();
        return started.generation;
    }

    /** Starts a fresh generation after an explicit in-place audio-track switch. */
    static long restartAudioBitrateMeasurementForSource(String sourceUrl, String audioLabel) {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            cancelAudioBitrateMeasurement(audioLabel);
            return AUDIO_MEASUREMENT_STATE.generation();
        }
        String sourceKey = Integer.toHexString(sourceUrl.hashCode());
        AudioBitrateMeasurementState.Started started =
                AUDIO_MEASUREMENT_STATE.begin(sourceKey, true);
        selectedAudioFormatLabel = audioCodecLabel(audioLabel);
        selectedAudioFallbackLabel = normalize(audioLabel, selectedAudioFormatLabel);
        clearAudioDetails();
        actualDecoderAudioLabel = selectedAudioFormatLabel + " 計算中...";
        currentAudioLabel = actualDecoderAudioLabel;
        publish();
        return started.generation;
    }

    /** Cancels a previous probe when a selected track has no probeable source URL. */
    static void cancelAudioBitrateMeasurement(String audioLabel) {
        AUDIO_MEASUREMENT_STATE.reset();
        selectedAudioFormatLabel = audioCodecLabel(audioLabel);
        selectedAudioFallbackLabel = normalize(audioLabel, selectedAudioFormatLabel);
        clearAudioDetails();
        actualDecoderAudioLabel = selectedAudioFallbackLabel;
        currentAudioLabel = selectedAudioFallbackLabel;
        publish();
    }

    /** Compatibility entry point for the live HLS path. */
    static void beginLiveAudioBitrateMeasurement() {
        beginAudioBitrateMeasurement();
    }

    /** Compatibility entry point for the live HLS path. */
    static void beginLiveAudioBitrateMeasurementForSource(String sourceUrl) {
        beginAudioBitrateMeasurementForSource(sourceUrl, "AAC");
    }

    /** Updates a successful audio selection without weakening the existing decoder proof. */
    static void updateSelectedAudio(String audioLabel) {
        if (AUDIO_MEASUREMENT_STATE.isPending() || AUDIO_MEASUREMENT_STATE.isMeasured()) {
            publish();
            return;
        }
        currentAudioLabel = normalize(audioLabel, "");
        publish();
    }

    /** A requested video change remains only a candidate until its new MediaCodec configures. */
    static void updateSelectedVideoCandidate(String videoLabel) {
        candidateVideoLabel = normalize(videoLabel, "unknown");
        publish();
    }

    /** Called from the main-process decoder-format receiver after MediaCodec.configure(). */
    static void updateActualDecoderMime(String mime) {
        confirmedDecoderVideoLabel = StreamCodecLabel.videoForDecoderMime(mime);
        publish();
    }

    /** Called from the main-process decoder-format receiver after an audio decoder configures. */
    static void updateActualDecoderAudio(String mime, int bitrate) {
        updateActualDecoderAudio(mime, bitrate, -1);
    }

    /** Called after the audio decoder configures with its decoder sample rate. */
    static void updateActualDecoderAudio(String mime, int bitrate, int sampleRateHz) {
        String label = StreamCodecLabel.audioForDecoderMime(mime);
        if ("UNKNOWN".equals(label)) {
            return;
        }
        updateActualDecoderAudioLabel(label, bitrate, sampleRateHz);
    }

    /** Updates the fallback audio label from IJK's actual native decoder metadata. */
    static void updateActualDecoderAudioLabel(String label, long bitrate) {
        updateActualDecoderAudioLabel(label, bitrate, -1);
    }

    /** Updates decoder metadata without allowing an old measurement to replace the selection. */
    static void updateActualDecoderAudioLabel(String label, long bitrate, int sampleRateHz) {
        if (label == null || label.trim().isEmpty() || "UNKNOWN".equals(label)) {
            return;
        }
        // A delayed IJK callback can still describe the old AAC decoder after the selected
        // DASH track has become Hi-Res or Dolby.  Never attach that decoder's rate to the
        // newly selected track just because the callback arrived later.
        String selectedFormat = AUDIO_MEASUREMENT_STATE.isPending()
                || AUDIO_MEASUREMENT_STATE.isMeasured()
                ? selectedAudioFormatLabel : audioCodecLabel(currentAudioLabel);
        if (selectedFormat != null && !selectedFormat.isEmpty()
                && !"UNKNOWN".equalsIgnoreCase(selectedFormat)
                && !StreamCodecLabel.audioDecoderMatchesSelection(
                        selectedFormat, audioCodecLabel(label))) {
            return;
        }
        if (sampleRateHz > 0) {
            actualAudioSampleRateHz = sampleRateHz;
        }
        if (AUDIO_MEASUREMENT_STATE.isMeasured()) {
            actualDecoderAudioLabel = formatAudioLabel(measuredAudioCodecLabel,
                    measuredAudioBitrate);
            currentAudioLabel = actualDecoderAudioLabel;
            publish();
            return;
        }
        if (AUDIO_MEASUREMENT_STATE.isPending()) {
            // Keep the selected asset quality (for example Hi-Res or Dolby).  IJK's late
            // metadata is only a decoder fallback and must not relabel the selected track as AAC.
            actualDecoderAudioLabel = selectedAudioFormatLabel + " 計算中...";
        } else {
            actualDecoderAudioLabel = formatAudioLabel(label, bitrate);
        }
        // A DASH asset label already carries quality information such as Hi-Res or Dolby.  Use
        // the actual decoder as the fallback for live streams that do not expose an IJK asset.
        if (currentAudioLabel == null || currentAudioLabel.trim().isEmpty()
                || "UNKNOWN".equals(currentAudioLabel)) {
            currentAudioLabel = actualDecoderAudioLabel;
        }
        publish();
    }

    /** Replaces nominal IJK metadata with a measured encoded-stream rate. */
    static void updateMeasuredAudioBitrate(String label, long bitrate) {
        updateMeasuredAudioBitrateForSourceKey(AUDIO_MEASUREMENT_STATE.sourceKey(), label, bitrate);
    }

    static void updateMeasuredAudioBitrateForSource(String sourceUrl, String label, long bitrate) {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            updateMeasuredAudioBitrate(label, bitrate);
            return;
        }
        updateMeasuredAudioBitrateForSourceKey(Integer.toHexString(sourceUrl.hashCode()),
                label, bitrate);
    }

    static boolean updateMeasuredAudioBitrateForSourceAndGeneration(
            String sourceUrl, long generation, String label, long bitrate) {
        return updateMeasuredAudioBitrateForSourceAndGeneration(
                sourceUrl, generation, label, bitrate, -1);
    }

    static boolean updateMeasuredAudioBitrateForSourceAndGeneration(
            String sourceUrl, long generation, String label, long bitrate, int sampleRateHz) {
        String sourceKey = sourceUrl == null || sourceUrl.trim().isEmpty()
                ? "" : Integer.toHexString(sourceUrl.hashCode());
        return updateMeasuredAudioBitrateForSourceKeyAndGeneration(
                sourceKey, generation, label, bitrate, sampleRateHz);
    }

    static boolean updateMeasuredAudioBitrateForSourceKey(String sourceKey, String label,
                                                          long bitrate) {
        if (label == null || label.trim().isEmpty() || "UNKNOWN".equals(label)
                || bitrate <= 0L) {
            return false;
        }
        if (!AUDIO_MEASUREMENT_STATE.markMeasuredCurrent(sourceKey)) {
            return false;
        }
        measuredAudioCodecLabel = audioCodecLabel(label);
        measuredAudioBitrate = bitrate;
        actualDecoderAudioLabel = formatAudioLabel(measuredAudioCodecLabel,
                measuredAudioBitrate);
        currentAudioLabel = actualDecoderAudioLabel;
        publish();
        return true;
    }

    static boolean updateMeasuredAudioBitrateForSourceKeyAndGeneration(
            String sourceKey, long generation, String label, long bitrate) {
        return updateMeasuredAudioBitrateForSourceKeyAndGeneration(
                sourceKey, generation, label, bitrate, -1);
    }

    static boolean updateMeasuredAudioBitrateForSourceKeyAndGeneration(
            String sourceKey, long generation, String label, long bitrate, int sampleRateHz) {
        if (label == null || label.trim().isEmpty() || "UNKNOWN".equals(label)
                || bitrate <= 0L
                || !AUDIO_MEASUREMENT_STATE.markMeasured(sourceKey, generation)) {
            return false;
        }
        if (sampleRateHz > 0) {
            actualAudioSampleRateHz = sampleRateHz;
        }
        measuredAudioCodecLabel = audioCodecLabel(label);
        measuredAudioBitrate = bitrate;
        actualDecoderAudioLabel = formatAudioLabel(measuredAudioCodecLabel,
                measuredAudioBitrate);
        currentAudioLabel = actualDecoderAudioLabel;
        publish();
        return true;
    }

    /** Ends a bounded measurement without leaving the overlay stuck in a pending state. */
    static void finishAudioBitrateMeasurement() {
        finishAudioBitrateMeasurementForSourceKey(AUDIO_MEASUREMENT_STATE.sourceKey());
    }

    static void finishAudioBitrateMeasurementForSource(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            finishAudioBitrateMeasurement();
            return;
        }
        finishAudioBitrateMeasurementForSourceKey(Integer.toHexString(sourceUrl.hashCode()));
    }

    static void finishAudioBitrateMeasurementForSourceKey(String sourceKey) {
        if (!AUDIO_MEASUREMENT_STATE.finishCurrent(sourceKey)) {
            return;
        }
        currentAudioLabel = selectedAudioFallbackLabel;
        clearMeasuredAudioDetails();
        actualDecoderAudioLabel = selectedAudioFallbackLabel;
        publish();
    }

    static boolean finishAudioBitrateMeasurementForSourceAndGeneration(
            String sourceUrl, long generation) {
        String sourceKey = sourceUrl == null || sourceUrl.trim().isEmpty()
                ? "" : Integer.toHexString(sourceUrl.hashCode());
        if (!AUDIO_MEASUREMENT_STATE.finish(sourceKey, generation)) {
            return false;
        }
        currentAudioLabel = selectedAudioFallbackLabel;
        clearMeasuredAudioDetails();
        actualDecoderAudioLabel = selectedAudioFallbackLabel;
        publish();
        return true;
    }

    private static void publish() {
        String video = confirmedDecoderVideoLabel;
        String audio = normalize(currentAudioLabel, "");
        if (audio.isEmpty()) {
            audio = normalize(actualDecoderAudioLabel, "");
        }
        audio = AudioSampleRateFormatter.appendTo(audio, actualAudioSampleRateHz);
        latestSelection = video == null ? "解碼器初始化中"
                : (audio.isEmpty() ? video : video + " / " + audio);
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                synchronized (OVERLAYS) {
                    for (OutlinedTextView label : OVERLAYS.values()) {
                        if (label != null) {
                            label.setText(latestSelection);
                        }
                    }
                }
            }
        });
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String audioCodecLabel(String value) {
        String normalized = normalize(value, "AAC");
        int separator = normalized.indexOf(' ');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        return normalized.isEmpty() ? "AAC" : normalized;
    }

    private static String formatAudioLabel(String label, long bitrate) {
        String result = audioCodecLabel(label);
        if (bitrate > 0L) {
            result += " " + AudioBitrateFormatter.format(bitrate);
        }
        return result;
    }

    private static void clearAudioDetails() {
        actualAudioSampleRateHz = -1;
        clearMeasuredAudioDetails();
    }

    private static void clearMeasuredAudioDetails() {
        measuredAudioCodecLabel = "";
        measuredAudioBitrate = -1L;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

}
