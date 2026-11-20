package com.app.postcommandservice.post.infrastructure.repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.collab.infrastructure.repository.CollabJpaRepository;
import com.app.postcommandservice.collab.infrastructure.repository.CollabMemberJpaRepository;
import com.app.postcommandservice.collab.infrastructure.repository.CollabRequestIdempotencyJpaRepository;
import com.app.postcommandservice.post.application.repository.UnacceptedPostDeletionRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

@Repository
@RequiredArgsConstructor
public class UnacceptedPostDeletionRepositoryImpl implements UnacceptedPostDeletionRepository {

    private static final List<PostStatus> UNACCEPTED_STATUSES = List.of(PostStatus.PENDING, PostStatus.MEDIA_UPLOAD_FAILED);

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;
    private final PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;
    private final CollabJpaRepository collabJpaRepository;
    private final CollabMemberJpaRepository collabMemberJpaRepository;
    private final CollabRequestIdempotencyJpaRepository collabRequestIdempotencyJpaRepository;

    @Override
    public Optional<PostDeletionSnapshot> findSnapshot(UUID postId) {
        Objects.requireNonNull(postId, "postId must not be null");
        return postJpaRepository.findDeletionViewById(postId)
                .map(post -> new PostDeletionSnapshot(
                        post.getId(),
                        post.getUserId(),
                        post.getCollabId(),
                        post.getStatus(),
                        post.getMediaPurgedAt() == null ? findBlobUrls(postId) : List.of()));
    }

    @Override
    public boolean deleteIfUnaccepted(UUID postId) {
        Objects.requireNonNull(postId, "postId must not be null");
        return postJpaRepository.deleteByIdAndStatusIn(postId, UNACCEPTED_STATUSES) == 1;
    }

    @Override
    public void deleteRequestIdempotency(UUID postId) {
        Objects.requireNonNull(postId, "postId must not be null");
        postRequestIdempotencyJpaRepository.deleteByPostId(postId);
        collabRequestIdempotencyJpaRepository.deleteByEntityId(postId);
    }

    @Override
    public boolean collabHasPosts(UUID collabId) {
        Objects.requireNonNull(collabId, "collabId must not be null");
        return postJpaRepository.existsByCollabId(collabId);
    }

    @Override
    public void deleteCollab(UUID collabId) {
        Objects.requireNonNull(collabId, "collabId must not be null");
        collabMemberJpaRepository.deleteAllByCollabId(collabId);
        collabRequestIdempotencyJpaRepository.deleteByEntityId(collabId);
        collabJpaRepository.hardDeleteById(collabId);
    }

    /** Each distinct blob once: an IMAGE has the same url as url and thumbnailUrl. */
    private List<String> findBlobUrls(UUID postId) {
        Set<String> blobUrls = new LinkedHashSet<>();
        for (PostMediaBlobView media : postMediaJpaRepository.findBlobViewsByPostIds(List.of(postId))) {
            if (StringUtils.hasText(media.getUrl())) {
                blobUrls.add(media.getUrl());
            }
            if (StringUtils.hasText(media.getThumbnailUrl())) {
                blobUrls.add(media.getThumbnailUrl());
            }
        }
        return List.copyOf(blobUrls);
    }
}
