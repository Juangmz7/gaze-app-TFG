package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostMediaCleanupRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

@Repository
@RequiredArgsConstructor
public class PostMediaCleanupRepositoryImpl implements PostMediaCleanupRepository {

    private static final List<PostStatus> CLEANUP_STATUSES = List.of(PostStatus.PENDING, PostStatus.MEDIA_UPLOAD_FAILED);

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;

    @Override
    public List<ExpiredUpload> findExpiredUploads(Instant createdBefore, CleanupCursor after, int limit) {
        Objects.requireNonNull(createdBefore, "createdBefore must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }

        List<PostMediaCleanupView> posts = after == null
                ? postJpaRepository.findMediaCleanupPage(CLEANUP_STATUSES, createdBefore, Limit.of(limit))
                : postJpaRepository.findMediaCleanupPageAfter(
                        CLEANUP_STATUSES, createdBefore, after.createdAt(), after.postId(), Limit.of(limit));
        if (posts.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<MediaBlobs>> mediaByPostId = new HashMap<>();
        postMediaJpaRepository.findBlobViewsByPostIds(posts.stream().map(PostMediaCleanupView::getId).toList())
                .forEach(media -> mediaByPostId
                        .computeIfAbsent(media.getPostId(), postId -> new ArrayList<>())
                        .add(new MediaBlobs(media.getUrl(), media.getThumbnailUrl())));

        return posts.stream()
                .map(post -> new ExpiredUpload(
                        post.getId(),
                        post.getStatus(),
                        post.getCreatedAt(),
                        mediaByPostId.getOrDefault(post.getId(), List.of())))
                .toList();
    }

    @Override
    public boolean failIfPending(UUID postId) {
        Objects.requireNonNull(postId, "postId must not be null");
        return postJpaRepository.updateStatusIfCurrent(postId, PostStatus.PENDING, PostStatus.MEDIA_UPLOAD_FAILED) == 1;
    }

    @Override
    public boolean markMediaPurged(UUID postId, Instant purgedAt) {
        Objects.requireNonNull(postId, "postId must not be null");
        Objects.requireNonNull(purgedAt, "purgedAt must not be null");
        return postJpaRepository.markMediaPurged(postId, PostStatus.MEDIA_UPLOAD_FAILED, purgedAt) == 1;
    }
}
