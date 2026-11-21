package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabDeletedDomainEvent;
import com.app.postcommandservice.collab.infrastructure.events.CollabDeletedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.post.application.repository.UnacceptedPostDeletionRepository;
import com.app.postcommandservice.post.domain.events.PostMediaBlobsReleasedEvent;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

/**
 * Hard-deletes a post that never reached {@code ACCEPTED} (task 60), for the owner's
 * {@code DELETE /api/posts/{postId}} ({@link DeletePostUseCase}) and for the scheduled purge of
 * failed posts ({@link FailedPostPurgeService}). Callers check ownership; this use case only
 * deletes.
 *
 * <p>Downstream services never received the post ({@code PostCreatedEvent} is only published on
 * {@code ACCEPTED}), so no post event is published. The collab created with the post is
 * different: {@code CollabOpenedEvent} was already published, so when no other post references
 * the collab it is deleted too and {@code CollabDeletedEvent} goes through the outbox in the same
 * transaction.</p>
 *
 * <p>The post goes with a conditional delete on its status, which is always issued and is the only
 * arbiter: when it matches no row (the post does not exist, or media verification accepted it
 * between the caller's read and the delete) nothing else is written and {@link Outcome#NOT_DELETED}
 * is returned, so an {@code ACCEPTED} post is never deleted silently. The statement flushes and
 * clears the persistence context in every case, so a caller that loaded the post before (like
 * {@link DeletePostUseCase}) reads its current state from the database on its next lookup. Blobs are deleted after the transaction commits ({@link PostMediaBlobsReleasedEvent}),
 * never inside it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteUnacceptedPostUseCase {

    private static final Set<PostStatus> UNACCEPTED_STATUSES = EnumSet.of(PostStatus.PENDING, PostStatus.MEDIA_UPLOAD_FAILED);

    private final UnacceptedPostDeletionRepository unacceptedPostDeletionRepository;
    private final CollabRepository collabRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public static boolean isUnaccepted(PostStatus status) {
        return status != null && UNACCEPTED_STATUSES.contains(status);
    }

    @Transactional
    public Outcome delete(UUID postId) {
        Objects.requireNonNull(postId, "postId must not be null");

        // Read before the delete: the collab and blob data are gone afterwards. The snapshot never
        // decides whether to delete; the conditional delete below is the only arbiter.
        var snapshot = unacceptedPostDeletionRepository.findSnapshot(postId).orElse(null);

        if (!unacceptedPostDeletionRepository.deleteIfUnaccepted(postId)) {
            log.info("Post {} was not deleted as never-accepted: it does not exist or is not (or no longer) "
                    + "PENDING / MEDIA_UPLOAD_FAILED", postId);
            return Outcome.NOT_DELETED;
        }
        unacceptedPostDeletionRepository.deleteRequestIdempotency(postId);

        if (snapshot == null) {
            // Only possible if the row appeared between the two statements; no collab or blob data to clean
            log.warn("Deleted never-accepted post {} without a prior snapshot: collab and blobs not cleaned", postId);
            return new Outcome(true, false);
        }

        boolean collabDeleted = snapshot.collabId() != null
                && deleteCollabIfOrphaned(snapshot.collabId(), snapshot.userId());

        if (!snapshot.blobUrls().isEmpty()) {
            applicationEventPublisher.publishEvent(new PostMediaBlobsReleasedEvent(postId, snapshot.blobUrls()));
        }

        log.info("Deleted never-accepted post {} (status={}, collabDeleted={})", postId, snapshot.status(), collabDeleted);
        return new Outcome(true, collabDeleted);
    }

    /**
     * Deletes the collab when no other post references it. {@code CollabDeletedEvent} is only
     * published when the collab was not already deleted (post-query already processed that one).
     */
    private boolean deleteCollabIfOrphaned(UUID collabId, UUID actionedBy) {
        if (unacceptedPostDeletionRepository.collabHasPosts(collabId)) {
            return false;
        }
        var collab = collabRepository.findById(collabId).orElse(null);
        if (collab == null) {
            return false;
        }

        unacceptedPostDeletionRepository.deleteCollab(collabId);
        if (!collab.isDeleted()) {
            publishCollabDeleted(collabId, actionedBy);
        }
        return true;
    }

    private void publishCollabDeleted(UUID collabId, UUID actionedBy) {
        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var event = collabEventMapper.toCollabDeletedEvent(
                outboxId,
                correlationId,
                collabId,
                actionedBy,
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
        applicationEventPublisher.publishEvent(new CollabDeletedDomainEvent(outboxId));
    }

    /**
     * @param postDeleted   whether the post was deleted; {@code false} when it does not exist or is
     *                      not (or no longer) {@code PENDING} / {@code MEDIA_UPLOAD_FAILED}
     * @param collabDeleted whether its collab was deleted with it
     */
    public record Outcome(boolean postDeleted, boolean collabDeleted) {

        public static final Outcome NOT_DELETED = new Outcome(false, false);
    }
}
