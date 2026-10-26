package dev.xpramt.bilisurfacebridge;

import java.util.Map;

/** Resolves a switched audio label when a rebuilt player asset omits the old candidate row. */
final class AudioTrackLabelResolver {
    private AudioTrackLabelResolver() {
    }

    static String resolve(int qualityId, Map<Integer, String> current,
                          Map<Integer, String> known) {
        String label = current == null ? null : current.get(qualityId);
        if (label != null && !label.trim().isEmpty()) {
            return label;
        }
        return known == null ? null : known.get(qualityId);
    }
}
