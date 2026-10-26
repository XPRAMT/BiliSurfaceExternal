package dev.xpramt.bilisurfacebridge;

/** Formats measured audio rates using the common display rate nearest to the measured value. */
final class AudioBitrateFormatter {
    private static final int[] COMMON_KILOBITS =
            new int[]{32, 48, 64, 96, 128, 160, 192, 256, 320, 384, 512, 640, 768, 1024};

    private AudioBitrateFormatter() {
    }

    static String format(long bitsPerSecond) {
        if (bitsPerSecond <= 0L) {
            return "";
        }
        // Very low-rate tracks (for example preview/silent Hi-Res variants) are not close to
        // the normal 32K floor.  Rounding them to 32K hides the measured value.
        if (bitsPerSecond < 16_000L) {
            return Math.max(1L, Math.round(bitsPerSecond / 1000.0d)) + "K";
        }
        long bestDistance = Long.MAX_VALUE;
        int bestK = 0;
        for (int commonK : COMMON_KILOBITS) {
            long decimal = commonK * 1000L;
            long binary = commonK * 1024L;
            long decimalDistance = Math.abs(bitsPerSecond - decimal);
            if (decimalDistance < bestDistance) {
                bestDistance = decimalDistance;
                bestK = commonK;
            }
            long binaryDistance = Math.abs(bitsPerSecond - binary);
            if (binaryDistance < bestDistance) {
                bestDistance = binaryDistance;
                bestK = commonK;
            }
        }
        if (bestK > 0) {
            return bestK + "K";
        }
        return Math.round(bitsPerSecond / 1000.0d) + "K";
    }
}
