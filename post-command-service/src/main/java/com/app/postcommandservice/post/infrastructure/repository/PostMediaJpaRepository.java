package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

public interface PostMediaJpaRepository extends JpaRepository<PostMediaEntity, UUID> {

    List<PostMediaEntity> findByPost_IdOrderByMediaOrderAsc(UUID postId);
}
