package com.app.postcommandservice.view.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.view.infrastructure.entity.PostViewEntity;

public interface PostViewJpaRepository extends JpaRepository<PostViewEntity, UUID> {

    long countByPostIdAndUserId(UUID postId, UUID userId);
}
