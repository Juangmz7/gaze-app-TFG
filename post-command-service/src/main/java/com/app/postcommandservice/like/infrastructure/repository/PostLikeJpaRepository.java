package com.app.postcommandservice.like.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.like.infrastructure.entity.PostLikeEntity;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeId;

public interface PostLikeJpaRepository extends JpaRepository<PostLikeEntity, PostLikeId> {
}
