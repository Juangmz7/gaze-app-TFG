package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyEntity;

public interface PostRequestIdempotencyJpaRepository extends JpaRepository<PostRequestIdempotencyEntity, UUID> {
}
