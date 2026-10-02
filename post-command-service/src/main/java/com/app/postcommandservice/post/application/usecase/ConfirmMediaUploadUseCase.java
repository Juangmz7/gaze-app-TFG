package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.commands.ConfirmMediaUploadCommand;
import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository;
import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository.MediaItem;
import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository.PendingMediaUpload;
import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.rabbitmq.PostMediaUploadedEventPublisher;

/**
 * Confirms a client-side media upload for a {@code PENDING} post (task 35). This use
 * case never performs a database write and never transitions the post's own status
 * itself: it only validates the request against a read-only projection and publishes
 * {@link PostMediaUploadedEvent} so the async verification flow (task 37) can resolve
 * the post to {@code ACCEPTED}/{@code MEDIA_UPLOAD_FAILED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmMediaUploadUseCase {

    private final ConfirmMediaUploadRepository confirmMediaUploadRepository;
    private final PostMediaProperties postMediaProperties;
    private final PostMediaUploadedEventPublisher postMediaUploadedEventPublisher;

    @Transactional(readOnly = true)
    public void confirm(ConfirmMediaUploadCommand command) {
        var pendingUpload = confirmMediaUploadRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        assertOwnership(pendingUpload, command.userId());
        assertPending(pendingUpload);
        assertWithinUploadWindow(pendingUpload);

        var event = toEvent(pendingUpload);
        postMediaUploadedEventPublisher.publish(event);

        log.info("Published PostMediaUploadedEvent {} for post {}", event.id(), pendingUpload.postId());
    }

    private void assertOwnership(PendingMediaUpload pendingUpload, UUID userId) {
        if (!pendingUpload.authorId().equals(userId)) {
            throw new PostOwnershipException(pendingUpload.postId(), userId);
        }
    }

    private void assertPending(PendingMediaUpload pendingUpload) {
        if (pendingUpload.status() != PostStatus.PENDING) {
            throw new PostNotPendingException(pendingUpload.postId(), pendingUpload.status(), "confirm media upload");
        }
    }

    private void assertWithinUploadWindow(PendingMediaUpload pendingUpload) {
        var windowExpiresAt = pendingUpload.createdAt().plus(postMediaProperties.getUploadWindow());
        if (Instant.now().isAfter(windowExpiresAt)) {
            throw new MediaUploadWindowExpiredException(pendingUpload.createdAt(), windowExpiresAt);
        }
    }

    private PostMediaUploadedEvent toEvent(PendingMediaUpload pendingUpload) {
        return PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(pendingUpload.postId())
                .media(toMediaPayload(pendingUpload.media()))
                .build();
    }

    private List<PostMediaUploadedMediaPayload> toMediaPayload(List<MediaItem> media) {
        return media.stream()
                .map(item -> PostMediaUploadedMediaPayload.builder()
                        .id(item.id())
                        .url(item.url())
                        .thumbnailUrl(item.thumbnailUrl())
                        .mediaType(item.mediaType())
                        .order(item.order())
                        .build())
                .toList();
    }
}
