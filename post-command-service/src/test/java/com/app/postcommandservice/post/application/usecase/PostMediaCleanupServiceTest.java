package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.ExpiredPost;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.MediaBlobRef;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaCleanupProperties;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationFailedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostMediaCleanupServiceTest {

    @Mock
    private PostMediaCleanupRepository postMediaCleanupRepository;

    @Mock
    private MediaBlobDeleter mediaBlobDeleter;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private final PostMediaCleanupProperties cleanupProperties = new PostMediaCleanupProperties();
    private final PostMediaProperties mediaProperties = new PostMediaProperties();
    private final JsonMapper jsonMapper = new JsonMapper(new tools.jackson.databind.ObjectMapper());

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    /**
     * In production {@code self} is the Spring AOP proxy of this same bean, so {@code run()}'s
     * calls to {@code self.findExpiredBatch(...)}/{@code self.expirePendingPost(...)}/{@code
     * self.markMediaPurged(...)} actually run inside their own transactions. There is no
     * Spring context in this plain unit test, so {@code self} is wired to the very same
     * instance via reflection — behaviourally equivalent for everything these tests assert on.
     */
    private PostMediaCleanupService service() {
        PostMediaCleanupService instance = new PostMediaCleanupService(
                postMediaCleanupRepository,
                mediaBlobDeleter,
                cleanupProperties,
                mediaProperties,
                outboxEventRepository,
                jsonMapper,
                applicationEventPublisher,
                null);
        try {
            var selfField = PostMediaCleanupService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(instance, instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return instance;
    }

    private ExpiredPost pendingPost(UUID postId, Instant createdAt, MediaBlobRef... media) {
        return new ExpiredPost(postId, PostStatus.PENDING, createdAt, List.of(media));
    }

    private ExpiredPost failedPost(UUID postId, Instant createdAt, MediaBlobRef... media) {
        return new ExpiredPost(postId, PostStatus.MEDIA_UPLOAD_FAILED, createdAt, List.of(media));
    }

    @Test
    void shouldExpirePendingPostsOlderThanTheWindowAndPublishFailedEventWithUploadExpired() {
        var postId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var createdAt = Instant.now().minus(mediaProperties.getUploadWindow()).minusSeconds(3600);
        var post = pendingPost(postId, createdAt, new MediaBlobRef(mediaId, MediaType.IMAGE, "https://cdn/img.jpg", "https://cdn/img.jpg"));

        when(postMediaCleanupRepository.findExpiredBatch(any(), isNull(), anyInt())).thenReturn(List.of(post));
        when(postMediaCleanupRepository.transitionPendingToFailed(postId)).thenReturn(1);
        when(postMediaCleanupRepository.markMediaPurged(eq(postId), any())).thenReturn(1);

        service().run();

        verify(postMediaCleanupRepository).transitionPendingToFailed(postId);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        var saved = outboxEventCaptor.getValue();
        assertThat(saved.getEventType()).isEqualTo(PostMediaUploadValidationFailedEvent.class.getSimpleName());
        assertThat(saved.getPayload()).contains("UPLOAD_EXPIRED");
        assertThat(saved.getPayload()).contains(postId.toString());
        verify(postMediaCleanupRepository).markMediaPurged(eq(postId), any());
        verify(mediaBlobDeleter).deleteIfExists("https://cdn/img.jpg");
    }

    @Test
    void shouldPurgeBlobsOfMediaUploadFailedPostsWithoutPublishingAnEvent() {
        var postId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var createdAt = Instant.now().minus(mediaProperties.getUploadWindow()).minusSeconds(3600);
        var post = failedPost(postId, createdAt, new MediaBlobRef(mediaId, MediaType.IMAGE, "https://cdn/img.jpg", "https://cdn/img.jpg"));

        when(postMediaCleanupRepository.findExpiredBatch(any(), isNull(), anyInt())).thenReturn(List.of(post));
        when(postMediaCleanupRepository.markMediaPurged(eq(postId), any())).thenReturn(1);

        service().run();

        verify(postMediaCleanupRepository, never()).transitionPendingToFailed(any());
        verifyNoInteractions(outboxEventRepository);
        verifyNoInteractions(applicationEventPublisher);
        verify(mediaBlobDeleter).deleteIfExists("https://cdn/img.jpg");
        verify(postMediaCleanupRepository).markMediaPurged(eq(postId), any());
    }

    @Test
    void shouldSkipThePostAndDeleteNothingWhenTheConditionalUpdateAffectsZeroRows() {
        var postId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var createdAt = Instant.now().minus(mediaProperties.getUploadWindow()).minusSeconds(3600);
        var post = pendingPost(postId, createdAt, new MediaBlobRef(mediaId, MediaType.IMAGE, "https://cdn/img.jpg", "https://cdn/img.jpg"));

        when(postMediaCleanupRepository.findExpiredBatch(any(), isNull(), anyInt())).thenReturn(List.of(post));
        when(postMediaCleanupRepository.transitionPendingToFailed(postId)).thenReturn(0);

        service().run();

        verifyNoInteractions(mediaBlobDeleter);
        verifyNoInteractions(outboxEventRepository);
        verifyNoInteractions(applicationEventPublisher);
        verify(postMediaCleanupRepository, never()).markMediaPurged(any(), any());
    }

    @Test
    void shouldContinueWithTheBatchWhenOnePostFails() {
        var failingPostId = UUID.randomUUID();
        var failingMediaId = UUID.randomUUID();
        var okPostId = UUID.randomUUID();
        var okMediaId = UUID.randomUUID();
        var createdAt = Instant.now().minus(mediaProperties.getUploadWindow()).minusSeconds(3600);

        var failingPost = failedPost(failingPostId, createdAt,
                new MediaBlobRef(failingMediaId, MediaType.IMAGE, "https://cdn/boom.jpg", "https://cdn/boom.jpg"));
        var okPost = failedPost(okPostId, createdAt.plusSeconds(1),
                new MediaBlobRef(okMediaId, MediaType.IMAGE, "https://cdn/ok.jpg", "https://cdn/ok.jpg"));

        when(postMediaCleanupRepository.findExpiredBatch(any(), isNull(), anyInt())).thenReturn(List.of(failingPost, okPost));
        org.mockito.Mockito.doThrow(new RuntimeException("Azure outage"))
                .when(mediaBlobDeleter).deleteIfExists("https://cdn/boom.jpg");
        when(postMediaCleanupRepository.markMediaPurged(eq(okPostId), any())).thenReturn(1);

        service().run();

        verify(mediaBlobDeleter).deleteIfExists("https://cdn/boom.jpg");
        verify(mediaBlobDeleter).deleteIfExists("https://cdn/ok.jpg");
        verify(postMediaCleanupRepository, never()).markMediaPurged(eq(failingPostId), any());
        verify(postMediaCleanupRepository).markMediaPurged(eq(okPostId), any());
    }

    @Test
    void shouldDeleteTheImageBlobOnlyOnce() {
        var postId = UUID.randomUUID();
        var mediaId = UUID.randomUUID();
        var createdAt = Instant.now().minus(mediaProperties.getUploadWindow()).minusSeconds(3600);
        // IMAGE media: url and thumbnailUrl point at the exact same blob.
        var post = failedPost(postId, createdAt,
                new MediaBlobRef(mediaId, MediaType.IMAGE, "https://cdn/same-blob.jpg", "https://cdn/same-blob.jpg"));

        when(postMediaCleanupRepository.findExpiredBatch(any(), isNull(), anyInt())).thenReturn(List.of(post));
        when(postMediaCleanupRepository.markMediaPurged(eq(postId), any())).thenReturn(1);

        service().run();

        verify(mediaBlobDeleter, times(1)).deleteIfExists("https://cdn/same-blob.jpg");
    }
}
