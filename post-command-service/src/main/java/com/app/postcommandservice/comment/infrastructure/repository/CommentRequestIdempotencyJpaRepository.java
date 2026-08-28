package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.comment.infrastructure.entity.CommentRequestIdempotencyEntity;

public interface CommentRequestIdempotencyJpaRepository extends JpaRepository<CommentRequestIdempotencyEntity, UUID> {
}
