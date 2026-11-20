package com.app.postcommandservice.post.application.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;
import com.app.postcommandservice.post.domain.events.PostMediaBlobsReleasedEvent;

/**
 * Deletes the blobs of a hard-deleted, never-accepted post (task 60) once its transaction has
 * committed, so no storage call ever runs inside a database transaction. Best effort: a failure
 * is logged and never fails the caller (the blob may already be gone, and the media cleanup job
 * of task 58 does not see the post any more, so a leftover blob is only wasted storage).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReleasedMediaBlobDeleter {

    private final MediaBlobDeleter mediaBlobDeleter;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostMediaBlobsReleased(PostMediaBlobsReleasedEvent event) {
        int deleted = 0;
        for (String blobUrl : event.blobUrls()) {
            try {
                if (mediaBlobDeleter.deleteIfExists(blobUrl)) {
                    deleted++;
                }
            } catch (RuntimeException exception) {
                log.warn("Could not delete blob {} of deleted post {}", blobUrl, event.postId(), exception);
            }
        }
        log.info("Deleted {} of {} blobs of deleted post {}", deleted, event.blobUrls().size(), event.postId());
    }
}
