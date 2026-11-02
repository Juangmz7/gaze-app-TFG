package com.app.postcommandservice.post.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyId;

public interface PostRequestIdempotencyJpaRepository
        extends JpaRepository<PostRequestIdempotencyEntity, PostRequestIdempotencyId> {
}
