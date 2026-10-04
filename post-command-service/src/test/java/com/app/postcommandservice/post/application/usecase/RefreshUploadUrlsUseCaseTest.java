package com.app.postcommandservice.post.application.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.app.postcommandservice.post.application.commands.RefreshUploadUrlsCommand;
import com.app.postcommandservice.post.application.commands.RefreshUploadUrlsCommand.ClientMediaUpload;
import com.app.postcommandservice.post.application.port.MediaUploadUrlSigner;
import com.app.postcommandservice.post.application.port.SignedUploadUrl;
import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository;
import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository.MediaUploadState;
import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository.RefreshableUpload;
import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshUploadUrlsUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final String STORED_HASH = "stored-hash";

    @Mock
    private RefreshUploadUrlsRepository refreshUploadUrlsRepository;

    @Mock
    private MediaUploadUrlSigner mediaUploadUrlSigner;

    @Mock
    private PasswordEncoder passwordEncoder;

    private PostMediaProperties postMediaProperties;

    /**
     * {@code self} is the Spring AOP proxy of this same bean in production, so {@code
     * refresh()}'s calls to {@code self.findUpload(...)}/{@code self.persistSasUpdates(...)}
     * run inside their own transactions. No Spring context here, so {@code self} is wired to
     * the very same instance via reflection, matching {@code PostMediaVerificationServiceTest}.
     */
    private RefreshUploadUrlsUseCase useCase(Duration uploadWindow) {
        postMediaProperties = new PostMediaProperties();
        postMediaProperties.setUploadWindow(uploadWindow);

        RefreshUploadUrlsUseCase instance = new RefreshUploadUrlsUseCase(
                refreshUploadUrlsRepository,
                postMediaProperties,
                mediaUploadUrlSigner,
                passwordEncoder,
                null);
        try {
            var selfField = RefreshUploadUrlsUseCase.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(instance, instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return instance;
    }

    private MediaUploadState imageMedia(UUID mediaId, String hash, Instant expiresAt) {
        return new MediaUploadState(
                mediaId, "https://cdn/blob.jpg", "https://cdn/blob.jpg", MediaType.IMAGE, 1,
                hash, expiresAt, null, null);
    }

    private MediaUploadState videoMedia(
            UUID mediaId, String uploadHash, Instant uploadExpiresAt, String thumbHash, Instant thumbExpiresAt) {
        return new MediaUploadState(
                mediaId, "https://cdn/video.mp4", "https://cdn/thumb.jpg", MediaType.VIDEO, 1,
                uploadHash, uploadExpiresAt, thumbHash, thumbExpiresAt);
    }

    @Test
    void shouldReturnFreshSasUrlsGivenAnExpiredSasAndPendingPostWithinUploadWindow() {
        var useCase = useCase(Duration.ofHours(48));
        var mediaId = UUID.randomUUID();
        var upload = new RefreshableUpload(
                POST_ID, AUTHOR_ID, PostStatus.PENDING, Instant.now().minus(Duration.ofHours(1)),
                List.of(imageMedia(mediaId, STORED_HASH, Instant.now().minus(Duration.ofMinutes(5)))));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.of(upload));
        var freshExpiry = Instant.now().plus(Duration.ofMinutes(15));
        when(mediaUploadUrlSigner.sign(eq("https://cdn/blob.jpg"), any()))
                .thenReturn(new SignedUploadUrl("https://cdn/blob.jpg?fresh-sas", freshExpiry));
        when(passwordEncoder.encode("https://cdn/blob.jpg?fresh-sas")).thenReturn("new-hash");

        var response = useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, AUTHOR_ID,
                List.of(new ClientMediaUpload(mediaId, "https://cdn/blob.jpg?expired-sas", null))));

        assertThat(response.postId()).isEqualTo(POST_ID);
        assertThat(response.media()).hasSize(1);
        assertThat(response.media().getFirst().uploadUrl()).isEqualTo("https://cdn/blob.jpg?fresh-sas");
        assertThat(response.media().getFirst().thumbnailUploadUrl()).isNull();
        assertThat(response.uploadExpiresAt()).isEqualTo(freshExpiry);
        verify(refreshUploadUrlsRepository).updateUploadSas(mediaId, "new-hash", freshExpiry);
    }

    @Test
    void shouldReturnTheSameSasUrlGivenAStillValidSas() {
        var useCase = useCase(Duration.ofHours(48));
        var mediaId = UUID.randomUUID();
        var storedExpiry = Instant.now().plus(Duration.ofMinutes(20));
        var upload = new RefreshableUpload(
                POST_ID, AUTHOR_ID, PostStatus.PENDING, Instant.now().minus(Duration.ofHours(1)),
                List.of(imageMedia(mediaId, STORED_HASH, storedExpiry)));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.of(upload));
        when(passwordEncoder.matches("https://cdn/blob.jpg?still-valid-sas", STORED_HASH)).thenReturn(true);

        var response = useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, AUTHOR_ID,
                List.of(new ClientMediaUpload(mediaId, "https://cdn/blob.jpg?still-valid-sas", null))));

        assertThat(response.media().getFirst().uploadUrl()).isEqualTo("https://cdn/blob.jpg?still-valid-sas");
        assertThat(response.uploadExpiresAt()).isEqualTo(storedExpiry);
        verify(mediaUploadUrlSigner, never()).sign(any(), any());
        verify(refreshUploadUrlsRepository, never()).updateUploadSas(any(), any(), any());
    }

    @Test
    void shouldIncludeThumbnailUploadUrlOnlyForVideoMedia() {
        var useCase = useCase(Duration.ofHours(48));
        var imageId = UUID.randomUUID();
        var videoId = UUID.randomUUID();
        var upload = new RefreshableUpload(
                POST_ID, AUTHOR_ID, PostStatus.PENDING, Instant.now().minus(Duration.ofHours(1)),
                List.of(
                        imageMedia(imageId, null, null),
                        videoMedia(videoId, null, null, null, null)));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.of(upload));
        when(mediaUploadUrlSigner.sign(eq("https://cdn/blob.jpg"), any()))
                .thenReturn(new SignedUploadUrl("https://cdn/blob.jpg?sas1", Instant.now().plus(Duration.ofMinutes(10))));
        when(mediaUploadUrlSigner.sign(eq("https://cdn/video.mp4"), any()))
                .thenReturn(new SignedUploadUrl("https://cdn/video.mp4?sas2", Instant.now().plus(Duration.ofMinutes(10))));
        when(mediaUploadUrlSigner.sign(eq("https://cdn/thumb.jpg"), any()))
                .thenReturn(new SignedUploadUrl("https://cdn/thumb.jpg?sas3", Instant.now().plus(Duration.ofMinutes(10))));
        when(passwordEncoder.encode(any())).thenReturn("hash");

        var response = useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, AUTHOR_ID, List.of()));

        assertThat(response.media()).hasSize(2);
        var imageResponse = response.media().stream().filter(m -> m.id().equals(imageId)).findFirst().orElseThrow();
        var videoResponse = response.media().stream().filter(m -> m.id().equals(videoId)).findFirst().orElseThrow();
        assertThat(imageResponse.thumbnailUploadUrl()).isNull();
        assertThat(videoResponse.uploadUrl()).isEqualTo("https://cdn/video.mp4?sas2");
        assertThat(videoResponse.thumbnailUploadUrl()).isEqualTo("https://cdn/thumb.jpg?sas3");
        verify(refreshUploadUrlsRepository).updateThumbnailSas(eq(videoId), eq("hash"), any());
    }

    @Test
    void shouldThrowNotFoundWhenPostDoesNotExist() {
        var useCase = useCase(Duration.ofHours(48));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, AUTHOR_ID, List.of())))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
        verify(mediaUploadUrlSigner, never()).sign(any(), any());
    }

    @Test
    void shouldThrowForbiddenWhenRequesterIsNotTheAuthor() {
        var useCase = useCase(Duration.ofHours(48));
        var otherUserId = UUID.randomUUID();
        var upload = new RefreshableUpload(
                POST_ID, AUTHOR_ID, PostStatus.PENDING, Instant.now(),
                List.of(imageMedia(UUID.randomUUID(), null, null)));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, otherUserId, List.of())))
                .isInstanceOf(PostOwnershipException.class);
        verify(mediaUploadUrlSigner, never()).sign(any(), any());
    }

    @Test
    void shouldThrowConflictWhenPostIsNotPending() {
        var useCase = useCase(Duration.ofHours(48));
        var upload = new RefreshableUpload(
                POST_ID, AUTHOR_ID, PostStatus.ACCEPTED, Instant.now(),
                List.of(imageMedia(UUID.randomUUID(), null, null)));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, AUTHOR_ID, List.of())))
                .isInstanceOf(PostNotPendingException.class);
        verify(mediaUploadUrlSigner, never()).sign(any(), any());
    }

    @Test
    void shouldThrowConflictWhenTheUploadWindowHasExpired() {
        var useCase = useCase(Duration.ofHours(48));
        var upload = new RefreshableUpload(
                POST_ID, AUTHOR_ID, PostStatus.PENDING, Instant.now().minus(Duration.ofHours(49)),
                List.of(imageMedia(UUID.randomUUID(), null, null)));
        when(refreshUploadUrlsRepository.findById(POST_ID)).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> useCase.refresh(new RefreshUploadUrlsCommand(POST_ID, AUTHOR_ID, List.of())))
                .isInstanceOf(MediaUploadWindowExpiredException.class);
        verify(mediaUploadUrlSigner, never()).sign(any(), any());
    }
}
