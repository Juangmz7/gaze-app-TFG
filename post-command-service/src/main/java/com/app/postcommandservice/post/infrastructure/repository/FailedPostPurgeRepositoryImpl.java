package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

@Repository
@RequiredArgsConstructor
public class FailedPostPurgeRepositoryImpl implements FailedPostPurgeRepository {

    private final PostJpaRepository postJpaRepository;

    @Override
    public List<PurgeablePost> findPurgeablePosts(Instant purgedBefore, PurgeCursor after, int limit) {
        Objects.requireNonNull(purgedBefore, "purgedBefore must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }

        List<PostFailedPurgeView> page = after == null
                ? postJpaRepository.findFailedPurgePage(PostStatus.MEDIA_UPLOAD_FAILED, purgedBefore, Limit.of(limit))
                : postJpaRepository.findFailedPurgePageAfter(
                        PostStatus.MEDIA_UPLOAD_FAILED, purgedBefore, after.mediaPurgedAt(), after.postId(), Limit.of(limit));
        return page.stream()
                .map(post -> new PurgeablePost(post.getId(), post.getMediaPurgedAt()))
                .toList();
    }
}
