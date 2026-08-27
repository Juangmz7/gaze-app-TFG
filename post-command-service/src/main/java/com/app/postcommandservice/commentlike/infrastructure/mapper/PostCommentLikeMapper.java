package com.app.postcommandservice.commentlike.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeContext;
import com.app.postcommandservice.commentlike.domain.model.PostCommentLike;
import com.app.postcommandservice.commentlike.infrastructure.entity.PostCommentLikeEntity;
import com.app.postcommandservice.commentlike.infrastructure.entity.PostCommentLikeId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class PostCommentLikeMapper {

    public PostCommentLikeEntity toEntity(PostCommentLike commentLike) {
        return PostCommentLikeEntity.builder()
                .id(new PostCommentLikeId(commentLike.getCommentId().value(), commentLike.getUserId().value()))
                .source(commentLike.getContext().source())
                .feedPosition(commentLike.getContext().feedPosition())
                .createdAt(commentLike.getCreatedAt())
                .build();
    }

    public PostCommentLike toDomain(PostCommentLikeEntity entity) {
        return new PostCommentLike(
                new CommentId(entity.getId().getCommentId()),
                new UserId(entity.getId().getUserId()),
                new CommentLikeContext(entity.getSource(), entity.getFeedPosition()),
                entity.getCreatedAt()
        );
    }
}
