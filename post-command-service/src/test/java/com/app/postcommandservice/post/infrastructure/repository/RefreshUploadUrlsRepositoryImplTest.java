package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshUploadUrlsRepositoryImplTest {

    @Mock
    private PostJpaRepository postJpaRepository;

    @Mock
    private PostMediaJpaRepository postMediaJpaRepository;

    @Mock
    private PostConfirmMediaUploadView postView;

    @Mock
    private PostMediaRefreshUploadUrlsView mediaView;

    private RefreshUploadUrlsRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new RefreshUploadUrlsRepositoryImpl(postJpaRepository, postMediaJpaRepository);
    }

    @Test
    void shouldReturnRefreshableUploadWithOrderedMediaAndSasStateWhenPostExists() {
        var postId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var createdAt = Instant.now();
        var uploadExpiresAt = Instant.now().plus(java.time.Duration.ofMinutes(10));

        when(postJpaRepository.findConfirmMediaUploadViewById(postId)).thenReturn(Optional.of(postView));
        when(postView.getId()).thenReturn(postId);
        when(postView.getAuthorId()).thenReturn(authorId);
        when(postView.getStatus()).thenReturn(PostStatus.PENDING);
        when(postView.getCreatedAt()).thenReturn(createdAt);

        when(mediaView.getId()).thenReturn(mediaId);
        when(mediaView.getUrl()).thenReturn("https://cdn/blob.jpg");
        when(mediaView.getThumbnailUrl()).thenReturn("https://cdn/blob.jpg");
        when(mediaView.getMediaType()).thenReturn(MediaType.IMAGE);
        when(mediaView.getMediaOrder()).thenReturn(1);
        when(mediaView.getUploadSasHash()).thenReturn("hash");
        when(mediaView.getUploadSasExpiresAt()).thenReturn(uploadExpiresAt);
        when(mediaView.getThumbnailSasHash()).thenReturn(null);
        when(mediaView.getThumbnailSasExpiresAt()).thenReturn(null);
        when(postMediaJpaRepository.findRefreshUploadUrlsViewsByPostId(postId)).thenReturn(List.of(mediaView));

        var result = repository.findById(postId);

        assertThat(result).isPresent();
        assertThat(result.get().postId()).isEqualTo(postId);
        assertThat(result.get().authorId()).isEqualTo(authorId);
        assertThat(result.get().status()).isEqualTo(PostStatus.PENDING);
        assertThat(result.get().createdAt()).isEqualTo(createdAt);
        assertThat(result.get().media()).hasSize(1);
        var media = result.get().media().getFirst();
        assertThat(media.id()).isEqualTo(mediaId);
        assertThat(media.url()).isEqualTo("https://cdn/blob.jpg");
        assertThat(media.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(media.order()).isEqualTo(1);
        assertThat(media.uploadSasHash()).isEqualTo("hash");
        assertThat(media.uploadSasExpiresAt()).isEqualTo(uploadExpiresAt);
        assertThat(media.thumbnailSasHash()).isNull();
    }

    @Test
    void shouldReturnEmptyWithoutQueryingMediaWhenPostDoesNotExist() {
        var postId = UUID.randomUUID();
        when(postJpaRepository.findConfirmMediaUploadViewById(postId)).thenReturn(Optional.empty());

        var result = repository.findById(postId);

        assertThat(result).isEmpty();
        verifyNoInteractions(postMediaJpaRepository);
    }

    @Test
    void shouldDelegateUploadSasUpdateToJpaRepository() {
        var mediaId = UUID.randomUUID();
        var expiresAt = Instant.now();

        repository.updateUploadSas(mediaId, "hash", expiresAt);

        verify(postMediaJpaRepository).updateUploadSas(mediaId, "hash", expiresAt);
    }

    @Test
    void shouldDelegateThumbnailSasUpdateToJpaRepository() {
        var mediaId = UUID.randomUUID();
        var expiresAt = Instant.now();

        repository.updateThumbnailSas(mediaId, "hash", expiresAt);

        verify(postMediaJpaRepository).updateThumbnailSas(mediaId, "hash", expiresAt);
    }
}
