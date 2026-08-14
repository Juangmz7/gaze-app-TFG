package com.app.postcommandservice.comment.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.infrastructure.entity.CommentEntity;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class CommentMapper {

    public CommentEntity toEntity(Comment comment) {
        return CommentEntity.builder()
                .id(comment.getId().value())
                .postId(comment.getPostId().value())
                .userId(comment.getUserId().value())
                .content(comment.getContent().value())
                .replyTo(comment.getReplyTo())
                .status(comment.getStatus())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .deletedAt(comment.getDeletedAt())
                .build();
    }

    public Comment toDomain(CommentEntity entity) {
        return new Comment(
                new CommentId(entity.getId()),
                new PostId(entity.getPostId()),
                new UserId(entity.getUserId()),
                new CommentContent(entity.getContent()),
                entity.getReplyTo(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
