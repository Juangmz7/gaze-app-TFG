package com.app.postcommandservice.commentlike.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.commentlike.infrastructure.entity.PostCommentLikeEntity;
import com.app.postcommandservice.commentlike.infrastructure.entity.PostCommentLikeId;

public interface CommentLikeJpaRepository extends JpaRepository<PostCommentLikeEntity, PostCommentLikeId> {
}
