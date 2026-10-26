package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

public final class Fmp4AacBitrateEstimatorTest {
    @Test
    public void findsAudioTrackAndCountsDefaultSampleSizes() throws IOException {
        byte[] tkhd = new byte[16];
        writeInt32(tkhd, 12, 2);
        byte[] mdhd = new byte[16];
        writeInt32(mdhd, 12, 48000);
        byte[] hdlr = new byte[12];
        hdlr[8] = 's';
        hdlr[9] = 'o';
        hdlr[10] = 'u';
        hdlr[11] = 'n';
        byte[] mdia = box("mdia", concat(box("mdhd", mdhd), box("hdlr", hdlr)));
        byte[] init = box("moov", box("trak", concat(box("tkhd", tkhd), mdia)));

        byte[] tfhd = new byte[16];
        writeInt32(tfhd, 0, 0x18);
        writeInt32(tfhd, 4, 2);
        writeInt32(tfhd, 8, 1024);
        writeInt32(tfhd, 12, 100);
        byte[] trun = new byte[8];
        writeInt32(trun, 4, 2);
        byte[] moof = box("moof", box("traf", concat(box("tfhd", tfhd), box("trun", trun))));
        byte[] media = concat(moof, moof);

        Fmp4AacBitrateEstimator.Track track =
                Fmp4AacBitrateEstimator.findAudioTrack(init);
        assertNotNull(track);
        assertEquals(2, track.trackId);
        assertEquals(48000, track.timescale);
        assertEquals(-1, track.sampleRateHz);

        Fmp4AacBitrateEstimator.Estimate estimate =
                Fmp4AacBitrateEstimator.estimate(media, track);
        assertEquals(400L, estimate.audioPayloadBytes);
        assertEquals(4L, estimate.sampleCount);
        assertEquals(4096L, estimate.durationTicks);
        assertEquals(37500L, estimate.bitrateBitsPerSecond(track.timescale));
    }

    @Test
    public void readsSampleRateFromSelectedAudioSampleEntryNotMdhdTimescale()
            throws IOException {
        byte[] tkhd = new byte[16];
        writeInt32(tkhd, 12, 7);
        byte[] mdhd = new byte[16];
        writeInt32(mdhd, 12, 48000);
        byte[] hdlr = new byte[12];
        hdlr[8] = 's';
        hdlr[9] = 'o';
        hdlr[10] = 'u';
        hdlr[11] = 'n';
        byte[] audioEntry = new byte[28];
        writeInt32(audioEntry, 24, 48000 << 16);
        byte[] streamInfo = new byte[34];
        streamInfo[10] = (byte) (96000 >>> 12);
        streamInfo[11] = (byte) (96000 >>> 4);
        streamInfo[12] = (byte) (96000 << 4);
        byte[] dfLa = box("dfLa", concat(new byte[4],
                new byte[]{(byte) 0x80, 0, 0, 34}, streamInfo));
        byte[] stsd = box("stsd", concat(new byte[4],
                new byte[]{0, 0, 0, 1}, box("fLaC", concat(audioEntry, dfLa))));
        byte[] stbl = box("stbl", stsd);
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", concat(box("mdhd", mdhd), box("hdlr", hdlr), minf));
        byte[] init = box("moov", box("trak", concat(box("tkhd", tkhd), mdia)));

        Fmp4AacBitrateEstimator.Track track = Fmp4AacBitrateEstimator.findAudioTrack(init);
        assertNotNull(track);
        assertEquals(48000, track.timescale);
        assertEquals(96000, track.sampleRateHz);
    }

    private static byte[] box(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt32(output, payload.length + 8);
        output.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(payload);
        return output.toByteArray();
    }

    private static byte[] concat(byte[]... values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value);
        }
        return output.toByteArray();
    }

    private static void writeInt32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static void writeInt32(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }
}
