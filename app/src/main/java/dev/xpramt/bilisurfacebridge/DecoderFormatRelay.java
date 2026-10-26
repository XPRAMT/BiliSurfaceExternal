package dev.xpramt.bilisurfacebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import de.robv.android.xposed.XposedHelpers;

/**
 * Relays decoder-confirmed MIME from Bilibili's IJK service process to the UI process.  The
 * broadcast is package-scoped, carries no URL or account data, and only exists because the two
 * injected processes cannot share Java static state.
 */
final class DecoderFormatRelay {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final String ACTION_DECODER_CONFIGURED =
            "dev.xpramt.bilisurfacebridge.DECODER_CONFIGURED";
    private static final String EXTRA_MIME = "mime";
    private static final String EXTRA_BITRATE = "bitrate";
    private static final String EXTRA_SAMPLE_RATE = "sampleRate";
    private static final String EXTRA_MEASURED = "measured";
    private static final String EXTRA_AUDIO_LABEL = "audioLabel";
    private static final String EXTRA_SOURCE_KEY = "sourceKey";
    private static final String EXTRA_MEASUREMENT_GENERATION = "measurementGeneration";
    private static volatile boolean receiverInstalled;

    private DecoderFormatRelay() {
    }

    static void installMainProcessReceiver(Context context) {
        if (receiverInstalled) {
            return;
        }
        if (context == null) {
            Log.i(TAG, "decoder format receiver unavailable: application context missing");
            return;
        }
        context = context.getApplicationContext();
        try {
            IntentFilter filter = new IntentFilter(ACTION_DECODER_CONFIGURED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    String mime = intent == null ? null : intent.getStringExtra(EXTRA_MIME);
                    if (mime == null) {
                        return;
                    }
                    if (intent.getBooleanExtra(EXTRA_MEASURED, false)) {
                        String audioLabel = intent.getStringExtra(EXTRA_AUDIO_LABEL);
                        int bitrate = intent.getIntExtra(EXTRA_BITRATE, -1);
                        String sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY);
                        long generation = intent.getLongExtra(EXTRA_MEASUREMENT_GENERATION, 0L);
                        boolean applied = generation > 0L
                                ? PlaybackStreamOverlay
                                .updateMeasuredAudioBitrateForSourceKeyAndGeneration(
                                        sourceKey, generation, audioLabel, bitrate)
                                : applyMeasuredAudioWithoutGeneration(
                                        sourceKey, audioLabel, bitrate);
                        Log.i(TAG, "[DEBUG-decoder-format-f697] UI received measured audio bitrate="
                                + bitrate + " label=" + audioLabel + " applied=" + applied);
                        return;
                    }
                    if (mime.startsWith("video/")) {
                        PlaybackStreamOverlay.updateActualDecoderMime(mime);
                        Log.i(TAG, "[DEBUG-decoder-format-f697] UI received video MIME=" + mime);
                    } else if (mime.startsWith("audio/")) {
                        int bitrate = intent.getIntExtra(EXTRA_BITRATE, -1);
                        int sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, -1);
                        PlaybackStreamOverlay.updateActualDecoderAudio(mime, bitrate, sampleRate);
                        Log.i(TAG, "[DEBUG-decoder-format-f697] UI received audio MIME=" + mime
                                + " bitrate=" + bitrate + " sampleRate=" + sampleRate);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            receiverInstalled = true;
            Log.i(TAG, "decoder format receiver installed");
        } catch (Throwable throwable) {
            Log.i(TAG, "decoder format receiver unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    static void publishConfiguredVideo(String mime, String decoderName) {
        if (mime == null || !mime.startsWith("video/")
                || !ModulePreferences.loadPlaybackStreamOverlayForHook()) {
            return;
        }
        Context context = currentApplicationContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_DECODER_CONFIGURED)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_MIME, mime);
            context.sendBroadcast(intent);
            Log.i(TAG, "[DEBUG-decoder-format-f697] decoder configured MIME=" + mime
                    + " decoder=" + decoderName);
        } catch (Throwable throwable) {
            Log.i(TAG, "decoder format relay failed=" + throwable.getClass().getSimpleName());
        }
    }

    static void publishConfiguredAudio(String mime, int bitrate, String decoderName) {
        publishConfiguredAudio(mime, bitrate, -1, decoderName);
    }

    static void publishConfiguredAudio(String mime, int bitrate, int sampleRate,
                                       String decoderName) {
        if (mime == null || !mime.startsWith("audio/")
                || !ModulePreferences.loadPlaybackStreamOverlayForHook()) {
            return;
        }
        Context context = currentApplicationContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_DECODER_CONFIGURED)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_MIME, mime)
                    .putExtra(EXTRA_BITRATE, bitrate)
                    .putExtra(EXTRA_SAMPLE_RATE, sampleRate);
            context.sendBroadcast(intent);
            Log.i(TAG, "[DEBUG-decoder-format-f697] decoder configured audio MIME=" + mime
                    + " bitrate=" + bitrate + " sampleRate=" + sampleRate
                    + " decoder=" + decoderName);
        } catch (Throwable throwable) {
            Log.i(TAG, "decoder audio format relay failed="
                    + throwable.getClass().getSimpleName());
        }
    }

    static void publishMeasuredAudioBitrate(String label, long bitrate, String sourceUrl) {
        publishMeasuredAudioBitrate(label, bitrate, sourceUrl, 0L);
    }

    static void publishMeasuredAudioBitrate(String label, long bitrate, String sourceUrl,
                                            long generation) {
        if (label == null || label.trim().isEmpty() || bitrate <= 0L
                || !ModulePreferences.loadPlaybackStreamOverlayForHook()) {
            return;
        }
        Context context = currentApplicationContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_DECODER_CONFIGURED)
                    .setPackage(TARGET_PACKAGE)
                    .putExtra(EXTRA_MIME, "audio/measured")
                    .putExtra(EXTRA_AUDIO_LABEL, label)
                    .putExtra(EXTRA_BITRATE, (int) Math.min(Integer.MAX_VALUE, bitrate))
                    .putExtra(EXTRA_MEASURED, true)
                    .putExtra(EXTRA_SOURCE_KEY, sourceUrl == null ? ""
                            : Integer.toHexString(sourceUrl.hashCode()))
                    .putExtra(EXTRA_MEASUREMENT_GENERATION, generation);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            Log.i(TAG, "measured audio bitrate relay failed="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static boolean applyMeasuredAudioWithoutGeneration(String sourceKey, String label,
                                                               long bitrate) {
        return PlaybackStreamOverlay.updateMeasuredAudioBitrateForSourceKey(
                sourceKey, label, bitrate);
    }

    private static Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = XposedHelpers.callStaticMethod(activityThread,
                    "currentApplication");
            return application instanceof Context
                    ? ((Context) application).getApplicationContext() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

}
