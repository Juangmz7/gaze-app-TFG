package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;
import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.ExpiredPost;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.KeysetCursor;
import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository.MediaBlobRef;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaCleanupProperties;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationFailedEvent;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

/**
 * Hourly job (task 39) that expires posts whose media upload was never confirmed within
 * {@code posts.media.upload-window} and deletes their blobs from Azure, and also deletes the
 * blobs of already-{@code MEDIA_UPLOAD_FAILED} posts past that same window.
 *
 * <p>Mirrors the shape of {@code PostMediaVerificationService} (task 37): {@link #run()}
 * itself is deliberately NOT {@code @Transactional} — it reads bounded pages in their own
 * short read-only transactions, deletes blobs with no transaction open (so no DB connection
 * is held across the network call), and applies each status/purge change in its own fresh
 * {@code REQUIRES_NEW} transaction. Every one of those steps is invoked through {@code self}
 * — an injected {@code @Lazy} proxy of this same bean — because calling {@code
 * this.findExpiredBatch(...)} etc. directly would bypass the Spring AOP proxy and silently
 * run with no transaction at all.</p>
 *
 * <p>One post failing (for any reason, including a transient Azure error) is logged and
 * skipped; it never aborts the run, and the next hourly run retries it since blob deletion is
 * idempotent.</p>
 */
@Slf4j
@Service
public class PostMediaCleanupService {

    private final PostMediaCleanupRepository postMediaCleanupRepository;
    private final MediaBlobDeleter mediaBlobDeleter;
    private final PostMediaCleanupProperties cleanupProperties;
    private final PostMediaProperties mediaProperties;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PostMediaCleanupService self;

    public PostMediaCleanupService(
            PostMediaCleanupRepository postMediaCleanupRepository,
            MediaBlobDeleter mediaBlobDeleter,
            PostMediaCleanupProperties cleanupProperties,
            PostMediaProperties mediaProperties,
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper,
            ApplicationEventPublisher applicationEventPublisher,
            @Lazy PostMediaCleanupService self) {
        this.postMediaCleanupRepository = postMediaCleanupRepository;
        this.mediaBlobDeleter = mediaBlobDeleter;
        this.cleanupProperties = cleanupProperties;
        this.mediaProperties = mediaProperties;
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.self = self;
    }

    @Scheduled(cron = "${posts.media-cleanup.cron:0 15 * * * *}")
    @SchedulerLock(name = "postMediaCleanupJob", lockAtMostFor = "${posts.media-cleanup.lock-at-most-for:PT55M}")
    public void run() {
        Instant expiryThreshold = Instant.now().minus(mediaProperties.getUploadWindow());

        int expiredCount = 0;
        int purgedCount = 0;
        int blobsDeletedCount = 0;
        int failureCount = 0;
        KeysetCursor cursor = null;

        for (int batch = 0; batch < cleanupProperties.getMaxBatchesPerRun(); batch++) {
            List<ExpiredPost> page = self.findExpiredBatch(expiryThreshold, cursor, cleanupProperties.getBatchSize());
            if (page.isEmpty()) {
                break;
            }

            for (ExpiredPost post : page) {
                cursor = new KeysetCursor(post.createdAt(), post.postId());
                try {
                    ProcessOutcome outcome = processPost(post);
                    if (outcome.expired()) {
                        expiredCount++;
                    }
                    if (outcome.purged()) {
                        purgedCount++;
                    }
                    blobsDeletedCount += outcome.blobsDeleted();
                } catch (Exception e) {
                    failureCount++;
                    log.error("Media cleanup failed for post {}; will retry on the next run", post.postId(), e);
                }
            }

            if (page.size() < cleanupProperties.getBatchSize()) {
                break;
            }
        }

        log.info(
                "Media cleanup run summary: expired={}, purged={}, blobsDeleted={}, failures={}",
                expiredCount, purgedCount, blobsDeletedCount, failureCount);
    }

    private ProcessOutcome processPost(ExpiredPost post) {
        boolean expired = false;
        if (post.status() == PostStatus.PENDING) {
            int updatedRows = self.expirePendingPost(post.postId());
            if (updatedRows == 0) {
                log.info("Skipping media cleanup for post {}: no longer PENDING (accepted concurrently)", post.postId());
                return ProcessOutcome.skipped();
            }
            expired = true;
        }

        int blobsDeleted = deleteBlobsOnce(post.mediaBlobs());

        int purgedRows = self.markMediaPurged(post.postId());

        return new ProcessOutcome(expired, purgedRows > 0, blobsDeleted);
    }

    @Transactional(readOnly = true)
    public List<ExpiredPost> findExpiredBatch(Instant expiryThreshold, KeysetCursor cursor, int batchSize) {
        return postMediaCleanupRepository.findExpiredBatch(expiryThreshold, cursor, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expirePendingPost(UUID postId) {
        int updatedRows = postMediaCleanupRepository.transitionPendingToFailed(postId);
        if (updatedRows == 0) {
            return 0;
        }

        var failedEvent = PostMediaUploadValidationFailedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(postId)
                .reasonCode(MediaVerificationFailureReason.UPLOAD_EXPIRED)
                .reason("Upload of post %s expired: media was not confirmed within 48 hours".formatted(postId))
                .build();
        publishThroughOutbox(failedEvent, PostMediaUploadValidationFailedEvent.class.getSimpleName());

        log.warn("Post {} expired: media upload was not confirmed within the upload window", postId);
        return updatedRows;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markMediaPurged(UUID postId) {
        return postMediaCleanupRepository.markMediaPurged(postId, Instant.now());
    }

    /**
     * Deletes every distinct blob URL referenced by {@code mediaBlobs}, strictly outside any
     * transaction. IMAGE media's {@code url} and {@code thumbnailUrl} point at the same blob,
     * so deduplicating by URL deletes it exactly once.
     */
    private int deleteBlobsOnce(List<MediaBlobRef> mediaBlobs) {
        Set<String> uniqueUrls = new LinkedHashSet<>();
        for (MediaBlobRef media : mediaBlobs) {
            uniqueUrls.add(media.url());
            if (media.thumbnailUrl() != null) {
                uniqueUrls.add(media.thumbnailUrl());
            }
        }

        for (String url : uniqueUrls) {
            mediaBlobDeleter.deleteIfExists(url);
        }
        return uniqueUrls.size();
    }

    private void publishThroughOutbox(EventMessage event, String eventType) {
        var outboxId = event.id();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(outboxId)
                .correlationId(event.correlationId())
                .payload(jsonMapper.toJson(event))
                .eventType(eventType)
                .status(EventStatus.PENDING)
                .build());

        applicationEventPublisher.publishEvent(new OutboxEventCreatedDomainEvent(outboxId));
    }

    private record ProcessOutcome(boolean expired, boolean purged, int blobsDeleted) {
        static ProcessOutcome skipped() {
            return new ProcessOutcome(false, false, 0);
        }
    }
}
