package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
import com.app.postcommandservice.post.application.port.MediaVerificationResult;
import com.app.postcommandservice.post.application.port.MediaVerifier;
import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository;
import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository.MediaSnapshot;
import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository.PostSnapshot;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.events.PostCreatedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationFailedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationSucceededEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostMediaVerificationServiceTest {

    private static final UUID POST_ID = UUID.randomUUID();

    @Mock
    private PostMediaVerificationRepository postMediaVerificationRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private MediaVerifier mediaVerifier;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private final PostEventMapper postEventMapper = new PostEventMapper();
    private final JsonMapper jsonMapper = new JsonMapper(new tools.jackson.databind.ObjectMapper());

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    /**
     * A thin self-delegating "proxy" stand-in: since this test drives the real bean (no Spring
     * context), {@code self} is the very same instance, which is enough to prove {@code
     * process} invokes {@code findPostSnapshot}/{@code applyVerificationOutcome} rather than
     * inlining their logic. A dedicated test below uses a spy to prove the self-invocation
     * actually goes through a separate reference rather than {@code this}.
     */
    private PostMediaVerificationService newService(PostMediaVerificationService self) {
        return new PostMediaVerificationService(
                postMediaVerificationRepository,
                postRepository,
                mediaVerifier,
                postEventMapper,
                outboxEventRepository,
                jsonMapper,
                applicationEventPublisher,
                self);
    }

    /**
     * In production {@code self} is the Spring AOP proxy of this same bean, so {@code
     * process()}'s calls to {@code self.findPostSnapshot(...)}/{@code
     * self.applyVerificationOutcome(...)} actually run inside their own transactions. There is
     * no Spring context in this plain unit test, so {@code self} is wired to the very same
     * instance via reflection (the constructor cannot take {@code this} before construction
     * finishes) — behaviourally equivalent for everything these tests assert on, since a proxy
     * forwarding to the real bean behaves like the bean calling itself.
     */
    private PostMediaVerificationService service() {
        PostMediaVerificationService instance = newService(null);
        try {
            var selfField = PostMediaVerificationService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(instance, instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return instance;
    }

    private PostMediaUploadedEvent uploadedEvent(UUID mediaId, MediaType mediaType) {
        return PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(POST_ID)
                .media(List.of(PostMediaUploadedMediaPayload.builder()
                        .id(mediaId)
                        .url("https://cdn/" + mediaId + ".mp4")
                        .thumbnailUrl("https://cdn/" + mediaId + "-thumb.jpg")
                        .mediaType(mediaType)
                        .order(1)
                        .build()))
                .build();
    }

    private PostSnapshot pendingSnapshot(UUID mediaId, MediaType mediaType) {
        return new PostSnapshot(POST_ID, PostStatus.PENDING, List.of(new MediaSnapshot(mediaId, mediaType)));
    }

    private Post acceptedPost(UUID mediaId, MediaType mediaType, Integer durationMillis) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(new PostDescription("d"), new PostTags(new LinkedHashSet<>(Set.of("tag"))), PostType.BASIC),
                List.of(PostMedia.create(POST_ID, "https://cdn/blob", "https://cdn/thumb", mediaType, durationMillis, Set.of(), 1)),
                PostStatus.ACCEPTED,
                now,
                now
        );
    }

    @Test
    void shouldSetAcceptedPersistDurationsAndPublishSucceededEventAndPostCreatedEventOnSuccess() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.VIDEO);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(mediaId, MediaType.VIDEO)));
        when(mediaVerifier.verify(any())).thenReturn(MediaVerificationResult.success(Map.of(mediaId, 7)));
        when(postMediaVerificationRepository.updateStatusIfPending(POST_ID, PostStatus.ACCEPTED)).thenReturn(1);
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(acceptedPost(mediaId, MediaType.VIDEO, 7000)));

        service.process(event);

        verify(postMediaVerificationRepository).updateStatusIfPending(POST_ID, PostStatus.ACCEPTED);
        verify(postMediaVerificationRepository).persistVideoDurationsMillis(Map.of(mediaId, 7000));
        verify(outboxEventRepository, times(2)).save(outboxEventCaptor.capture());

        var eventTypes = outboxEventCaptor.getAllValues().stream().map(OutboxEvent::getEventType).toList();
        assertThat(eventTypes).containsExactlyInAnyOrder(
                PostMediaUploadValidationSucceededEvent.class.getSimpleName(),
                PostCreatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getAllValues()).allMatch(e -> e.getStatus() == EventStatus.PENDING);
    }

    @Test
    void shouldSetMediaUploadFailedAndPublishFailedEventWithCustomReasonOnBusinessFailure() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(mediaId, MediaType.IMAGE)));
        when(mediaVerifier.verify(any())).thenReturn(MediaVerificationResult.failure(
                MediaVerificationFailureReason.BLOB_NOT_FOUND, mediaId, "https://cdn/missing.jpg", "raw azure 404 detail"));
        when(postMediaVerificationRepository.updateStatusIfPending(POST_ID, PostStatus.MEDIA_UPLOAD_FAILED)).thenReturn(1);

        service.process(event);

        verify(postMediaVerificationRepository).updateStatusIfPending(POST_ID, PostStatus.MEDIA_UPLOAD_FAILED);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());

        var saved = outboxEventCaptor.getValue();
        assertThat(saved.getEventType()).isEqualTo(PostMediaUploadValidationFailedEvent.class.getSimpleName());
        assertThat(saved.getPayload()).doesNotContain("raw azure 404 detail");
        assertThat(saved.getPayload()).contains(POST_ID.toString());
        verify(postRepository, never()).findById(any());
    }

    @Test
    void shouldNotThrowOnBusinessFailure() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(mediaId, MediaType.IMAGE)));
        when(mediaVerifier.verify(any())).thenReturn(MediaVerificationResult.failure(
                MediaVerificationFailureReason.CORRUPT_FILE, mediaId, "https://cdn/corrupt.jpg", "boom"));
        when(postMediaVerificationRepository.updateStatusIfPending(POST_ID, PostStatus.MEDIA_UPLOAD_FAILED)).thenReturn(1);

        org.assertj.core.api.Assertions.assertThatCode(() -> service.process(event)).doesNotThrowAnyException();
    }

    @Test
    void shouldRethrowOnInfrastructureFailure() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(mediaId, MediaType.IMAGE)));
        when(mediaVerifier.verify(any())).thenThrow(new RuntimeException("Azure storage unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Azure storage unavailable");

        verify(postMediaVerificationRepository, never()).updateStatusIfPending(any(), any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void shouldIgnoreMessagesForNonPendingPosts() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(new PostSnapshot(POST_ID, PostStatus.ACCEPTED,
                        List.of(new MediaSnapshot(mediaId, MediaType.IMAGE)))));

        service.process(event);

        verifyNoInteractions(mediaVerifier);
        verifyNoInteractions(outboxEventRepository);
        verify(postMediaVerificationRepository, never()).updateStatusIfPending(any(), any());
    }

    @Test
    void shouldIgnoreMessagesForAbsentPosts() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID)).thenReturn(Optional.empty());

        service.process(event);

        verifyNoInteractions(mediaVerifier);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void shouldIgnoreMessagesWhoseMediaIdsDoNotMatchThePersistedPost() {
        var persistedMediaId = UUID.randomUUID();
        var payloadMediaId = UUID.randomUUID();
        var event = uploadedEvent(payloadMediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(persistedMediaId, MediaType.IMAGE)));

        service.process(event);

        verifyNoInteractions(mediaVerifier);
        verifyNoInteractions(outboxEventRepository);
        verify(postMediaVerificationRepository, never()).updateStatusIfPending(any(), any());
    }

    @Test
    void shouldPublishNothingWhenTheConditionalUpdateAffectsZeroRows() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);
        var service = service();

        when(postMediaVerificationRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(mediaId, MediaType.IMAGE)));
        when(mediaVerifier.verify(any())).thenReturn(MediaVerificationResult.success(Map.of()));
        when(postMediaVerificationRepository.updateStatusIfPending(POST_ID, PostStatus.ACCEPTED)).thenReturn(0);

        service.process(event);

        verify(postMediaVerificationRepository, never()).persistVideoDurationsMillis(any());
        verifyNoInteractions(outboxEventRepository);
        verifyNoInteractions(applicationEventPublisher);
        verify(postRepository, never()).findById(any());
    }

    @Test
    void shouldInvokeTransactionalMethodsThroughTheInjectedSelfProxy() {
        var mediaId = UUID.randomUUID();
        var event = uploadedEvent(mediaId, MediaType.IMAGE);

        PostMediaVerificationService selfProxy = org.mockito.Mockito.mock(PostMediaVerificationService.class);
        var serviceUnderTest = newService(selfProxy);

        when(selfProxy.findPostSnapshot(POST_ID))
                .thenReturn(Optional.of(pendingSnapshot(mediaId, MediaType.IMAGE)));
        when(mediaVerifier.verify(any())).thenReturn(MediaVerificationResult.success(Map.of()));

        serviceUnderTest.process(event);

        verify(selfProxy).findPostSnapshot(POST_ID);
        verify(selfProxy).applyVerificationOutcome(eq(POST_ID), any(MediaVerificationResult.class));
        // the real applyVerificationOutcome must never run directly on serviceUnderTest when
        // self is a separate mock, proving process() never calls this.applyVerificationOutcome(...)
        verifyNoInteractions(postMediaVerificationRepository);
    }
}
