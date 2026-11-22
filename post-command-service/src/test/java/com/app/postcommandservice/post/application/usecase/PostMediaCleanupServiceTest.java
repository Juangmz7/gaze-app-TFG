package com.app.postcommandservice.post.application.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.SelfProvider;
import com.app.postcommandservice.post.application.port.MediaBlobDeleter;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.CleanupCursor;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.ExpiredUpload;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.MediaBlobs;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaCleanupProperties;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationFailedEvent;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostMediaCleanupServiceTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private PostMediaCleanupRepository postMediaCleanupRepository;

    @Mock
    private MediaBlobDeleter mediaBlobDeleter;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private final JsonMapper jsonMapper = new JsonMapper(new tools.jackson.databind.ObjectMapper());
    private final PostMediaProperties postMediaProperties = new PostMediaProperties();
    private final PostMediaCleanupProperties postMediaCleanupProperties = new PostMediaCleanupProperties();

    private PostMediaCleanupService service;

    @BeforeEach
    void setUp() {
        service = service();
    }

    /**
     * In production {@code self} provides the Spring proxy of this bean. Without a Spring context
     * it provides the same instance (see {@link SelfProvider}), which behaves like a proxy
     * forwarding to the real bean.
     */
    private PostMediaCleanupService service() {
        var self = new SelfProvider<PostMediaCleanupService>();
        var instance = self.set(new PostMediaCleanupService(
                postMediaCleanupRepository,
                mediaBlobDeleter,
                postMediaProperties,
                postMediaCleanupProperties,
                outboxEventRepository,
                jsonMapper,
                applicationEventPublisher,
                self));
        return instance;
    }

    private static ExpiredUpload upload(PostStatus status, MediaBlobs... media) {
        return new ExpiredUpload(UUID.randomUUID(), status, OLD, List.of(media));
    }

    private static MediaBlobs video(String name) {
        return new MediaBlobs("https://blob/" + name + ".mp4", "https://blob/" + name + "-thumb.jpg");
    }

    private static MediaBlobs image(String name) {
        return new MediaBlobs("https://blob/" + name + ".jpg", "https://blob/" + name + ".jpg");
    }

    private void givenSinglePage(ExpiredUpload... uploads) {
        when(postMediaCleanupRepository.findExpiredUploads(any(Instant.class), isNull(), eq(200)))
                .thenReturn(List.of(uploads));
    }

    @Test
    void shouldExpirePendingPostsOlderThanTheWindowAndPublishFailedEventWithUploadExpired() {
        var pending = upload(PostStatus.PENDING, video("a"));
        givenSinglePage(pending);
        when(postMediaCleanupRepository.failIfPending(pending.postId())).thenReturn(true);
        when(mediaBlobDeleter.deleteIfExists(anyString())).thenReturn(true);
        when(postMediaCleanupRepository.markMediaPurged(eq(pending.postId()), any(Instant.class))).thenReturn(true);

        service.run();

        var createdBefore = ArgumentCaptor.forClass(Instant.class);
        verify(postMediaCleanupRepository).findExpiredUploads(createdBefore.capture(), isNull(), eq(200));
        assertThat(createdBefore.getValue())
                .isCloseTo(Instant.now().minus(Duration.ofHours(48)), within(Duration.ofSeconds(5)));

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        var outboxEvent = outboxEventCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo(PostMediaUploadValidationFailedEvent.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEvent.getPayload())
                .contains("\"postId\":\"" + pending.postId() + "\"")
                .contains("\"reasonCode\":\"UPLOAD_EXPIRED\"")
                .contains("Upload of post " + pending.postId()
                        + " expired: media was not confirmed within 48 hours");
        verify(applicationEventPublisher).publishEvent(new OutboxEventCreatedDomainEvent(outboxEvent.getId()));

        verify(mediaBlobDeleter).deleteIfExists("https://blob/a.mp4");
        verify(mediaBlobDeleter).deleteIfExists("https://blob/a-thumb.jpg");
        verify(postMediaCleanupRepository).markMediaPurged(eq(pending.postId()), any(Instant.class));
    }

    @Test
    void shouldPurgeBlobsOfMediaUploadFailedPostsWithoutPublishingAnEvent() {
        var failed = upload(PostStatus.MEDIA_UPLOAD_FAILED, video("b"));
        givenSinglePage(failed);
        when(mediaBlobDeleter.deleteIfExists(anyString())).thenReturn(true);
        when(postMediaCleanupRepository.markMediaPurged(eq(failed.postId()), any(Instant.class))).thenReturn(true);

        service.run();

        verify(postMediaCleanupRepository, never()).failIfPending(any());
        verifyNoInteractions(outboxEventRepository, applicationEventPublisher);
        verify(mediaBlobDeleter).deleteIfExists("https://blob/b.mp4");
        verify(mediaBlobDeleter).deleteIfExists("https://blob/b-thumb.jpg");
        verify(postMediaCleanupRepository).markMediaPurged(eq(failed.postId()), any(Instant.class));
    }

    @Test
    void shouldSkipThePostAndDeleteNothingWhenTheConditionalUpdateAffectsZeroRows() {
        var acceptedMeanwhile = upload(PostStatus.PENDING, video("c"));
        givenSinglePage(acceptedMeanwhile);
        when(postMediaCleanupRepository.failIfPending(acceptedMeanwhile.postId())).thenReturn(false);

        service.run();

        verifyNoInteractions(mediaBlobDeleter, outboxEventRepository, applicationEventPublisher);
        verify(postMediaCleanupRepository, never()).markMediaPurged(any(), any());
    }

    @Test
    void shouldContinueWithTheBatchWhenOnePostFails() {
        var broken = upload(PostStatus.MEDIA_UPLOAD_FAILED, image("broken"));
        var healthy = upload(PostStatus.MEDIA_UPLOAD_FAILED, image("healthy"));
        givenSinglePage(broken, healthy);
        when(mediaBlobDeleter.deleteIfExists("https://blob/broken.jpg"))
                .thenThrow(new IllegalStateException("storage unavailable"));
        when(mediaBlobDeleter.deleteIfExists("https://blob/healthy.jpg")).thenReturn(true);
        when(postMediaCleanupRepository.markMediaPurged(eq(healthy.postId()), any(Instant.class))).thenReturn(true);

        service.run();

        verify(postMediaCleanupRepository, never()).markMediaPurged(eq(broken.postId()), any());
        verify(postMediaCleanupRepository).markMediaPurged(eq(healthy.postId()), any(Instant.class));
    }

    @Test
    void shouldDeleteTheImageBlobOnlyOnce() {
        var failed = upload(PostStatus.MEDIA_UPLOAD_FAILED, image("img"));
        givenSinglePage(failed);
        when(mediaBlobDeleter.deleteIfExists("https://blob/img.jpg")).thenReturn(true);
        when(postMediaCleanupRepository.markMediaPurged(eq(failed.postId()), any(Instant.class))).thenReturn(true);

        service.run();

        verify(mediaBlobDeleter, times(1)).deleteIfExists("https://blob/img.jpg");
    }

    @Test
    void shouldReadNextPagesAfterTheLastPositionAndStopAtMaxBatchesPerRun() {
        postMediaCleanupProperties.setBatchSize(2);
        postMediaCleanupProperties.setMaxBatchesPerRun(2);
        var first = List.of(upload(PostStatus.MEDIA_UPLOAD_FAILED), upload(PostStatus.MEDIA_UPLOAD_FAILED));
        var second = List.of(upload(PostStatus.MEDIA_UPLOAD_FAILED), upload(PostStatus.MEDIA_UPLOAD_FAILED));
        when(postMediaCleanupRepository.findExpiredUploads(any(Instant.class), isNull(), eq(2))).thenReturn(first);
        when(postMediaCleanupRepository.findExpiredUploads(any(Instant.class), eq(first.getLast().cursor()), eq(2)))
                .thenReturn(second);
        when(postMediaCleanupRepository.markMediaPurged(any(), any(Instant.class))).thenReturn(true);

        service.run();

        verify(postMediaCleanupRepository, times(2)).findExpiredUploads(any(Instant.class), any(), anyInt());
        verify(postMediaCleanupRepository, times(4)).markMediaPurged(any(), any(Instant.class));
    }

    @Test
    void shouldStopAfterAShortPage() {
        postMediaCleanupProperties.setBatchSize(2);
        when(postMediaCleanupRepository.findExpiredUploads(any(Instant.class), isNull(), eq(2)))
                .thenReturn(new ArrayList<>(List.of(upload(PostStatus.MEDIA_UPLOAD_FAILED))));
        when(postMediaCleanupRepository.markMediaPurged(any(), any(Instant.class))).thenReturn(true);

        service.run();

        verify(postMediaCleanupRepository, times(1)).findExpiredUploads(any(Instant.class), any(), anyInt());
        verify(postMediaCleanupRepository, never())
                .findExpiredUploads(any(Instant.class), any(CleanupCursor.class), anyInt());
    }
}
