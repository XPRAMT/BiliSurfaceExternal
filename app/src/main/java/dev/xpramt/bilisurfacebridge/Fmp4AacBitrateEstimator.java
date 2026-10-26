package dev.xpramt.bilisurfacebridge;

/** Extracts audio sample sizes from an unencrypted fragmented MP4 HLS stream. */
final class Fmp4AacBitrateEstimator {
    private static final int TFHD_DEFAULT_SAMPLE_DURATION = 0x000008;
    private static final int TFHD_DEFAULT_SAMPLE_SIZE = 0x000010;
    private static final int TRUN_DATA_OFFSET = 0x000001;
    private static final int TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
    private static final int TRUN_SAMPLE_DURATION = 0x000100;
    private static final int TRUN_SAMPLE_SIZE = 0x000200;
    private static final int TRUN_SAMPLE_FLAGS = 0x000400;
    private static final int TRUN_SAMPLE_COMPOSITION_OFFSET = 0x000800;

    private Fmp4AacBitrateEstimator() {
    }

    static Track findAudioTrack(byte[] init) {
        Box moov = findChild(init, 0, init == null ? 0 : init.length, "moov");
        if (moov == null) {
            return null;
        }
        int cursor = moov.dataStart;
        while (cursor < moov.end) {
            Box trak = readBox(init, cursor, moov.end);
            if (trak == null) {
                break;
            }
            Box mdia = findChild(init, trak.dataStart, trak.end, "mdia");
            Box hdlr = mdia == null ? null : findChild(init, mdia.dataStart, mdia.end, "hdlr");
            if (hdlr != null && hdlr.dataStart + 12 <= hdlr.end
                    && u8(init, hdlr.dataStart + 8) == 's'
                    && u8(init, hdlr.dataStart + 9) == 'o'
                    && u8(init, hdlr.dataStart + 10) == 'u'
                    && u8(init, hdlr.dataStart + 11) == 'n') {
                Box tkhd = findChild(init, trak.dataStart, trak.end, "tkhd");
                Box mdhd = mdia == null ? null : findChild(init, mdia.dataStart, mdia.end, "mdhd");
                int trackId = tkhd == null ? -1 : trackId(init, tkhd);
                long timescale = mdhd == null ? -1L : mediaTimescale(init, mdhd);
                if (trackId >= 0 && timescale > 0L) {
                    return new Track(trackId, timescale, audioSampleRate(init, mdia));
                }
            }
            cursor = trak.end;
        }
        return null;
    }

    /** AudioSampleEntry stores the encoded stream rate as a 16.16 value in stsd. */
    private static int audioSampleRate(byte[] init, Box mdia) {
        Box minf = findChild(init, mdia.dataStart, mdia.end, "minf");
        Box stbl = minf == null ? null : findChild(init, minf.dataStart, minf.end, "stbl");
        Box stsd = stbl == null ? null : findChild(init, stbl.dataStart, stbl.end, "stsd");
        if (stsd == null || stsd.dataStart + 8 > stsd.end
                || uint32(init, stsd.dataStart + 4) < 1L) {
            return -1;
        }
        Box entry = readBox(init, stsd.dataStart + 8, stsd.end);
        if (entry == null || entry.dataStart + 28 > entry.end) {
            return -1;
        }
        if ("fLaC".equals(entry.type)) {
            // AudioSampleEntry's 16.16 field cannot represent 96 kHz. FLAC carries its
            // authoritative rate in the 20-bit STREAMINFO field inside dfLa.
            Box dfLa = findChild(init, entry.dataStart + 28, entry.end, "dfLa");
            if (dfLa == null || dfLa.dataStart + 8 + 34 > dfLa.end
                    || (u8(init, dfLa.dataStart + 4) & 0x7f) != 0
                    || ((u8(init, dfLa.dataStart + 5) << 16)
                    | (u8(init, dfLa.dataStart + 6) << 8)
                    | u8(init, dfLa.dataStart + 7)) != 34) {
                return -1;
            }
            int streamInfo = dfLa.dataStart + 8;
            int flacRate = (u8(init, streamInfo + 10) << 12)
                    | (u8(init, streamInfo + 11) << 4)
                    | (u8(init, streamInfo + 12) >>> 4);
            return flacRate >= 8000 && flacRate <= 384000 ? flacRate : -1;
        }
        int sampleRate = (int) (uint32(init, entry.dataStart + 24) >>> 16);
        return sampleRate >= 8000 && sampleRate <= 384000 ? sampleRate : -1;
    }

    static Estimate estimate(byte[] media, Track audioTrack) {
        if (media == null || audioTrack == null) {
            return Estimate.empty();
        }
        long audioBytes = 0L;
        long sampleCount = 0L;
        long durationTicks = 0L;
        int topLevelCursor = 0;
        while (topLevelCursor < media.length) {
            Box topLevel = readBox(media, topLevelCursor, media.length);
            if (topLevel == null) {
                break;
            }
            if ("moof".equals(topLevel.type)) {
                Estimate moofEstimate = estimateMoof(media, topLevel, audioTrack.trackId);
                audioBytes += moofEstimate.audioPayloadBytes;
                sampleCount += moofEstimate.sampleCount;
                durationTicks += moofEstimate.durationTicks;
            }
            topLevelCursor = topLevel.end;
        }
        return new Estimate(audioBytes, sampleCount, durationTicks);
    }

    private static Estimate estimateMoof(byte[] media, Box moof, int audioTrackId) {
        long audioBytes = 0L;
        long sampleCount = 0L;
        long durationTicks = 0L;
        int cursor = moof.dataStart;
        while (cursor < moof.end) {
            Box traf = readBox(media, cursor, moof.end);
            if (traf == null) {
                break;
            }
            Box tfhd = findChild(media, traf.dataStart, traf.end, "tfhd");
            if (tfhd != null && tfhdTrackId(media, tfhd) == audioTrackId) {
                TfhdDefaults defaults = parseTfhd(media, tfhd);
                int child = traf.dataStart;
                while (child < traf.end) {
                    Box trun = readBox(media, child, traf.end);
                    if (trun == null) {
                        break;
                    }
                    if ("trun".equals(trun.type)) {
                        TrunEstimate trunEstimate = parseTrun(media, trun, defaults);
                        audioBytes += trunEstimate.sampleBytes;
                        sampleCount += trunEstimate.sampleCount;
                        durationTicks += trunEstimate.durationTicks;
                    }
                    child = trun.end;
                }
            }
            cursor = traf.end;
        }
        return new Estimate(audioBytes, sampleCount, durationTicks);
    }

    private static TfhdDefaults parseTfhd(byte[] data, Box tfhd) {
        int flags = readInt32(data, tfhd.dataStart) & 0x00ffffff;
        int cursor = tfhd.dataStart + 8; // full box version/flags + track ID
        if ((flags & 0x000001) != 0) {
            cursor += 8;
        }
        if ((flags & 0x000002) != 0) {
            cursor += 4;
        }
        long defaultDuration = -1L;
        long defaultSize = -1L;
        if ((flags & TFHD_DEFAULT_SAMPLE_DURATION) != 0 && cursor + 4 <= tfhd.end) {
            defaultDuration = uint32(data, cursor);
            cursor += 4;
        }
        if ((flags & TFHD_DEFAULT_SAMPLE_SIZE) != 0 && cursor + 4 <= tfhd.end) {
            defaultSize = uint32(data, cursor);
        }
        return new TfhdDefaults(defaultDuration, defaultSize);
    }

    private static TrunEstimate parseTrun(byte[] data, Box trun, TfhdDefaults defaults) {
        int flags = readInt32(data, trun.dataStart) & 0x00ffffff;
        int sampleCount = readInt32(data, trun.dataStart + 4);
        int cursor = trun.dataStart + 8;
        if ((flags & TRUN_DATA_OFFSET) != 0) {
            cursor += 4;
        }
        if ((flags & TRUN_FIRST_SAMPLE_FLAGS) != 0) {
            cursor += 4;
        }
        long sampleBytes = 0L;
        long durationTicks = 0L;
        int parsed = 0;
        for (int index = 0; index < sampleCount && cursor <= trun.end; index++) {
            long duration = defaults.defaultDuration;
            if ((flags & TRUN_SAMPLE_DURATION) != 0) {
                if (cursor + 4 > trun.end) {
                    break;
                }
                duration = uint32(data, cursor);
                cursor += 4;
            }
            long size = defaults.defaultSize;
            if ((flags & TRUN_SAMPLE_SIZE) != 0 && cursor + 4 <= trun.end) {
                size = uint32(data, cursor);
                cursor += 4;
            }
            if ((flags & TRUN_SAMPLE_FLAGS) != 0) {
                cursor += 4;
            }
            if ((flags & TRUN_SAMPLE_COMPOSITION_OFFSET) != 0) {
                cursor += 4;
            }
            if (size >= 0L) {
                sampleBytes += size;
                parsed++;
                if (duration >= 0L) {
                    durationTicks += duration;
                }
            }
            if (cursor > trun.end) {
                break;
            }
        }
        return new TrunEstimate(sampleBytes, parsed, durationTicks);
    }

    private static int trackId(byte[] data, Box box) {
        if (box.dataStart + 16 > box.end) {
            return -1;
        }
        int version = u8(data, box.dataStart);
        int offset = version == 1 ? box.dataStart + 20 : box.dataStart + 12;
        return offset + 4 <= box.end ? readInt32(data, offset) : -1;
    }

    private static long mediaTimescale(byte[] data, Box box) {
        if (box.dataStart + 16 > box.end) {
            return -1L;
        }
        int version = u8(data, box.dataStart);
        int offset = version == 1 ? box.dataStart + 20 : box.dataStart + 12;
        return offset + 4 <= box.end ? uint32(data, offset) : -1L;
    }

    private static int tfhdTrackId(byte[] data, Box box) {
        int offset = box.dataStart + 4;
        return offset + 4 <= box.end ? readInt32(data, offset) : -1;
    }

    private static Box findChild(byte[] data, int start, int end, String type) {
        if (data == null) {
            return null;
        }
        int cursor = start;
        while (cursor < end) {
            Box box = readBox(data, cursor, end);
            if (box == null) {
                return null;
            }
            if (type.equals(box.type)) {
                return box;
            }
            cursor = box.end;
        }
        return null;
    }

    private static Box readBox(byte[] data, int start, int limit) {
        if (data == null || start < 0 || start + 8 > limit || start + 8 > data.length) {
            return null;
        }
        long size = uint32(data, start);
        int header = 8;
        if (size == 1L) {
            if (start + 16 > limit) {
                return null;
            }
            size = readLong(data, start + 8);
            header = 16;
        } else if (size == 0L) {
            size = limit - start;
        }
        if (size < header || size > Integer.MAX_VALUE || start + size > limit) {
            return null;
        }
        String type = new String(data, start + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return new Box(type, start + header, (int) (start + size));
    }

    private static int readInt32(byte[] data, int offset) {
        return (int) uint32(data, offset);
    }

    private static long uint32(byte[] data, int offset) {
        return ((long) u8(data, offset) << 24)
                | ((long) u8(data, offset + 1) << 16)
                | ((long) u8(data, offset + 2) << 8)
                | u8(data, offset + 3);
    }

    private static long readLong(byte[] data, int offset) {
        return (uint32(data, offset) << 32) | uint32(data, offset + 4);
    }

    private static int u8(byte[] data, int offset) {
        return data[offset] & 0xff;
    }

    static final class Track {
        final int trackId;
        final long timescale;
        final int sampleRateHz;

        Track(int trackId, long timescale, int sampleRateHz) {
            this.trackId = trackId;
            this.timescale = timescale;
            this.sampleRateHz = sampleRateHz;
        }
    }

    static final class Estimate {
        final long audioPayloadBytes;
        final long sampleCount;
        final long durationTicks;

        private Estimate(long audioPayloadBytes, long sampleCount, long durationTicks) {
            this.audioPayloadBytes = audioPayloadBytes;
            this.sampleCount = sampleCount;
            this.durationTicks = durationTicks;
        }

        static Estimate empty() {
            return new Estimate(0L, 0L, 0L);
        }

        long bitrateBitsPerSecond(long timescale) {
            if (audioPayloadBytes <= 0L || durationTicks <= 0L || timescale <= 0L) {
                return -1L;
            }
            return Math.round(audioPayloadBytes * 8.0d * timescale / durationTicks);
        }
    }

    private static final class Box {
        final String type;
        final int dataStart;
        final int end;

        Box(String type, int dataStart, int end) {
            this.type = type;
            this.dataStart = dataStart;
            this.end = end;
        }
    }

    private static final class TfhdDefaults {
        final long defaultDuration;
        final long defaultSize;

        TfhdDefaults(long defaultDuration, long defaultSize) {
            this.defaultDuration = defaultDuration;
            this.defaultSize = defaultSize;
        }
    }

    private static final class TrunEstimate {
        final long sampleBytes;
        final int sampleCount;
        final long durationTicks;

        TrunEstimate(long sampleBytes, int sampleCount, long durationTicks) {
            this.sampleBytes = sampleBytes;
            this.sampleCount = sampleCount;
            this.durationTicks = durationTicks;
        }
    }
}
