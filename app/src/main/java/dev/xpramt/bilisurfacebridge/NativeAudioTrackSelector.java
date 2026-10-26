package dev.xpramt.bilisurfacebridge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects the highest-bitrate native audio stream without depending on stream-list order. */
final class NativeAudioTrackSelector {
    private NativeAudioTrackSelector() {
    }

    static int chooseHighestBitrateIndex(List<Track> tracks, int[] availableIndexes,
                                         int currentIndex) {
        if (tracks == null || tracks.isEmpty()) {
            return -1;
        }
        Set<Integer> available = new HashSet<Integer>();
        if (availableIndexes != null) {
            for (int index : availableIndexes) {
                available.add(index);
            }
        }
        boolean restrictToAvailable = !available.isEmpty();
        Track best = null;
        for (Track track : tracks) {
            if (track == null || track.index < 0 || track.bitrate <= 0
                    || (restrictToAvailable && !available.contains(track.index))) {
                continue;
            }
            if (best == null || track.bitrate > best.bitrate
                    || (track.bitrate == best.bitrate && track.index == currentIndex)) {
                best = track;
            }
        }
        return best == null ? -1 : best.index;
    }

    static final class Track {
        final int index;
        final long bitrate;

        Track(int index, long bitrate) {
            this.index = index;
            this.bitrate = bitrate;
        }
    }
}
