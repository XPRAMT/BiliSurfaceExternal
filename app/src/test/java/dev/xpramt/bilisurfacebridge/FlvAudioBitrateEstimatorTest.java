package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

public final class FlvAudioBitrateEstimatorTest {
    @Test
    public void estimatesAacBytesOverAudioTimestampSpan() throws IOException {
        byte[] fixture = flv(
                new AudioTag(0, 1, 1000),
                new AudioTag(1000, 1, 1000),
                new AudioTag(2000, 1, 1000));

        FlvAudioBitrateEstimator.Estimate estimate =
                FlvAudioBitrateEstimator.estimate(fixture);

        assertEquals(3, estimate.rawAacTagCount);
        assertEquals(3000, estimate.rawAacPayloadBytes);
        assertEquals(0, estimate.firstTimestampMs);
        assertEquals(2000, estimate.lastTimestampMs);
        assertEquals(12000, estimate.bitrateBitsPerSecond());
    }

    @Test
    public void ignoresSequenceHeaderAndNonAacAudio() throws IOException {
        byte[] fixture = flv(
                new AudioTag(0, 0, 500),
                new AudioTag(1000, 1, 1000),
                new AudioTag(2000, 2, 2000));

        FlvAudioBitrateEstimator.Estimate estimate =
                FlvAudioBitrateEstimator.estimate(fixture);

        assertEquals(1, estimate.rawAacTagCount);
        assertEquals(1000, estimate.rawAacPayloadBytes);
        assertEquals(1000, estimate.firstTimestampMs);
        assertEquals(1000, estimate.lastTimestampMs);
        assertEquals(-1, estimate.bitrateBitsPerSecond());
        assertTrue(estimate.hasAac());
        assertFalse(estimate.hasUsableBitrate());
    }

    private static byte[] flv(AudioTag... tags) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{'F', 'L', 'V', 1, 5, 0, 0, 0, 9});
        writeInt32(output, 0);
        for (AudioTag tag : tags) {
            int dataSize = tag.payloadBytes + 2;
            output.write(8);
            writeInt24(output, dataSize);
            writeInt24(output, tag.timestampMs & 0x00ffffff);
            output.write((tag.timestampMs >>> 24) & 0xff);
            writeInt24(output, 0);
            output.write(0xaf);
            output.write(tag.packetType);
            for (int index = 0; index < tag.payloadBytes; index++) {
                output.write(0x55);
            }
            writeInt32(output, 11 + dataSize);
        }
        return output.toByteArray();
    }

    private static void writeInt24(ByteArrayOutputStream output, int value) {
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeInt32(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static final class AudioTag {
        final int timestampMs;
        final int packetType;
        final int payloadBytes;

        AudioTag(int timestampMs, int packetType, int payloadBytes) {
            this.timestampMs = timestampMs;
            this.packetType = packetType;
            this.payloadBytes = payloadBytes;
        }
    }
}
