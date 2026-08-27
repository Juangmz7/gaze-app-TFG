package com.app.postcommandservice.commentlike.application.repository;

import java.util.UUID;

import com.app.postcommandservice.commentlike.domain.model.PostCommentLike;

public interface PostCommentLikeRepository {

    boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);

    PostCommentLike save(PostCommentLike commentLike);
}
