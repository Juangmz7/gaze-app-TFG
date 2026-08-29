package com.app.postcommandservice.comment.application.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.comment.domain.model.Comment;

public interface CommentRepository {

    Comment save(Comment comment);

    Comment saveAndFlush(Comment comment);

    Optional<Comment> findById(UUID commentId);

    Optional<Comment> findByIdAndPostId(UUID commentId, UUID postId);
}
