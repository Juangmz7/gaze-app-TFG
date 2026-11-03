package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
        List<PostMediaCleanupPostView> page = cursor == null
                ? postJpaRepository.findExpiredCleanupFirstPage(
                        EXPIRABLE_STATUSES, expiryThreshold, PageRequest.ofSize(batchSize))
                : postJpaRepository.findExpiredCleanupNextPage(
                        EXPIRABLE_STATUSES, expiryThreshold, cursor.createdAt(), cursor.id(), PageRequest.ofSize(batchSize));

        if (page.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<MediaBlobRef>> mediaByPostId = postMediaJpaRepository
                .findCleanupMediaViewsByPostIds(page.stream().map(PostMediaCleanupPostView::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        PostMediaCleanupMediaView::getPostId,
                        Collectors.mapping(
                                mediaView -> new MediaBlobRef(
                                        mediaView.getId(), mediaView.getMediaType(), mediaView.getUrl(), mediaView.getThumbnailUrl()),
                                Collectors.toList())));

        return page.stream()
                .map(view -> new ExpiredPost(
                        view.getId(),
                        view.getStatus(),
                        view.getCreatedAt(),
                        List.copyOf(mediaByPostId.getOrDefault(view.getId(), List.of()))))
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
