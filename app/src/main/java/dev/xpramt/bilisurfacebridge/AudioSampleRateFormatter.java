package dev.xpramt.bilisurfacebridge;

import java.util.Locale;

/** Formats decoder sample rates for the compact playback overlay. */
final class AudioSampleRateFormatter {
    private AudioSampleRateFormatter() {
    }

    static String format(int sampleRateHz) {
        if (sampleRateHz <= 0) {
            return "";
        }
        if (sampleRateHz < 1000) {
            return sampleRateHz + "Hz";
        }
        if (sampleRateHz % 1000 == 0) {
            return (sampleRateHz / 1000) + "KHz";
        }
        return String.format(Locale.US, "%.1fKHz", sampleRateHz / 1000.0d);
    }

    static String appendTo(String audioLabel, int sampleRateHz) {
        String sampleRate = format(sampleRateHz);
        if (audioLabel == null || audioLabel.isEmpty() || sampleRate.isEmpty()) {
            return audioLabel;
        }
        return audioLabel + " " + sampleRate;
    }
}
