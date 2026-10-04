package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

@Repository
@RequiredArgsConstructor
public class PostMediaVerificationRepositoryImpl implements PostMediaVerificationRepository {

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;

    @Override
    public Optional<PostSnapshot> findSnapshot(UUID postId) {
        return postJpaRepository.findStatusById(postId)
                .map(status -> new PostSnapshot(
                        postId,
                        status,
                        postMediaJpaRepository.findConfirmMediaUploadViewsByPostId(postId).stream()
                                .map(mediaView -> new MediaSnapshot(mediaView.getId(), mediaView.getMediaType()))
                                .toList()));
    }

    @Override
    public int updateStatusIfPending(UUID postId, PostStatus newStatus) {
        return postJpaRepository.updateStatusIfCurrent(postId, PostStatus.PENDING, newStatus);
    }

    @Override
    public void persistVideoDurationsMillis(Map<UUID, Integer> durationsMillis) {
        durationsMillis.forEach(postMediaJpaRepository::updateDuration);
    }
}
