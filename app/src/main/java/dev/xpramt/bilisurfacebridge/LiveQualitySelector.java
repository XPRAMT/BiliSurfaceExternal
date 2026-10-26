package dev.xpramt.bilisurfacebridge;

import java.util.List;

/** Chooses the highest usable live URL without relying on API list order. */
final class LiveQualitySelector {
    private LiveQualitySelector() {
    }

    static Candidate chooseHighest(List<Candidate> candidates, int currentQnId) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.qn <= 0 || candidate.qnId <= 0) {
                continue;
            }
            if (best == null || isBetter(candidate, best, currentQnId)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isBetter(Candidate candidate, Candidate best, int currentQnId) {
        boolean candidateHasUrl = candidate.urlBandwidth > 0;
        boolean bestHasUrl = best.urlBandwidth > 0;
        if (candidateHasUrl != bestHasUrl) {
            // A quality description without a corresponding URL cannot be played.
            return candidateHasUrl;
        }
        if (candidate.qn != best.qn) {
            return candidate.qn > best.qn;
        }
        if (candidateHasUrl && candidate.urlBandwidth != best.urlBandwidth) {
            return candidate.urlBandwidth > best.urlBandwidth;
        }
        if (candidate.qnId == currentQnId && best.qnId != currentQnId) {
            return true;
        }
        if (candidate.qnId != currentQnId && best.qnId == currentQnId) {
            return false;
        }
        return candidate.qnId > best.qnId;
    }

    static final class Candidate {
        final int qn;
        final int qnId;
        final int hdrType;
        final int urlBandwidth;
        final int codec;

        Candidate(int qn, int qnId, int hdrType, int urlBandwidth, int codec) {
            this.qn = qn;
            this.qnId = qnId;
            this.hdrType = hdrType;
            this.urlBandwidth = urlBandwidth;
            this.codec = codec;
        }
    }
}
