package com.app.postcommandservice.share.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.share.infrastructure.entity.PostShareEntity;
import com.app.postcommandservice.share.infrastructure.entity.PostShareId;

public interface PostShareJpaRepository extends JpaRepository<PostShareEntity, PostShareId> {
}
