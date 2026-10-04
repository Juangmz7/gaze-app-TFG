package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.Map;
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
class PostMediaVerificationRepositoryImplTest {

    @Mock
    private PostJpaRepository postJpaRepository;

    @Mock
    private PostMediaJpaRepository postMediaJpaRepository;

    @Mock
    private PostMediaConfirmView mediaView;

    private PostMediaVerificationRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new PostMediaVerificationRepositoryImpl(postJpaRepository, postMediaJpaRepository);
    }

    @Test
    void shouldReturnSnapshotWithStatusAndMediaWhenPostExists() {
        var postId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();

        when(postJpaRepository.findStatusById(postId)).thenReturn(Optional.of(PostStatus.PENDING));
        when(mediaView.getId()).thenReturn(mediaId);
        when(mediaView.getMediaType()).thenReturn(MediaType.VIDEO);
        when(postMediaJpaRepository.findConfirmMediaUploadViewsByPostId(postId)).thenReturn(List.of(mediaView));

        var snapshot = repository.findSnapshot(postId);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().postId()).isEqualTo(postId);
        assertThat(snapshot.get().status()).isEqualTo(PostStatus.PENDING);
        assertThat(snapshot.get().media()).hasSize(1);
        assertThat(snapshot.get().media().getFirst().id()).isEqualTo(mediaId);
        assertThat(snapshot.get().media().getFirst().mediaType()).isEqualTo(MediaType.VIDEO);
    }

    @Test
    void shouldReturnEmptyWithoutQueryingMediaWhenPostDoesNotExist() {
        var postId = UUID.randomUUID();
        when(postJpaRepository.findStatusById(postId)).thenReturn(Optional.empty());

        var snapshot = repository.findSnapshot(postId);

        assertThat(snapshot).isEmpty();
        verifyNoInteractions(postMediaJpaRepository);
    }

    @Test
    void shouldDelegateConditionalStatusUpdateToJpaRepository() {
        var postId = UUID.randomUUID();
        when(postJpaRepository.updateStatusIfCurrent(postId, PostStatus.PENDING, PostStatus.ACCEPTED)).thenReturn(1);

        var updatedRows = repository.updateStatusIfPending(postId, PostStatus.ACCEPTED);

        assertThat(updatedRows).isEqualTo(1);
        verify(postJpaRepository).updateStatusIfCurrent(postId, PostStatus.PENDING, PostStatus.ACCEPTED);
    }

    @Test
    void shouldPersistEachVideoDurationIndividually() {
        var firstMediaId = UUID.randomUUID();
        var secondMediaId = UUID.randomUUID();

        repository.persistVideoDurationsMillis(Map.of(firstMediaId, 7000, secondMediaId, 12000));

        verify(postMediaJpaRepository).updateDuration(firstMediaId, 7000);
        verify(postMediaJpaRepository).updateDuration(secondMediaId, 12000);
    }
}
