package com.app.postcommandservice.like.application.repository;

import java.util.UUID;

import com.app.postcommandservice.like.domain.model.PostLike;

public interface PostLikeRepository {

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    PostLike save(PostLike postLike);

    void deleteByPostIdAndUserId(UUID postId, UUID userId);
}
