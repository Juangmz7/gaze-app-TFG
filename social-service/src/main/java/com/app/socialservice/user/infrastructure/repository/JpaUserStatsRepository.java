package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.infrastructure.entity.UserStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaUserStatsRepository extends JpaRepository<UserStatsEntity, UUID> {
}
