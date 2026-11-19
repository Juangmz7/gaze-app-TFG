package com.app.postcommandservice.view.infrastructure.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.view.application.repository.PostViewRepository;
import com.app.postcommandservice.view.domain.model.PostView;
import com.app.postcommandservice.view.infrastructure.mapper.PostViewMapper;

@Repository
@RequiredArgsConstructor
public class PostViewRepositoryImpl implements PostViewRepository {

    private final PostViewJpaRepository postViewJpaRepository;
    private final PostViewMapper postViewMapper;

    @Override
    public long countByPostIdAndUserId(UUID postId, UUID userId) {
        return postViewJpaRepository.countByPostIdAndUserId(postId, userId);
    }

    @Override
    public Optional<PostView> findById(UUID viewId) {
        Objects.requireNonNull(viewId, "viewId must not be null");
        return postViewJpaRepository.findById(viewId).map(postViewMapper::toDomain);
    }

    @Override
    public boolean insertIfAbsent(PostView postView) {
        Objects.requireNonNull(postView, "postView must not be null");
        var inserted = postViewJpaRepository.insertIfAbsent(
                postView.getViewId(),
                postView.getPostId().value(),
                postView.getUserId().value(),
                postView.getSource().name(),
                postView.getExitReason().name(),
                postView.getFeedPosition(),
                postView.getDurationMs(),
                postView.getTimeWatchedMs(),
                postView.getCompletionPercent(),
                postView.getReplayCount(),
                postView.getServerTimestamp()
        );
        return inserted == 1;
    }
}
