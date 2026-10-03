package com.app.postcommandservice.post.infrastructure.azure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.application.port.MediaToVerify;
import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
import com.app.postcommandservice.post.application.port.MediaVerificationResult;
import com.app.postcommandservice.post.application.port.MediaVerifier;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.azure.storage.blob.models.BlobStorageException;

import org.mp4parser.IsoFile;
import org.mp4parser.boxes.iso14496.part12.MovieBox;
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox;
import org.springframework.stereotype.Component;

/**
 * Azure Blob Storage implementation of {@link MediaVerifier}. Checks, cheapest first: the blob
 * exists (size read from its properties, never a full download), is not empty, is within the
 * configured size limit, and its real content type (detected from magic bytes, never the
 * client-set Content-Type property) matches the expected role. VIDEO content blobs that pass
 * are additionally streamed to a temporary file to extract their duration via
 * {@code org.mp4parser}, since the Azure SDK exposes no media metadata.
 *
 * <p>Business inconsistencies are always returned as a {@link MediaVerificationResult.Failure},
 * never thrown. {@link BlobStorageException} with a 404 status is the only Azure error mapped to
 * a failure ({@code BLOB_NOT_FOUND}); every other exception (timeouts, 5xx, network failures)
 * propagates so a message-driven caller can retry. This class performs no database access and
 * is not transactional.</p>
 */
@Component
public class AzureMediaVerifier implements MediaVerifier {

    /**
     * Large enough to cover every magic-byte signature this verifier recognizes (the longest
     * is the WEBP "RIFF....WEBP" pattern at 12 bytes, and the ISO BMFF {@code ftyp} major brand
     * at offset 8-11), with margin.
     */
    private static final int HEADER_BYTES = 64;

    private static final Set<DetectedFormat> VIDEO_FORMATS = EnumSet.of(DetectedFormat.MP4, DetectedFormat.MOV);

    private final AzureBlobContentClient contentClient;
    private final PostMediaProperties mediaProperties;

    AzureMediaVerifier(AzureBlobContentClient contentClient, PostMediaProperties mediaProperties) {
        this.contentClient = Objects.requireNonNull(contentClient, "contentClient must not be null");
        this.mediaProperties = Objects.requireNonNull(mediaProperties, "mediaProperties must not be null");
    }

    @Override
    public MediaVerificationResult verify(List<MediaToVerify> media) {
        Objects.requireNonNull(media, "media must not be null");

        Map<UUID, Integer> videoDurationsSeconds = new LinkedHashMap<>();
        for (MediaToVerify item : media) {
            MediaVerificationResult.Failure failure = verifyMedia(item, videoDurationsSeconds);
            if (failure != null) {
                return failure;
            }
        }
        return MediaVerificationResult.success(videoDurationsSeconds);
    }

    private MediaVerificationResult.Failure verifyMedia(MediaToVerify item, Map<UUID, Integer> videoDurationsSeconds) {
        if (item.mediaType() == MediaType.IMAGE) {
            // url == thumbnailUrl for IMAGE media: verify the single blob only once.
            return verifyBlob(item.id(), item.url(), Role.IMAGE, videoDurationsSeconds);
        }

        MediaVerificationResult.Failure contentFailure =
                verifyBlob(item.id(), item.url(), Role.VIDEO, videoDurationsSeconds);
        if (contentFailure != null) {
            return contentFailure;
        }
        return verifyBlob(item.id(), item.thumbnailUrl(), Role.IMAGE, videoDurationsSeconds);
    }

    private MediaVerificationResult.Failure verifyBlob(
            UUID mediaId, String blobUrl, Role role, Map<UUID, Integer> videoDurationsSeconds) {
        long size;
        try {
            size = contentClient.fetchContentLength(blobUrl);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return failure(MediaVerificationFailureReason.BLOB_NOT_FOUND, mediaId, blobUrl,
                        "Blob not found: " + blobUrl);
            }
            throw e;
        }

        if (size <= 0) {
            return failure(MediaVerificationFailureReason.EMPTY_BLOB, mediaId, blobUrl,
                    "Blob is empty: " + blobUrl);
        }

        long maxBytes = role == Role.VIDEO ? mediaProperties.getMaxVideoBytes() : mediaProperties.getMaxImageBytes();
        if (size > maxBytes) {
            return failure(MediaVerificationFailureReason.FILE_TOO_LARGE, mediaId, blobUrl,
                    "Blob size " + size + " exceeds the max of " + maxBytes + " bytes: " + blobUrl);
        }

        byte[] header = contentClient.downloadRange(blobUrl, 0, Math.min(size, HEADER_BYTES));
        DetectedFormat detected = detectFormat(header);

        if (detected == DetectedFormat.UNKNOWN) {
            return failure(MediaVerificationFailureReason.CORRUPT_FILE, mediaId, blobUrl,
                    "Could not determine the real content type of blob: " + blobUrl);
        }

        boolean detectedIsVideo = VIDEO_FORMATS.contains(detected);
        boolean expectsVideo = role == Role.VIDEO;
        if (detectedIsVideo != expectsVideo) {
            return failure(MediaVerificationFailureReason.TYPE_MISMATCH, mediaId, blobUrl,
                    "Expected a " + (expectsVideo ? "video" : "image") + " but detected " + detected
                            + " for blob: " + blobUrl);
        }

        Set<String> allowedFormats = expectsVideo
                ? mediaProperties.getAllowedVideoFormats()
                : mediaProperties.getAllowedImageFormats();
        if (!allowedFormats.contains(detected.name())) {
            return failure(MediaVerificationFailureReason.UNSUPPORTED_FORMAT, mediaId, blobUrl,
                    "Unsupported format " + detected + " for blob: " + blobUrl);
        }

        if (!expectsVideo) {
            return null;
        }
        return extractDuration(mediaId, blobUrl, videoDurationsSeconds);
    }

    private MediaVerificationResult.Failure extractDuration(
            UUID mediaId, String blobUrl, Map<UUID, Integer> videoDurationsSeconds) {
        Path tempFile = Path.of(System.getProperty("java.io.tmpdir"), "media-verifier-" + UUID.randomUUID() + ".bin");
        try {
            contentClient.downloadToFile(blobUrl, tempFile);

            int durationSeconds;
            try (IsoFile isoFile = new IsoFile(tempFile.toString())) {
                MovieBox moov = isoFile.getMovieBox();
                MovieHeaderBox movieHeaderBox = moov == null ? null : moov.getMovieHeaderBox();
                if (movieHeaderBox == null || movieHeaderBox.getTimescale() <= 0) {
                    return failure(MediaVerificationFailureReason.DURATION_UNREADABLE, mediaId, blobUrl,
                            "Could not read a movie header box from blob: " + blobUrl);
                }
                durationSeconds = (int) Math.round(
                        (double) movieHeaderBox.getDuration() / movieHeaderBox.getTimescale());
            } catch (IOException | RuntimeException e) {
                // mp4parser throws on structurally unreadable ISO BMFF content (e.g. a
                // truncated or corrupted moov box) that the magic-byte check above could not
                // have detected; this is distinct from CORRUPT_FILE, which only covers the
                // signature stage.
                return failure(MediaVerificationFailureReason.DURATION_UNREADABLE, mediaId, blobUrl,
                        "Could not parse the video container of blob: " + blobUrl);
            }

            Integer maxDurationSeconds = mediaProperties.getMaxVideoDurationSeconds();
            if (maxDurationSeconds != null && durationSeconds > maxDurationSeconds) {
                return failure(MediaVerificationFailureReason.DURATION_TOO_LONG, mediaId, blobUrl,
                        "Video duration " + durationSeconds + "s exceeds the max of " + maxDurationSeconds
                                + "s for blob: " + blobUrl);
            }

            videoDurationsSeconds.put(mediaId, durationSeconds);
            return null;
        } finally {
            deleteQuietly(tempFile);
        }
    }

    private static void deleteQuietly(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // Best-effort cleanup; never let a cleanup failure mask the real verification result.
        }
    }

    private static MediaVerificationResult.Failure failure(
            MediaVerificationFailureReason reason, UUID mediaId, String blobUrl, String message) {
        return new MediaVerificationResult.Failure(reason, mediaId, blobUrl, message);
    }

    /**
     * Detects the real content type of {@code header} (the first bytes of a blob) by its
     * magic-byte signature. Never trusts the client-set Content-Type blob property.
     */
    private static DetectedFormat detectFormat(byte[] header) {
        if (startsWith(header, 0, 0xFF, 0xD8, 0xFF)) {
            return DetectedFormat.JPEG;
        }
        if (startsWith(header, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return DetectedFormat.PNG;
        }
        if (matchesAscii(header, 0, "RIFF") && matchesAscii(header, 8, "WEBP")) {
            return DetectedFormat.WEBP;
        }
        if (matchesAscii(header, 0, "GIF8")) {
            return DetectedFormat.GIF;
        }
        if (matchesAscii(header, 4, "ftyp")) {
            String majorBrand = asciiAt(header, 8, 4);
            return majorBrand != null && majorBrand.trim().equalsIgnoreCase("qt")
                    ? DetectedFormat.MOV
                    : DetectedFormat.MP4;
        }
        return DetectedFormat.UNKNOWN;
    }

    private static boolean startsWith(byte[] data, int offset, int... expectedUnsignedBytes) {
        if (data.length < offset + expectedUnsignedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedUnsignedBytes.length; i++) {
            if ((data[offset + i] & 0xFF) != expectedUnsignedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(byte[] data, int offset, String expected) {
        String actual = asciiAt(data, offset, expected.length());
        return expected.equals(actual);
    }

    private static String asciiAt(byte[] data, int offset, int length) {
        if (data.length < offset + length) {
            return null;
        }
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private enum Role {
        IMAGE,
        VIDEO
    }

    private enum DetectedFormat {
        JPEG,
        PNG,
        WEBP,
        GIF,
        MP4,
        MOV,
        UNKNOWN
    }
}
