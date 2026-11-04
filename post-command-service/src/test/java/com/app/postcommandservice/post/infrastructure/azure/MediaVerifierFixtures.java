package com.app.postcommandservice.post.infrastructure.azure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import org.mp4parser.boxes.iso14496.part12.FileTypeBox;
import org.mp4parser.boxes.iso14496.part12.MovieBox;
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox;

/**
 * Builds minimal, real ISO BMFF (MP4) byte streams and image magic-byte headers for
 * {@code AzureMediaVerifier} tests, without depending on any binary test fixture file.
 *
 * <p>The MP4 bytes only ever contain a {@code ftyp} box and a {@code moov} box with a single
 * {@code mvhd} child (no track/sample data): {@code MovieHeaderBox} is the only box
 * {@code AzureMediaVerifier} reads to extract duration, so this is sufficient for a real,
 * parseable file without needing actual video/audio codec data.</p>
 */
final class MediaVerifierFixtures {

    private MediaVerifierFixtures() {
    }

    static byte[] validMp4(int durationSeconds, int timescale) {
        return buildMp4("isom", 0, (long) durationSeconds * timescale, timescale, 0);
    }

    /** A valid MP4 (10s duration) whose {@code ftyp} box declares {@code majorBrand}. */
    static byte[] mp4WithMajorBrand(String majorBrand) {
        return buildMp4(majorBrand, 0, 10L * 600, 600, 0);
    }

    /**
     * An MP4 whose {@code mvhd} box has the given {@code version} and whose duration field holds
     * exactly {@code rawDuration} (the low 32 bits for version 0, all 64 bits for version 1).
     * The field is patched in the serialized bytes so values mp4parser would refuse to write
     * (e.g. negative ones) can still be produced.
     */
    static byte[] mp4WithRawDuration(int version, long rawDuration, int timescale) {
        byte[] bytes = buildMp4("isom", version, 0, timescale, 0);
        int mvhd = indexOf(bytes, "mvhd".getBytes(StandardCharsets.US_ASCII));
        if (mvhd < 0 || bytes[mvhd + 4] != version) {
            throw new IllegalStateException("Could not build an mvhd box of version " + version);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (version == 0) {
            buffer.putInt(mvhd + 20, (int) rawDuration);
        } else {
            buffer.putLong(mvhd + 28, rawDuration);
        }
        return bytes;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Same as {@link #validMp4} but with {@code mdatFillerBytes} of raw {@code mdat} content
     * written *before* the {@code moov} box, so the movie header box physically sits at the
     * end of the file, exercising the real-world "moov atom at the end" case.
     */
    static byte[] validMp4WithMoovAtEnd(int durationSeconds, int timescale, int mdatFillerBytes) {
        return buildMp4("isom", 0, (long) durationSeconds * timescale, timescale, mdatFillerBytes);
    }

    private static byte[] buildMp4(
            String majorBrand, int version, long durationTicks, int timescale, int mdatFillerBytes) {
        try {
            FileTypeBox ftyp = new FileTypeBox(majorBrand, 512, List.of("isom", "iso2", "mp41"));

            MovieHeaderBox mvhd = new MovieHeaderBox();
            mvhd.setTimescale(timescale);
            mvhd.setDuration(durationTicks);
            mvhd.setVersion(version);
            mvhd.setCreationTime(new Date());
            mvhd.setModificationTime(new Date());

            MovieBox moov = new MovieBox();
            moov.addBox(mvhd);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            WritableByteChannel channel = Channels.newChannel(out);
            ftyp.getBox(channel);
            if (mdatFillerBytes > 0) {
                writeMdatBox(channel, mdatFillerBytes);
            }
            moov.getBox(channel);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeMdatBox(WritableByteChannel channel, int payloadSize) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        header.putInt(8 + payloadSize);
        header.put("mdat".getBytes(StandardCharsets.US_ASCII));
        header.flip();
        channel.write(header);
        channel.write(ByteBuffer.wrap(new byte[payloadSize]));
    }

    static byte[] jpeg(int totalSize) {
        byte[] data = new byte[totalSize];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        return data;
    }

    static byte[] png(int totalSize) {
        byte[] data = new byte[totalSize];
        int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            data[i] = (byte) signature[i];
        }
        return data;
    }

    static byte[] webp(int totalSize) {
        byte[] data = new byte[totalSize];
        writeAscii(data, 0, "RIFF");
        writeAscii(data, 8, "WEBP");
        return data;
    }

    static byte[] gif(int totalSize) {
        byte[] data = new byte[totalSize];
        writeAscii(data, 0, "GIF8");
        return data;
    }

    static byte[] garbage(int totalSize) {
        byte[] data = new byte[totalSize];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static void writeAscii(byte[] data, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }
}
