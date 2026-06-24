package com.app.socialservice.follow.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaFollowRepository extends JpaRepository<FollowEntity, FollowEntityId> {

    @Modifying
    @Query(
            value = """
                    INSERT INTO follows (follower_id, followed_id, status, created_at, updated_at)
                    VALUES (:followerUserId, :followedUserId, :status, :createdAt, :updatedAt)
                    ON CONFLICT (follower_id, followed_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            UUID followerUserId,
            UUID followedUserId,
            String status,
            Instant createdAt,
            Instant updatedAt
    );
}
