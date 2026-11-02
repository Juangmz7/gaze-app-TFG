package com.app.postcommandservice.post.infrastructure.azure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.app.postcommandservice.post.application.port.MediaToVerify;
import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
import com.app.postcommandservice.post.application.port.MediaVerificationResult;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureMediaVerifierTest {

    private static final String IMAGE_URL = "https://myaccount.blob.core.windows.net/post-media/image-blob";
    private static final String VIDEO_URL = "https://myaccount.blob.core.windows.net/post-media/video-blob";
    private static final String THUMBNAIL_URL = "https://myaccount.blob.core.windows.net/post-media/thumb-blob";
    private static final String ETAG = "\"0x8DCAFEBABE\"";

    @Mock
    private AzureBlobContentClient contentClient;

    private PostMediaProperties mediaProperties;
    private AzureMediaVerifier verifier;

    @BeforeEach
    void setUp() {
        mediaProperties = new PostMediaProperties();
        verifier = new AzureMediaVerifier(contentClient, mediaProperties);
    }

    @Test
    void shouldReturnSuccessAndExtractedDurationsWhenAllMediaAreValid() {
        UUID imageId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(1024L));
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.jpeg(64));

        when(contentClient.fetchProperties(VIDEO_URL)).thenReturn(snapshot(2048L));
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(10, 600);
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(2);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), eq(ETAG), any(Path.class));

        when(contentClient.fetchProperties(THUMBNAIL_URL)).thenReturn(snapshot(512L));
        when(contentClient.downloadRange(eq(THUMBNAIL_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.png(64));

        List<MediaToVerify> media = List.of(
                new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE),
                new MediaToVerify(videoId, VIDEO_URL, THUMBNAIL_URL, MediaType.VIDEO));

        MediaVerificationResult result = verifier.verify(media);

        assertThat(result).isInstanceOf(MediaVerificationResult.Success.class);
        MediaVerificationResult.Success success = (MediaVerificationResult.Success) result;
        assertThat(success.videoDurationsSeconds()).containsEntry(videoId, 10);
        assertThat(success.videoDurationsSeconds()).doesNotContainKey(imageId);
    }

    @Test
    void shouldReturnFailureWhenBlobDoesNotExist() {
        UUID imageId = UUID.randomUUID();
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(404);
        BlobStorageException notFound = new BlobStorageException("not found", response, null);
        when(contentClient.fetchProperties(IMAGE_URL)).thenThrow(notFound);

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        MediaVerificationResult.Failure failure = (MediaVerificationResult.Failure) result;
        assertThat(failure.reason()).isEqualTo(MediaVerificationFailureReason.BLOB_NOT_FOUND);
        assertThat(failure.mediaId()).isEqualTo(imageId);
        assertThat(failure.mediaUrl()).isEqualTo(IMAGE_URL);
    }

    @Test
    void shouldReturnFailureWhenBlobIsEmpty() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(0L));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.EMPTY_BLOB);
    }

    @Test
    void shouldReturnFailureWhenBlobIsTooLarge() {
        UUID imageId = UUID.randomUUID();
        mediaProperties.setMaxImageBytes(100);
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(101L));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.FILE_TOO_LARGE);
    }

    @Test
    void shouldReturnFailureWhenVideoThumbnailIsNotAnImage() {
        UUID videoId = UUID.randomUUID();

        when(contentClient.fetchProperties(VIDEO_URL)).thenReturn(snapshot(2048L));
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(5, 600);
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(2);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), eq(ETAG), any(Path.class));

        when(contentClient.fetchProperties(THUMBNAIL_URL)).thenReturn(snapshot(2048L));
        when(contentClient.downloadRange(eq(THUMBNAIL_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(videoId, VIDEO_URL, THUMBNAIL_URL, MediaType.VIDEO)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        MediaVerificationResult.Failure failure = (MediaVerificationResult.Failure) result;
        assertThat(failure.reason()).isEqualTo(MediaVerificationFailureReason.TYPE_MISMATCH);
        assertThat(failure.mediaUrl()).isEqualTo(THUMBNAIL_URL);
    }

    @Test
    void shouldReturnFailureWhenMediaContentDoesNotMatchMediaType() {
        UUID imageId = UUID.randomUUID();
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(5, 600);

        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(2048L));
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.TYPE_MISMATCH);
    }

    @Test
    void shouldReturnFailureForUnsupportedFormats() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(64L));
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.gif(64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.UNSUPPORTED_FORMAT);
    }

    @Test
    void shouldReturnFailureForCorruptFile() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(64L));
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.garbage(64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.CORRUPT_FILE);
    }

    @Test
    void shouldVerifyImageBlobOnlyOnce() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(1024L));
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.jpeg(64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Success.class);
        verify(contentClient, times(1)).fetchProperties(IMAGE_URL);
        verify(contentClient, never()).downloadToFile(any(), any(), any());
    }

    @Test
    void shouldThrowRetryableExceptionWhenAzureApiFails() {
        UUID imageId = UUID.randomUUID();
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(503);
        BlobStorageException serviceUnavailable = new BlobStorageException("service unavailable", response, null);
        when(contentClient.fetchProperties(IMAGE_URL)).thenThrow(serviceUnavailable);

        assertThatThrownBy(() -> verifier.verify(
                        List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE))))
                .isInstanceOf(BlobStorageException.class);
    }

    @Test
    void shouldReturnDurationTooLongWhenConfiguredLimitIsExceeded() {
        UUID videoId = UUID.randomUUID();
        mediaProperties.setMaxVideoDurationSeconds(5);

        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(10, 600);
        when(contentClient.fetchProperties(VIDEO_URL)).thenReturn(snapshot(2048L));
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(2);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), eq(ETAG), any(Path.class));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(videoId, VIDEO_URL, THUMBNAIL_URL, MediaType.VIDEO)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.DURATION_TOO_LONG);
    }

    @Test
    void shouldReturnBlobModifiedWhenEtagNoLongerMatchesOnHeaderDownload() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(1024L));
        BlobStorageException failure = storageException(412);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong())).thenThrow(failure);

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.BLOB_MODIFIED);
    }

    @Test
    void shouldReturnBlobNotFoundWhenBlobDisappearsBeforeHeaderDownload() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(1024L));
        BlobStorageException failure = storageException(404);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong())).thenThrow(failure);

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.BLOB_NOT_FOUND);
    }

    @Test
    void shouldReturnBlobModifiedWhenVideoChangesBeforeFullDownload() {
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(5, 600);
        stubVideoHeader(mp4Bytes);

        BlobStorageException failure = storageException(412);
        doThrow(failure).when(contentClient).downloadToFile(eq(VIDEO_URL), eq(ETAG), any(Path.class));

        assertThat(failureReason(verifyVideo())).isEqualTo(MediaVerificationFailureReason.BLOB_MODIFIED);
    }

    @Test
    void shouldReturnBlobNotFoundWhenVideoDisappearsBeforeFullDownload() {
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(5, 600);
        stubVideoHeader(mp4Bytes);

        BlobStorageException failure = storageException(404);
        doThrow(failure).when(contentClient).downloadToFile(eq(VIDEO_URL), eq(ETAG), any(Path.class));

        assertThat(failureReason(verifyVideo())).isEqualTo(MediaVerificationFailureReason.BLOB_NOT_FOUND);
    }

    @Test
    void shouldRethrowOtherAzureErrorsFromDownloads() {
        UUID imageId = UUID.randomUUID();
        when(contentClient.fetchProperties(IMAGE_URL)).thenReturn(snapshot(1024L));
        BlobStorageException failure = storageException(503);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(ETAG), eq(0L), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> verifier.verify(
                        List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE))))
                .isInstanceOf(BlobStorageException.class);
    }

    @Test
    void shouldRejectFtypBlobsWithAnUnsupportedMajorBrand() {
        UUID videoId = UUID.randomUUID();
        byte[] heic = MediaVerifierFixtures.mp4WithMajorBrand("heic");
        when(contentClient.fetchProperties(VIDEO_URL)).thenReturn(snapshot(2048L));
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(heic, 64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(videoId, VIDEO_URL, THUMBNAIL_URL, MediaType.VIDEO)));

        assertThat(failureReason(result)).isEqualTo(MediaVerificationFailureReason.CORRUPT_FILE);
        verify(contentClient, never()).downloadToFile(any(), any(), any());
    }

    @Test
    void shouldTreatVersionZeroDurationAsUnsigned() {
        // 0x80000000 ticks would be negative if read as a signed 32-bit int.
        byte[] mp4Bytes = MediaVerifierFixtures.mp4WithRawDuration(0, 0x80000000L, 1000);
        stubVideoHeader(mp4Bytes);
        stubVideoDownload(mp4Bytes);
        stubValidThumbnail();

        MediaVerificationResult result = verifyVideo();

        assertThat(result).isInstanceOf(MediaVerificationResult.Success.class);
        assertThat(((MediaVerificationResult.Success) result).videoDurationsSeconds())
                .containsValue(2_147_484);
    }

    @Test
    void shouldReturnDurationUnreadableForVersionZeroUnknownDurationSentinel() {
        byte[] mp4Bytes = MediaVerifierFixtures.mp4WithRawDuration(0, 0xFFFFFFFFL, 600);
        stubVideoHeader(mp4Bytes);
        stubVideoDownload(mp4Bytes);

        assertThat(failureReason(verifyVideo())).isEqualTo(MediaVerificationFailureReason.DURATION_UNREADABLE);
    }

    @Test
    void shouldReturnDurationUnreadableForNegativeVersionOneDuration() {
        byte[] mp4Bytes = MediaVerifierFixtures.mp4WithRawDuration(1, -5L, 600);
        stubVideoHeader(mp4Bytes);
        stubVideoDownload(mp4Bytes);

        assertThat(failureReason(verifyVideo())).isEqualTo(MediaVerificationFailureReason.DURATION_UNREADABLE);
    }

    @Test
    void shouldReturnDurationUnreadableWhenDurationDoesNotFitInAnInt() {
        byte[] mp4Bytes = MediaVerifierFixtures.mp4WithRawDuration(1, 1L << 40, 1);
        stubVideoHeader(mp4Bytes);
        stubVideoDownload(mp4Bytes);

        assertThat(failureReason(verifyVideo())).isEqualTo(MediaVerificationFailureReason.DURATION_UNREADABLE);
    }

    private void stubVideoHeader(byte[] mp4Bytes) {
        when(contentClient.fetchProperties(VIDEO_URL)).thenReturn(snapshot(mp4Bytes.length));
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
    }

    private void stubVideoDownload(byte[] mp4Bytes) {
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(2);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), eq(ETAG), any(Path.class));
    }

    private void stubValidThumbnail() {
        when(contentClient.fetchProperties(THUMBNAIL_URL)).thenReturn(snapshot(512L));
        when(contentClient.downloadRange(eq(THUMBNAIL_URL), eq(ETAG), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.png(64));
    }

    private MediaVerificationResult verifyVideo() {
        return verifier.verify(
                List.of(new MediaToVerify(UUID.randomUUID(), VIDEO_URL, THUMBNAIL_URL, MediaType.VIDEO)));
    }

    private static MediaVerificationFailureReason failureReason(MediaVerificationResult result) {
        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        return ((MediaVerificationResult.Failure) result).reason();
    }

    private static BlobStorageException storageException(int statusCode) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(statusCode);
        return new BlobStorageException("status " + statusCode, response, null);
    }

    private static AzureBlobContentClient.BlobSnapshot snapshot(long contentLength) {
        return new AzureBlobContentClient.BlobSnapshot(contentLength, ETAG);
    }

    private static byte[] firstBytes(byte[] source, int count) {
        int length = Math.min(count, source.length);
        byte[] result = new byte[length];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }
}
