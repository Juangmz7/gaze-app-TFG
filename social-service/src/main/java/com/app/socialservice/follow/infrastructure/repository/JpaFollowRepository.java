package com.app.socialservice.follow.infrastructure.repository;

import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaFollowRepository extends JpaRepository<FollowEntity, FollowEntityId> {
}
