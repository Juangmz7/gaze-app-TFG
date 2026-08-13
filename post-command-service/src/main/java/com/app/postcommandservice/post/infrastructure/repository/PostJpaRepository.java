package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.PostEntity;

public interface PostJpaRepository extends JpaRepository<PostEntity, UUID> {
}
