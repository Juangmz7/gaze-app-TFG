package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.comment.infrastructure.entity.CommentEntity;

public interface CommentJpaRepository extends JpaRepository<CommentEntity, UUID> {

    Optional<CommentEntity> findByIdAndPostId(UUID id, UUID postId);
}
