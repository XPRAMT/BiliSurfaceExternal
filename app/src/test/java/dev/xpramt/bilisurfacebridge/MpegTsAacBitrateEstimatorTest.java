package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class MpegTsAacBitrateEstimatorTest {
    @Test
    public void countsAacPesPayloadAfterPatAndPmt() {
        byte[] data = new byte[188 * 4];
        Arrays.fill(data, (byte) 0xff);
        patPacket(data, 0);
        pmtPacket(data, 188);
        pesPacket(data, 376, true, 170);
        pesPacket(data, 564, false, 184);

        MpegTsAacBitrateEstimator.Estimate estimate =
                MpegTsAacBitrateEstimator.estimate(data);

        assertTrue(estimate.mpegTs);
        assertEquals(257, estimate.audioPid);
        assertEquals(1, estimate.pesCount);
        assertEquals(354L, estimate.audioPayloadBytes);
    }

    private static void patPacket(byte[] data, int offset) {
        header(data, offset, 0, true);
        int p = offset + 4;
        data[p++] = 0;
        data[p++] = 0;
        data[p++] = (byte) 0xb0;
        data[p++] = 13;
        data[p++] = 0;
        data[p++] = 1;
        data[p++] = (byte) 0xc1;
        data[p++] = 0;
        data[p++] = 0;
        data[p++] = 0;
        data[p++] = 1;
        data[p++] = (byte) 0xe1;
        data[p++] = 0;
    }

    private static void pmtPacket(byte[] data, int offset) {
        header(data, offset, 256, true);
        int p = offset + 4;
        data[p++] = 0;
        data[p++] = 2;
        data[p++] = (byte) 0xb0;
        data[p++] = 18;
        data[p++] = 0;
        data[p++] = 1;
        data[p++] = (byte) 0xc1;
        data[p++] = 0;
        data[p++] = 0;
        data[p++] = (byte) 0xe1;
        data[p++] = 1;
        data[p++] = (byte) 0xf0;
        data[p++] = 0;
        data[p++] = 0x0f;
        data[p++] = (byte) 0xe1;
        data[p++] = 1;
        data[p++] = (byte) 0xf0;
        data[p] = 0;
    }

    private static void pesPacket(byte[] data, int offset, boolean start, int payloadBytes) {
        header(data, offset, 257, start);
        if (!start) {
            return;
        }
        int p = offset + 4;
        data[p++] = 0;
        data[p++] = 0;
        data[p++] = 1;
        data[p++] = (byte) 0xc0;
        data[p++] = 0;
        data[p++] = 0;
        data[p++] = (byte) 0x80;
        data[p++] = (byte) 0x80;
        data[p++] = 5;
        p += 5;
        for (int index = 0; index < payloadBytes; index++) {
            data[p++] = 0x55;
        }
    }

    private static void header(byte[] data, int offset, int pid, boolean start) {
        data[offset] = 0x47;
        data[offset + 1] = (byte) ((start ? 0x40 : 0) | ((pid >>> 8) & 0x1f));
        data[offset + 2] = (byte) pid;
        data[offset + 3] = 0x10;
    }
}
