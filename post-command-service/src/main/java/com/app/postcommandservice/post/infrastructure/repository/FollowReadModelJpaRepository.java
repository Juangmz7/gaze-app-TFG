package com.app.postcommandservice.post.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.app.postcommandservice.post.infrastructure.entity.FollowReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.FollowReadModelId;

public interface FollowReadModelJpaRepository extends JpaRepository<FollowReadModelEntity, FollowReadModelId> {
}
