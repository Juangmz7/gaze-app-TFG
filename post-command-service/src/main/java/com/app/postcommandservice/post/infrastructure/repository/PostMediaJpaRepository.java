package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

public interface PostMediaJpaRepository extends JpaRepository<PostMediaEntity, UUID> {
    Optional<PostMediaEntity> findByIdAndPostId(UUID id, UUID postId);
}
