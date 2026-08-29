package com.app.postcommandservice.commentlike.infrastructure.repository;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeRepository;
import com.app.postcommandservice.commentlike.domain.model.PostCommentLike;
import com.app.postcommandservice.commentlike.infrastructure.entity.PostCommentLikeId;
import com.app.postcommandservice.commentlike.infrastructure.mapper.PostCommentLikeMapper;

@Repository
@RequiredArgsConstructor
public class PostCommentLikeRepositoryImpl implements PostCommentLikeRepository {

    private final CommentLikeJpaRepository commentLikeJpaRepository;
    private final PostCommentLikeMapper postCommentLikeMapper;

    @Override
    public boolean existsByCommentIdAndUserId(UUID commentId, UUID userId) {
        return commentLikeJpaRepository.existsById(new PostCommentLikeId(commentId, userId));
    }

    @Override
    public PostCommentLike save(PostCommentLike commentLike) {
        return postCommentLikeMapper.toDomain(commentLikeJpaRepository.save(postCommentLikeMapper.toEntity(commentLike)));
    }

    @Override
    public void deleteByCommentIdAndUserId(UUID commentId, UUID userId) {
        commentLikeJpaRepository.deleteById(new PostCommentLikeId(commentId, userId));
    }
}
