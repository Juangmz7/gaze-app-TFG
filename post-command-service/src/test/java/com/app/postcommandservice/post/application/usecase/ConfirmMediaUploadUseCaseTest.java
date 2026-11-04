package com.app.postcommandservice.post.application.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.application.commands.ConfirmMediaUploadCommand;
import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository;
import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository.MediaItem;
import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository.PendingMediaUpload;
import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.rabbitmq.PostMediaUploadedEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmMediaUploadUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();

    @Mock
    private ConfirmMediaUploadRepository confirmMediaUploadRepository;

    @Mock
    private PostMediaUploadedEventPublisher postMediaUploadedEventPublisher;

    private PostMediaProperties postMediaProperties;

    private ConfirmMediaUploadUseCase confirmMediaUploadUseCase;

    @Captor
    private ArgumentCaptor<PostMediaUploadedEvent> eventCaptor;

    private void setUp(Duration uploadWindow) {
        postMediaProperties = new PostMediaProperties();
        postMediaProperties.setUploadWindow(uploadWindow);
        confirmMediaUploadUseCase = new ConfirmMediaUploadUseCase(
                confirmMediaUploadRepository,
                postMediaProperties,
                postMediaUploadedEventPublisher
        );
    }

    @Test
    void shouldPublishPostMediaUploadedEventWithAllMediaFieldsWhenPostIsPending() {
        setUp(Duration.ofHours(48));
        var mediaId = UUID.randomUUID();
        var pendingUpload = new PendingMediaUpload(
                POST_ID,
                AUTHOR_ID,
                PostStatus.PENDING,
                Instant.now().minus(Duration.ofHours(1)),
                List.of(new MediaItem(mediaId, "https://cdn/blob.jpg", "https://cdn/blob.jpg", MediaType.IMAGE, 1))
        );
        when(confirmMediaUploadRepository.findById(POST_ID)).thenReturn(Optional.of(pendingUpload));

        confirmMediaUploadUseCase.confirm(new ConfirmMediaUploadCommand(POST_ID, AUTHOR_ID));

        verify(postMediaUploadedEventPublisher).publish(eventCaptor.capture());
        var publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.postId()).isEqualTo(POST_ID);
        assertThat(publishedEvent.media()).hasSize(1);
        assertThat(publishedEvent.media().getFirst().id()).isEqualTo(mediaId);
        assertThat(publishedEvent.media().getFirst().url()).isEqualTo("https://cdn/blob.jpg");
        assertThat(publishedEvent.media().getFirst().thumbnailUrl()).isEqualTo("https://cdn/blob.jpg");
        assertThat(publishedEvent.media().getFirst().mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(publishedEvent.media().getFirst().order()).isEqualTo(1);
    }

    @Test
    void shouldThrowNotFoundWhenPostDoesNotExist() {
        setUp(Duration.ofHours(48));
        when(confirmMediaUploadRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> confirmMediaUploadUseCase.confirm(new ConfirmMediaUploadCommand(POST_ID, AUTHOR_ID)))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
        verify(postMediaUploadedEventPublisher, never()).publish(eventCaptor.capture());
    }

    @Test
    void shouldThrowForbiddenWhenRequesterIsNotTheAuthor() {
        setUp(Duration.ofHours(48));
        var otherUserId = UUID.randomUUID();
        var pendingUpload = new PendingMediaUpload(
                POST_ID,
                AUTHOR_ID,
                PostStatus.PENDING,
                Instant.now(),
                List.of(new MediaItem(UUID.randomUUID(), "https://cdn/blob.jpg", "https://cdn/blob.jpg",
                        MediaType.IMAGE, 1))
        );
        when(confirmMediaUploadRepository.findById(POST_ID)).thenReturn(Optional.of(pendingUpload));

        assertThatThrownBy(
                () -> confirmMediaUploadUseCase.confirm(new ConfirmMediaUploadCommand(POST_ID, otherUserId)))
                .isInstanceOf(PostOwnershipException.class);
        verify(postMediaUploadedEventPublisher, never()).publish(eventCaptor.capture());
    }

    @Test
    void shouldThrowConflictWhenPostIsNotPending() {
        setUp(Duration.ofHours(48));
        var pendingUpload = new PendingMediaUpload(
                POST_ID,
                AUTHOR_ID,
                PostStatus.ACCEPTED,
                Instant.now(),
                List.of(new MediaItem(UUID.randomUUID(), "https://cdn/blob.jpg", "https://cdn/blob.jpg",
                        MediaType.IMAGE, 1))
        );
        when(confirmMediaUploadRepository.findById(POST_ID)).thenReturn(Optional.of(pendingUpload));

        assertThatThrownBy(() -> confirmMediaUploadUseCase.confirm(new ConfirmMediaUploadCommand(POST_ID, AUTHOR_ID)))
                .isInstanceOf(PostNotPendingException.class);
        verify(postMediaUploadedEventPublisher, never()).publish(eventCaptor.capture());
    }

    @Test
    void shouldThrowConflictWhenTheUploadWindowHasExpired() {
        setUp(Duration.ofHours(48));
        var pendingUpload = new PendingMediaUpload(
                POST_ID,
                AUTHOR_ID,
                PostStatus.PENDING,
                Instant.now().minus(Duration.ofHours(49)),
                List.of(new MediaItem(UUID.randomUUID(), "https://cdn/blob.jpg", "https://cdn/blob.jpg",
                        MediaType.IMAGE, 1))
        );
        when(confirmMediaUploadRepository.findById(POST_ID)).thenReturn(Optional.of(pendingUpload));

        assertThatThrownBy(() -> confirmMediaUploadUseCase.confirm(new ConfirmMediaUploadCommand(POST_ID, AUTHOR_ID)))
                .isInstanceOf(MediaUploadWindowExpiredException.class);
        verify(postMediaUploadedEventPublisher, never()).publish(eventCaptor.capture());
    }
}
