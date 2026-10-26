package dev.xpramt.bilisurfacebridge;

/** Pure mapping between the official IJK codec enum names and compact overlay labels. */
final class StreamCodecLabel {
    private StreamCodecLabel() {
    }

    static String videoForEnumName(String codec) {
        if ("H264".equals(codec)) {
            return "AVC";
        }
        if ("H265".equals(codec)) {
            return "HEVC";
        }
        if ("AV1".equals(codec)) {
            return "AV1";
        }
        if ("H266".equals(codec)) {
            return "VVC";
        }
        return "UNKNOWN";
    }

    /** Maps the MIME accepted by the actual platform decoder, not a DASH manifest declaration. */
    static String videoForDecoderMime(String mime) {
        if ("video/avc".equals(mime)) {
            return "AVC";
        }
        if ("video/hevc".equals(mime)) {
            return "HEVC";
        }
        if ("video/av01".equals(mime)) {
            return "AV1";
        }
        if ("video/vvc".equals(mime)) {
            return "VVC";
        }
        return "UNKNOWN";
    }

    /** Maps the MIME accepted by the actual platform audio decoder to the overlay label. */
    static String audioForDecoderMime(String mime) {
        if ("audio/mp4a-latm".equals(mime) || "audio/aac".equals(mime)) {
            return "AAC";
        }
        if ("audio/ac3".equals(mime) || "audio/eac3".equals(mime)
                || "audio/eac3-joc".equals(mime)) {
            return "Dolby";
        }
        if ("audio/opus".equals(mime)) {
            return "OPUS";
        }
        if ("audio/vorbis".equals(mime)) {
            return "VORBIS";
        }
        if ("audio/flac".equals(mime)) {
            return "FLAC";
        }
        return "UNKNOWN";
    }

    /** Maps IJK's native decoder name/implementation pair to the displayed audio format. */
    static String audioForDecoderName(String decoder, String implementation) {
        String normalized = ((decoder == null ? "" : decoder) + " "
                + (implementation == null ? "" : implementation)).trim().toUpperCase(
                java.util.Locale.US);
        if (normalized.contains("AAC") || normalized.contains("MP4A")) {
            return "AAC";
        }
        if (normalized.contains("EAC3") || normalized.contains("AC3")
                || normalized.contains("DOLBY")) {
            return "Dolby";
        }
        if (normalized.contains("OPUS")) {
            return "OPUS";
        }
        if (normalized.contains("VORBIS")) {
            return "VORBIS";
        }
        if (normalized.contains("FLAC")) {
            return "FLAC";
        }
        return "UNKNOWN";
    }

    /** A quality tier and its decoded codec need not have the same display name. */
    static boolean audioDecoderMatchesSelection(String selectedFormat, String decoderFormat) {
        if (selectedFormat == null || decoderFormat == null) {
            return false;
        }
        if (selectedFormat.equalsIgnoreCase(decoderFormat)) {
            return true;
        }
        return "Hi-Res".equalsIgnoreCase(selectedFormat)
                && "FLAC".equalsIgnoreCase(decoderFormat);
    }

    /**
     * Maps the official IJK audio quality and codec enum names to the one-line overlay label.
     *
     * <p>The current official app can return an empty codec enum for a normal DASH audio stream.
     * That stream is still the ordinary AAC track; keeping the empty enum visible as an empty
     * label would make the entire audio part disappear from the overlay.</p>
     */
    static String audioForEnumName(String quality, String codec) {
        String normalizedQuality = quality == null ? "" : quality.trim().toUpperCase(
                java.util.Locale.US).replace("_", "").replace("-", "");
        if (normalizedQuality.contains("DOLBY")) {
            return "Dolby";
        }
        if (normalizedQuality.contains("HIRES")) {
            return "Hi-Res";
        }
        String normalizedCodec = codec == null ? "" : codec.trim().toUpperCase(
                java.util.Locale.US);
        if (normalizedCodec.contains("AAC") || normalizedCodec.contains("MP4A")
                || normalizedCodec.contains("M4A")) {
            return "AAC";
        }
        if (!normalizedCodec.isEmpty()) {
            return normalizedCodec;
        }
        if ("NORMAL".equals(normalizedQuality) || normalizedQuality.isEmpty()) {
            return "AAC";
        }
        return "UNKNOWN";
    }
}
