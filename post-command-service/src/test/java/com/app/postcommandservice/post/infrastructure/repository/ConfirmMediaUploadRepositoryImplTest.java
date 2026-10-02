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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmMediaUploadRepositoryImplTest {

    @Mock
    private PostJpaRepository postJpaRepository;

    @Mock
    private PostMediaJpaRepository postMediaJpaRepository;

    @Mock
    private PostConfirmMediaUploadView postView;

    @Mock
    private PostMediaConfirmView mediaView;

    private ConfirmMediaUploadRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new ConfirmMediaUploadRepositoryImpl(postJpaRepository, postMediaJpaRepository);
    }

    @Test
    void shouldReturnPendingMediaUploadWithOrderedMediaWhenPostExists() {
        var postId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var createdAt = Instant.now();

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
        when(postMediaJpaRepository.findConfirmMediaUploadViewsByPostId(postId)).thenReturn(List.of(mediaView));

        var result = repository.findById(postId);

        assertThat(result).isPresent();
        assertThat(result.get().postId()).isEqualTo(postId);
        assertThat(result.get().authorId()).isEqualTo(authorId);
        assertThat(result.get().status()).isEqualTo(PostStatus.PENDING);
        assertThat(result.get().createdAt()).isEqualTo(createdAt);
        assertThat(result.get().media()).hasSize(1);
        assertThat(result.get().media().getFirst().id()).isEqualTo(mediaId);
        assertThat(result.get().media().getFirst().url()).isEqualTo("https://cdn/blob.jpg");
        assertThat(result.get().media().getFirst().mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(result.get().media().getFirst().order()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyWithoutQueryingMediaWhenPostDoesNotExist() {
        var postId = UUID.randomUUID();
        when(postJpaRepository.findConfirmMediaUploadViewById(postId)).thenReturn(Optional.empty());

        var result = repository.findById(postId);

        assertThat(result).isEmpty();
        verifyNoInteractions(postMediaJpaRepository);
    }
}
