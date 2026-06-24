package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.infrastructure.entity.UserStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface JpaUserStatsRepository extends JpaRepository<UserStatsEntity, UUID> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO user_stats (user_id, follower_count, following_count, version, created_at, updated_at) "
            + "VALUES (:userId, 0, 0, 0, NOW(), NOW()) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int ensureExists(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE user_stats SET following_count = following_count + 1, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE user_id = :userId", nativeQuery = true)
    int incrementFollowingCount(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE user_stats SET follower_count = follower_count + 1, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE user_id = :userId", nativeQuery = true)
    int incrementFollowerCount(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE user_stats SET following_count = following_count - 1, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE user_id = :userId AND following_count > 0", nativeQuery = true)
    int decrementFollowingCount(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE user_stats SET follower_count = follower_count - 1, "
            + "version = version + 1, updated_at = NOW() "
            + "WHERE user_id = :userId AND follower_count > 0", nativeQuery = true)
    int decrementFollowerCount(@Param("userId") UUID userId);
}
