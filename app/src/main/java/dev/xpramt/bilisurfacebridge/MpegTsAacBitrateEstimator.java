package dev.xpramt.bilisurfacebridge;

/** Counts AAC PES payload bytes in an MPEG-TS HLS segment sample. */
final class MpegTsAacBitrateEstimator {
    private static final int TS_PACKET_BYTES = 188;

    private MpegTsAacBitrateEstimator() {
    }

    static Estimate estimate(byte[] data) {
        int offset = findSyncOffset(data);
        if (offset < 0) {
            return Estimate.empty();
        }
        int pmtPid = findPmtPid(data, offset);
        if (pmtPid < 0) {
            return Estimate.empty();
        }
        int audioPid = findAudioPid(data, offset, pmtPid);
        if (audioPid < 0) {
            return Estimate.empty();
        }

        long payloadBytes = 0L;
        int pesCount = 0;
        boolean inAudioPes = false;
        for (int position = offset; position + TS_PACKET_BYTES <= data.length;
             position += TS_PACKET_BYTES) {
            Packet packet = packet(data, position);
            if (packet == null || packet.pid != audioPid || packet.payloadLength <= 0) {
                continue;
            }
            if (packet.payloadUnitStart) {
                inAudioPes = false;
                int payload = packet.payloadOffset;
                if (payload + 9 <= packet.payloadEnd
                        && u8(data, payload) == 0x00
                        && u8(data, payload + 1) == 0x00
                        && u8(data, payload + 2) == 0x01) {
                    int headerBytes = 9 + u8(data, payload + 8);
                    if (payload + headerBytes <= packet.payloadEnd) {
                        payloadBytes += packet.payloadEnd - payload - headerBytes;
                        pesCount++;
                        inAudioPes = true;
                    }
                }
            } else if (inAudioPes) {
                payloadBytes += packet.payloadLength;
            }
        }
        return new Estimate(true, audioPid, pesCount, payloadBytes);
    }

    private static int findSyncOffset(byte[] data) {
        if (data == null || data.length < TS_PACKET_BYTES * 3) {
            return -1;
        }
        for (int candidate = 0; candidate < TS_PACKET_BYTES; candidate++) {
            if (candidate + TS_PACKET_BYTES * 2 < data.length
                    && u8(data, candidate) == 0x47
                    && u8(data, candidate + TS_PACKET_BYTES) == 0x47
                    && u8(data, candidate + TS_PACKET_BYTES * 2) == 0x47) {
                return candidate;
            }
        }
        return -1;
    }

    private static int findPmtPid(byte[] data, int offset) {
        for (int position = offset; position + TS_PACKET_BYTES <= data.length;
             position += TS_PACKET_BYTES) {
            Packet packet = packet(data, position);
            if (packet == null || packet.pid != 0 || !packet.payloadUnitStart) {
                continue;
            }
            int section = sectionStart(data, packet);
            if (section < 0 || section + 12 > packet.payloadEnd || u8(data, section) != 0x00) {
                continue;
            }
            int sectionLength = ((u8(data, section + 1) & 0x0f) << 8)
                    | u8(data, section + 2);
            int end = Math.min(packet.payloadEnd, section + 3 + sectionLength - 4);
            for (int cursor = section + 8; cursor + 4 <= end; cursor += 4) {
                int program = (u8(data, cursor) << 8) | u8(data, cursor + 1);
                if (program != 0) {
                    return ((u8(data, cursor + 2) & 0x1f) << 8) | u8(data, cursor + 3);
                }
            }
        }
        return -1;
    }

    private static int findAudioPid(byte[] data, int offset, int pmtPid) {
        for (int position = offset; position + TS_PACKET_BYTES <= data.length;
             position += TS_PACKET_BYTES) {
            Packet packet = packet(data, position);
            if (packet == null || packet.pid != pmtPid || !packet.payloadUnitStart) {
                continue;
            }
            int section = sectionStart(data, packet);
            if (section < 0 || section + 12 > packet.payloadEnd || u8(data, section) != 0x02) {
                continue;
            }
            int sectionLength = ((u8(data, section + 1) & 0x0f) << 8)
                    | u8(data, section + 2);
            int end = Math.min(packet.payloadEnd, section + 3 + sectionLength - 4);
            if (section + 12 > end) {
                continue;
            }
            int programInfoLength = ((u8(data, section + 10) & 0x0f) << 8)
                    | u8(data, section + 11);
            int cursor = section + 12 + programInfoLength;
            while (cursor + 5 <= end) {
                int streamType = u8(data, cursor);
                int pid = ((u8(data, cursor + 1) & 0x1f) << 8) | u8(data, cursor + 2);
                int esInfoLength = ((u8(data, cursor + 3) & 0x0f) << 8)
                        | u8(data, cursor + 4);
                if (streamType == 0x0f || streamType == 0x11) {
                    return pid;
                }
                cursor += 5 + esInfoLength;
            }
        }
        return -1;
    }

    private static int sectionStart(byte[] data, Packet packet) {
        if (packet.payloadOffset >= packet.payloadEnd) {
            return -1;
        }
        int pointer = u8(data, packet.payloadOffset);
        int section = packet.payloadOffset + 1 + pointer;
        return section < packet.payloadEnd ? section : -1;
    }

    private static Packet packet(byte[] data, int position) {
        if (position < 0 || position + TS_PACKET_BYTES > data.length
                || u8(data, position) != 0x47) {
            return null;
        }
        int pid = ((u8(data, position + 1) & 0x1f) << 8) | u8(data, position + 2);
        int adaptationControl = (u8(data, position + 3) >>> 4) & 0x03;
        if (adaptationControl == 0) {
            return null;
        }
        int payloadOffset = position + 4;
        if ((adaptationControl & 0x02) != 0) {
            if (payloadOffset >= position + TS_PACKET_BYTES) {
                return null;
            }
            payloadOffset += 1 + u8(data, payloadOffset);
        }
        if ((adaptationControl & 0x01) == 0 || payloadOffset > position + TS_PACKET_BYTES) {
            payloadOffset = position + TS_PACKET_BYTES;
        }
        return new Packet(pid, (u8(data, position + 1) & 0x40) != 0,
                payloadOffset, position + TS_PACKET_BYTES);
    }

    private static int u8(byte[] data, int offset) {
        return data[offset] & 0xff;
    }

    private static final class Packet {
        final int pid;
        final boolean payloadUnitStart;
        final int payloadOffset;
        final int payloadEnd;
        final int payloadLength;

        Packet(int pid, boolean payloadUnitStart, int payloadOffset, int payloadEnd) {
            this.pid = pid;
            this.payloadUnitStart = payloadUnitStart;
            this.payloadOffset = payloadOffset;
            this.payloadEnd = payloadEnd;
            this.payloadLength = Math.max(0, payloadEnd - payloadOffset);
        }
    }

    static final class Estimate {
        final boolean mpegTs;
        final int audioPid;
        final int pesCount;
        final long audioPayloadBytes;

        private Estimate(boolean mpegTs, int audioPid, int pesCount, long audioPayloadBytes) {
            this.mpegTs = mpegTs;
            this.audioPid = audioPid;
            this.pesCount = pesCount;
            this.audioPayloadBytes = audioPayloadBytes;
        }

        static Estimate empty() {
            return new Estimate(false, -1, 0, 0L);
        }
    }
}
