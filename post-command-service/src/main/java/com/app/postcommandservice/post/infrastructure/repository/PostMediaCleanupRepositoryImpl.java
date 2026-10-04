package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

@Repository
@RequiredArgsConstructor
public class PostMediaCleanupRepositoryImpl implements PostMediaCleanupRepository {

    private static final List<PostStatus> EXPIRABLE_STATUSES = List.of(PostStatus.PENDING, PostStatus.MEDIA_UPLOAD_FAILED);

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;

    @Override
    public List<ExpiredPost> findExpiredBatch(Instant expiryThreshold, KeysetCursor cursor, int batchSize) {
        Instant cursorCreatedAt = cursor == null ? null : cursor.createdAt();
        UUID cursorId = cursor == null ? null : cursor.id();

        List<PostMediaCleanupPostView> page = postJpaRepository.findExpiredCleanupBatch(
                EXPIRABLE_STATUSES, expiryThreshold, cursorCreatedAt, cursorId, PageRequest.ofSize(batchSize));

        return page.stream()
                .map(view -> new ExpiredPost(
                        view.getId(),
                        view.getStatus(),
                        view.getCreatedAt(),
                        postMediaJpaRepository.findConfirmMediaUploadViewsByPostId(view.getId()).stream()
                                .map(mediaView -> new MediaBlobRef(
                                        mediaView.getId(), mediaView.getMediaType(), mediaView.getUrl(), mediaView.getThumbnailUrl()))
                                .toList()))
                .toList();
    }

    @Override
    public int transitionPendingToFailed(UUID postId) {
        return postJpaRepository.updateStatusIfCurrent(postId, PostStatus.PENDING, PostStatus.MEDIA_UPLOAD_FAILED);
    }

    @Override
    public int markMediaPurged(UUID postId, Instant purgedAt) {
        return postJpaRepository.markMediaPurgedIfUnpurged(postId, purgedAt);
    }
}
