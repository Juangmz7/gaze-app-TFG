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
        return buildMp4(durationSeconds, timescale, 0);
    }

    /**
     * Same as {@link #validMp4} but with {@code mdatFillerBytes} of raw {@code mdat} content
     * written *before* the {@code moov} box, so the movie header box physically sits at the
     * end of the file, exercising the real-world "moov atom at the end" case.
     */
    static byte[] validMp4WithMoovAtEnd(int durationSeconds, int timescale, int mdatFillerBytes) {
        return buildMp4(durationSeconds, timescale, mdatFillerBytes);
    }

    private static byte[] buildMp4(int durationSeconds, int timescale, int mdatFillerBytes) {
        try {
            FileTypeBox ftyp = new FileTypeBox("isom", 512, List.of("isom", "iso2", "mp41"));

            MovieHeaderBox mvhd = new MovieHeaderBox();
            mvhd.setTimescale(timescale);
            mvhd.setDuration((long) durationSeconds * timescale);
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
