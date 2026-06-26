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

    @Modifying
    @Query(
            value = """
                    UPDATE follows SET status = 'ACTIVE', updated_at = NOW()
                    WHERE follower_id = :followerId AND followed_id = :followedId AND status = 'REMOVED'
                    """,
            nativeQuery = true
    )
    int reactivateIfRemoved(UUID followerId, UUID followedId);

    @Modifying
    @Query(
            value = """
                    UPDATE follows SET status = 'REMOVED', updated_at = NOW()
                    WHERE follower_id = :followerId AND followed_id = :followedId AND status = 'ACTIVE'
                    """,
            nativeQuery = true
    )
    int markAsRemovedIfActive(UUID followerId, UUID followedId);

    @Modifying
    @Query(
            value = """
                    UPDATE follows SET status = 'BLOCKED', updated_at = NOW()
                    WHERE ((follower_id = :userId1 AND followed_id = :userId2)
                    OR (follower_id = :userId2 AND followed_id = :userId1))
                    AND status = 'ACTIVE'
                    """,
            nativeQuery = true
    )
    int markBidirectionalAsBlocked(UUID userId1, UUID userId2);

    @Modifying
    @Query(
            value = """
                    UPDATE follows SET status = 'REMOVED', updated_at = NOW()
                    WHERE ((follower_id = :userId1 AND followed_id = :userId2)
                    OR (follower_id = :userId2 AND followed_id = :userId1))
                    AND status = 'BLOCKED'
                    """,
            nativeQuery = true
    )
    int markBidirectionalAsRemoved(UUID userId1, UUID userId2);
}
