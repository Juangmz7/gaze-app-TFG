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
import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;
import com.app.postcommandservice.post.application.port.MediaVerificationFailureReason;
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

/**
 * Scheduled cleanup of expired uploads (task 58, spec of task 39). Every run takes the posts
 * whose media was not resolved within {@code posts.media.upload-window}: a {@code PENDING} post
 * becomes {@code MEDIA_UPLOAD_FAILED} (publishing {@code PostMediaUploadValidationFailedEvent}
 * with {@code UPLOAD_EXPIRED}), then the blobs of every such post are deleted and
 * {@code media_purged_at} is set. Post rows are kept; {@code ACCEPTED} posts are never selected.
 *
 * <p>Same transaction layout as {@link PostMediaVerificationService}: {@link #run()} is not
 * transactional and calls the read-only page query and the two {@code REQUIRES_NEW} writes
 * through {@code self}, an injected {@code @Lazy} proxy of this bean (calling them on
 * {@code this} would bypass the transaction proxy). Blobs are deleted between the two writes,
 * with no transaction open, and only after the conditional {@code PENDING ->
 * MEDIA_UPLOAD_FAILED} update succeeded, so a post the upload listener accepts concurrently
 * keeps its media.</p>
 *
 * <p>Work per run is bounded by {@code batch-size * max-batches-per-run}. A failure on one post is
 * logged and the post is retried by the next run (blob deletion is idempotent); it never aborts
 * the run. ShedLock makes sure a single instance runs each interval.</p>
 */
@Slf4j
@Service
public class PostMediaCleanupService {

    static final String LOCK_NAME = "postMediaCleanupJob";

    private final PostMediaCleanupRepository postMediaCleanupRepository;
    private final MediaBlobDeleter mediaBlobDeleter;
    private final PostMediaProperties postMediaProperties;
    private final PostMediaCleanupProperties postMediaCleanupProperties;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PostMediaCleanupService self;

    public PostMediaCleanupService(
            PostMediaCleanupRepository postMediaCleanupRepository,
            MediaBlobDeleter mediaBlobDeleter,
            PostMediaProperties postMediaProperties,
            PostMediaCleanupProperties postMediaCleanupProperties,
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper,
            ApplicationEventPublisher applicationEventPublisher,
            @Lazy PostMediaCleanupService self) {
        this.postMediaCleanupRepository = postMediaCleanupRepository;
        this.mediaBlobDeleter = mediaBlobDeleter;
        this.postMediaProperties = postMediaProperties;
        this.postMediaCleanupProperties = postMediaCleanupProperties;
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.self = self;
    }

    @Scheduled(cron = "${posts.media-cleanup.cron:0 15 * * * *}")
    @SchedulerLock(
            name = LOCK_NAME,
            lockAtMostFor = "${posts.media-cleanup.lock-at-most-for:PT55M}",
            lockAtLeastFor = "${posts.media-cleanup.lock-at-least-for:PT5M}")
    public void run() {
        Instant createdBefore = Instant.now().minus(postMediaProperties.getUploadWindow());
        int batchSize = postMediaCleanupProperties.getBatchSize();
        int maxBatches = postMediaCleanupProperties.getMaxBatchesPerRun();
        RunSummary summary = new RunSummary();

        CleanupCursor cursor = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<ExpiredUpload> page = self.findExpiredUploads(createdBefore, cursor, batchSize);
            for (ExpiredUpload upload : page) {
                processSafely(upload, summary);
            }
            if (page.size() < batchSize) {
                break;
            }
            // Advance past the whole page, failed posts included: they are retried by the next run
            cursor = page.getLast().cursor();
        }

        log.info(
                "Media cleanup run finished: postsExpired={}, postsPurged={}, blobsDeleted={}, postsSkipped={}, failures={}",
                summary.expired, summary.purged, summary.blobsDeleted, summary.skipped, summary.failures);
    }

    @Transactional(readOnly = true)
    public List<ExpiredUpload> findExpiredUploads(Instant createdBefore, CleanupCursor after, int limit) {
        return postMediaCleanupRepository.findExpiredUploads(createdBefore, after, limit);
    }

    /**
     * Moves an expired {@code PENDING} post to {@code MEDIA_UPLOAD_FAILED} and writes the failed
     * event to the outbox in the same transaction (sent after commit). Returns {@code false},
     * writing nothing, when the post was no longer {@code PENDING}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expirePendingUpload(UUID postId) {
        if (!postMediaCleanupRepository.failIfPending(postId)) {
            return false;
        }

        var failedEvent = PostMediaUploadValidationFailedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(postId)
                .reasonCode(MediaVerificationFailureReason.UPLOAD_EXPIRED)
                .reason("Upload of post %s expired: media was not confirmed within %d hours"
                        .formatted(postId, postMediaProperties.getUploadWindow().toHours()))
                .build();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(failedEvent.id())
                .correlationId(failedEvent.correlationId())
                .payload(jsonMapper.toJson(failedEvent))
                .eventType(PostMediaUploadValidationFailedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .build());
        applicationEventPublisher.publishEvent(new OutboxEventCreatedDomainEvent(failedEvent.id()));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markMediaPurged(UUID postId) {
        return postMediaCleanupRepository.markMediaPurged(postId, Instant.now());
    }

    private void processSafely(ExpiredUpload upload, RunSummary summary) {
        try {
            process(upload, summary);
        } catch (RuntimeException exception) {
            summary.failures++;
            log.warn("Media cleanup failed for post {} (status={}); the next run retries it",
                    upload.postId(), upload.status(), exception);
        }
    }

    private void process(ExpiredUpload upload, RunSummary summary) {
        UUID postId = upload.postId();
        if (upload.status() == PostStatus.PENDING) {
            if (!self.expirePendingUpload(postId)) {
                summary.skipped++;
                log.info("Skipping media cleanup of post {}: it is no longer PENDING", postId);
                return;
            }
            summary.expired++;
            log.info("Post {} expired: media was not confirmed within the upload window", postId);
        } else if (upload.status() != PostStatus.MEDIA_UPLOAD_FAILED) {
            summary.skipped++;
            log.warn("Skipping media cleanup of post {}: unexpected status {}", postId, upload.status());
            return;
        }

        summary.blobsDeleted += deleteBlobs(upload);

        if (self.markMediaPurged(postId)) {
            summary.purged++;
        } else {
            log.info("Post {} was already purged or is no longer MEDIA_UPLOAD_FAILED", postId);
        }
    }

    /** Deletes each distinct blob once: an IMAGE has the same url as url and thumbnailUrl. */
    private int deleteBlobs(ExpiredUpload upload) {
        Set<String> blobUrls = new LinkedHashSet<>();
        for (MediaBlobs media : upload.media()) {
            addIfPresent(blobUrls, media.url());
            addIfPresent(blobUrls, media.thumbnailUrl());
        }

        int deleted = 0;
        for (String blobUrl : blobUrls) {
            if (mediaBlobDeleter.deleteIfExists(blobUrl)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static void addIfPresent(Set<String> blobUrls, String blobUrl) {
        if (StringUtils.hasText(blobUrl)) {
            blobUrls.add(blobUrl);
        }
    }

    private static final class RunSummary {
        private int expired;
        private int purged;
        private int blobsDeleted;
        private int skipped;
        private int failures;
    }
}
