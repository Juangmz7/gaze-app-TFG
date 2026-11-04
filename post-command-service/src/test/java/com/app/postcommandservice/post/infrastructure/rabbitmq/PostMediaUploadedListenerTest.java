package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.application.usecase.PostMediaVerificationService;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PostMediaUploadedListenerTest {

    @Mock
    private PostMediaVerificationService postMediaVerificationService;

    @Captor
    private ArgumentCaptor<PostMediaUploadedEvent> eventCaptor;

    private PostMediaUploadedListener listener;

    private PostMediaUploadedMediaPayload validMedia(UUID id) {
        return PostMediaUploadedMediaPayload.builder()
                .id(id)
                .url("https://cdn/blob.jpg")
                .thumbnailUrl("https://cdn/blob.jpg")
                .mediaType(MediaType.IMAGE)
                .order(1)
                .build();
    }

    private PostMediaUploadedEvent validEvent() {
        return PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .media(List.of(validMedia(UUID.randomUUID())))
                .build();
    }

    private void setUp() {
        listener = new PostMediaUploadedListener(postMediaVerificationService);
    }

    @Test
    void shouldDelegateValidEventToVerificationService() {
        setUp();
        var event = validEvent();

        listener.onPostMediaUploaded(event);

        verify(postMediaVerificationService).process(eventCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue()).isEqualTo(event);
    }

    @Test
    void shouldDiscardNullEventWithoutRetrying() {
        setUp();
        assertThatThrownBy(() -> listener.onPostMediaUploaded(null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(postMediaVerificationService);
    }

    @Test
    void shouldDiscardEventWithMissingPostIdWithoutRetrying() {
        setUp();
        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(null)
                .media(List.of(validMedia(UUID.randomUUID())))
                .build();

        assertThatThrownBy(() -> listener.onPostMediaUploaded(event))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(postMediaVerificationService);
    }

    @Test
    void shouldDiscardEventWithEmptyMediaWithoutRetrying() {
        setUp();
        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .media(List.of())
                .build();

        assertThatThrownBy(() -> listener.onPostMediaUploaded(event))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(postMediaVerificationService);
    }

    @Test
    void shouldDiscardEventWithIncompleteMediaItemWithoutRetrying() {
        setUp();
        var incompleteMedia = PostMediaUploadedMediaPayload.builder()
                .id(UUID.randomUUID())
                .url(null)
                .thumbnailUrl("https://cdn/blob.jpg")
                .mediaType(MediaType.IMAGE)
                .order(1)
                .build();
        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .media(List.of(incompleteMedia))
                .build();

        assertThatThrownBy(() -> listener.onPostMediaUploaded(event))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(postMediaVerificationService);
    }

    @Test
    void shouldRejectUnsupportedPayloadTypeWithoutRetrying() {
        setUp();
        assertThatThrownBy(() -> listener.onUnsupportedPayload(new Object()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(postMediaVerificationService);
    }
}
