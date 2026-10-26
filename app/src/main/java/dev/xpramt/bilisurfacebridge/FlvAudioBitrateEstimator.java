package dev.xpramt.bilisurfacebridge;

/** Estimates the encoded AAC payload rate from a bounded FLV byte sample. */
final class FlvAudioBitrateEstimator {
    private static final int FLV_HEADER_BYTES = 9;
    private static final int TAG_HEADER_BYTES = 11;
    private static final int AAC_SOUND_FORMAT = 10;
    private static final int AAC_SEQUENCE_HEADER = 0;
    private static final int AAC_RAW = 1;

    private FlvAudioBitrateEstimator() {
    }

    static Estimate estimate(byte[] data) {
        if (data == null || data.length < FLV_HEADER_BYTES + 4
                || data[0] != 'F' || data[1] != 'L' || data[2] != 'V') {
            return Estimate.empty();
        }

        int dataOffset = readInt32(data, 5);
        if (dataOffset < FLV_HEADER_BYTES || dataOffset > data.length - 4) {
            return Estimate.empty();
        }
        int position = dataOffset + 4; // data offset followed by PreviousTagSize0
        int rawTagCount = 0;
        long rawPayloadBytes = 0L;
        long firstTimestampMs = -1L;
        long lastTimestampMs = -1L;

        while (position <= data.length - TAG_HEADER_BYTES) {
            int tagType = data[position] & 0xff;
            int dataSize = readUInt24(data, position + 1);
            long timestampMs = readUInt24(data, position + 4)
                    | ((long) (data[position + 7] & 0xff) << 24);
            int payloadStart = position + TAG_HEADER_BYTES;
            long payloadEnd = (long) payloadStart + dataSize;
            if (dataSize < 0 || payloadEnd > data.length) {
                break; // A bounded read may end in the middle of the next tag.
            }

            if (tagType == 8 && dataSize >= 2) {
                int soundHeader = data[payloadStart] & 0xff;
                int soundFormat = (soundHeader >>> 4) & 0x0f;
                int aacPacketType = data[payloadStart + 1] & 0xff;
                if (soundFormat == AAC_SOUND_FORMAT) {
                    if (aacPacketType == AAC_RAW) {
                        rawTagCount++;
                        rawPayloadBytes += dataSize - 2L;
                        if (firstTimestampMs < 0L) {
                            firstTimestampMs = timestampMs;
                        }
                        lastTimestampMs = timestampMs;
                    }
                }
            }

            position = (int) payloadEnd;
            if (position > data.length - 4) {
                break;
            }
            position += 4; // PreviousTagSize
        }
        return new Estimate(rawTagCount, rawPayloadBytes, firstTimestampMs, lastTimestampMs);
    }

    private static int readUInt24(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 16)
                | ((data[offset + 1] & 0xff) << 8)
                | (data[offset + 2] & 0xff);
    }

    private static int readInt32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    static final class Estimate {
        final int rawAacTagCount;
        final long rawAacPayloadBytes;
        final long firstTimestampMs;
        final long lastTimestampMs;

        private Estimate(int rawAacTagCount, long rawAacPayloadBytes,
                         long firstTimestampMs, long lastTimestampMs) {
            this.rawAacTagCount = rawAacTagCount;
            this.rawAacPayloadBytes = rawAacPayloadBytes;
            this.firstTimestampMs = firstTimestampMs;
            this.lastTimestampMs = lastTimestampMs;
        }

        static Estimate empty() {
            return new Estimate(0, 0L, -1L, -1L);
        }

        boolean hasAac() {
            return rawAacTagCount > 0;
        }

        boolean hasUsableBitrate() {
            return bitrateBitsPerSecond() > 0L;
        }

        long bitrateBitsPerSecond() {
            long durationMs = lastTimestampMs - firstTimestampMs;
            if (rawAacTagCount < 2 || rawAacPayloadBytes <= 0L || durationMs <= 0L) {
                return -1L;
            }
            return rawAacPayloadBytes * 8_000L / durationMs;
        }
    }
}
