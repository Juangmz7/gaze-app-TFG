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

        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(1024L);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.jpeg(64));

        when(contentClient.fetchContentLength(VIDEO_URL)).thenReturn(2048L);
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(10, 600);
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(1);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), any(Path.class));

        when(contentClient.fetchContentLength(THUMBNAIL_URL)).thenReturn(512L);
        when(contentClient.downloadRange(eq(THUMBNAIL_URL), eq(0L), anyLong()))
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
        when(contentClient.fetchContentLength(IMAGE_URL)).thenThrow(notFound);

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
        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(0L);

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
        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(101L);

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.FILE_TOO_LARGE);
    }

    @Test
    void shouldReturnFailureWhenVideoThumbnailIsNotAnImage() {
        UUID videoId = UUID.randomUUID();

        when(contentClient.fetchContentLength(VIDEO_URL)).thenReturn(2048L);
        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(5, 600);
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(1);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), any(Path.class));

        when(contentClient.fetchContentLength(THUMBNAIL_URL)).thenReturn(2048L);
        when(contentClient.downloadRange(eq(THUMBNAIL_URL), eq(0L), anyLong()))
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

        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(2048L);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(0L), anyLong()))
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
        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(64L);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(0L), anyLong()))
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
        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(64L);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(0L), anyLong()))
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
        when(contentClient.fetchContentLength(IMAGE_URL)).thenReturn(1024L);
        when(contentClient.downloadRange(eq(IMAGE_URL), eq(0L), anyLong()))
                .thenReturn(MediaVerifierFixtures.jpeg(64));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Success.class);
        verify(contentClient, times(1)).fetchContentLength(IMAGE_URL);
        verify(contentClient, never()).downloadToFile(any(), any());
    }

    @Test
    void shouldThrowRetryableExceptionWhenAzureApiFails() {
        UUID imageId = UUID.randomUUID();
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(503);
        BlobStorageException serviceUnavailable = new BlobStorageException("service unavailable", response, null);
        when(contentClient.fetchContentLength(IMAGE_URL)).thenThrow(serviceUnavailable);

        assertThatThrownBy(() -> verifier.verify(
                        List.of(new MediaToVerify(imageId, IMAGE_URL, IMAGE_URL, MediaType.IMAGE))))
                .isInstanceOf(BlobStorageException.class);
    }

    @Test
    void shouldReturnDurationTooLongWhenConfiguredLimitIsExceeded() {
        UUID videoId = UUID.randomUUID();
        mediaProperties.setMaxVideoDurationSeconds(5);

        byte[] mp4Bytes = MediaVerifierFixtures.validMp4(10, 600);
        when(contentClient.fetchContentLength(VIDEO_URL)).thenReturn(2048L);
        when(contentClient.downloadRange(eq(VIDEO_URL), eq(0L), anyLong()))
                .thenReturn(firstBytes(mp4Bytes, 64));
        doAnswer(invocation -> {
            Path destination = invocation.getArgument(1);
            Files.write(destination, mp4Bytes);
            return null;
        }).when(contentClient).downloadToFile(eq(VIDEO_URL), any(Path.class));

        MediaVerificationResult result = verifier.verify(
                List.of(new MediaToVerify(videoId, VIDEO_URL, THUMBNAIL_URL, MediaType.VIDEO)));

        assertThat(result).isInstanceOf(MediaVerificationResult.Failure.class);
        assertThat(((MediaVerificationResult.Failure) result).reason())
                .isEqualTo(MediaVerificationFailureReason.DURATION_TOO_LONG);
    }

    private static byte[] firstBytes(byte[] source, int count) {
        int length = Math.min(count, source.length);
        byte[] result = new byte[length];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }
}
