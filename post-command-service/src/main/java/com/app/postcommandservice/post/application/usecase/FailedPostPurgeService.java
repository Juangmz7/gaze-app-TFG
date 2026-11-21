package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository;
import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository.PurgeCursor;
import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository.PurgeablePost;
import com.app.postcommandservice.post.infrastructure.config.FailedPostPurgeProperties;

/**
 * Scheduled purge of failed posts (task 60). Every run hard-deletes the
 * {@code MEDIA_UPLOAD_FAILED} posts whose blobs the media cleanup job (task 58) purged more than
 * {@code posts.failed-purge.retention} ago, through {@link DeleteUnacceptedPostUseCase} (one
 * transaction per post, which also deletes an orphaned collab and publishes
 * {@code CollabDeletedEvent}).
 *
 * <p>Same layout as {@link PostMediaCleanupService}: {@link #run()} is not transactional, reads
 * keyset pages in a read-only transaction through {@code self} (which provides this bean's
 * transactional proxy), and work per run is bounded by {@code batch-size *
 * max-batches-per-run}. A failure on one post is logged and skipped; it never aborts the run and the next run retries it. ShedLock
 * makes sure a single instance runs each interval.</p>
 */
@Slf4j
@Service
public class FailedPostPurgeService {

    static final String LOCK_NAME = "failedPostPurgeJob";

    private final FailedPostPurgeRepository failedPostPurgeRepository;
    private final DeleteUnacceptedPostUseCase deleteUnacceptedPostUseCase;
    private final FailedPostPurgeProperties failedPostPurgeProperties;
    private final ObjectProvider<FailedPostPurgeService> self;

    public FailedPostPurgeService(
            FailedPostPurgeRepository failedPostPurgeRepository,
            DeleteUnacceptedPostUseCase deleteUnacceptedPostUseCase,
            FailedPostPurgeProperties failedPostPurgeProperties,
            ObjectProvider<FailedPostPurgeService> self) {
        this.failedPostPurgeRepository = failedPostPurgeRepository;
        this.deleteUnacceptedPostUseCase = deleteUnacceptedPostUseCase;
        this.failedPostPurgeProperties = failedPostPurgeProperties;
        this.self = self;
    }

    @Scheduled(cron = "${posts.failed-purge.cron:0 45 * * * *}")
    @SchedulerLock(
            name = LOCK_NAME,
            lockAtMostFor = "${posts.failed-purge.lock-at-most-for:PT55M}",
            lockAtLeastFor = "${posts.failed-purge.lock-at-least-for:PT5M}")
    public void run() {
        Instant purgedBefore = Instant.now().minus(failedPostPurgeProperties.getRetention());
        int batchSize = failedPostPurgeProperties.getBatchSize();
        int maxBatches = failedPostPurgeProperties.getMaxBatchesPerRun();
        RunSummary summary = new RunSummary();

        PurgeCursor cursor = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<PurgeablePost> page = self.getObject().findPurgeablePosts(purgedBefore, cursor, batchSize);
            for (PurgeablePost post : page) {
                purgeSafely(post, summary);
            }
            if (page.size() < batchSize) {
                break;
            }
            // Advance past the whole page, failed posts included: they are retried by the next run
            cursor = page.getLast().cursor();
        }

        log.info("Failed post purge run finished: postsDeleted={}, collabsDeleted={}, postsSkipped={}, failures={}",
                summary.postsDeleted, summary.collabsDeleted, summary.skipped, summary.failures);
    }

    @Transactional(readOnly = true)
    public List<PurgeablePost> findPurgeablePosts(Instant purgedBefore, PurgeCursor after, int limit) {
        return failedPostPurgeRepository.findPurgeablePosts(purgedBefore, after, limit);
    }

    private void purgeSafely(PurgeablePost post, RunSummary summary) {
        try {
            var outcome = deleteUnacceptedPostUseCase.delete(post.postId());
            if (!outcome.postDeleted()) {
                summary.skipped++;
                log.info("Skipping purge of post {}: it no longer exists or is no longer MEDIA_UPLOAD_FAILED",
                        post.postId());
                return;
            }
            summary.postsDeleted++;
            if (outcome.collabDeleted()) {
                summary.collabsDeleted++;
            }
        } catch (RuntimeException exception) {
            summary.failures++;
            log.warn("Failed post purge failed for post {}; the next run retries it", post.postId(), exception);
        }
    }

    private static final class RunSummary {
        private int postsDeleted;
        private int collabsDeleted;
        private int skipped;
        private int failures;
    }
}
