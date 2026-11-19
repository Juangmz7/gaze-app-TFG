package com.app.socialservice.follow.infrastructure.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaFollowRepository extends JpaRepository<FollowEntity, FollowEntityId> {

    /*
     * Keyset pages of the followers / following lists: an index seek on
     * (followed_id, created_at DESC, follower_id DESC) or (follower_id, created_at DESC, followed_id DESC),
     * equality on the list owner and then the row comparison against the cursor, in index order.
     * A page starts after the last row returned, so filtered-out rows (not ACTIVE, not ACCEPTED,
     * blocked) never shift it. The first page has its own query: an "OR no cursor" branch would
     * stop the index from serving the comparison. The block checks are primary key lookups on
     * blocks (blocked_id, blocker_id), one per direction.
     */
    String NOT_BLOCKED_WITH_VIEWER = """
              AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocked_id = u.id AND b.blocker_id = :viewerId)
              AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocked_id = :viewerId AND b.blocker_id = u.id)
            """;

    String FOLLOWERS_PAGE_SELECT = """
            SELECT u.id AS "userId", u.username AS username, u.picture_url AS "profilePic",
                   f.created_at AS "followedAt"
            FROM follows f
            JOIN users u ON u.id = f.follower_id
            WHERE f.followed_id = :listOwnerId
              AND f.status = 'ACTIVE'
              AND u.account_status = 'ACCEPTED'
            """ + NOT_BLOCKED_WITH_VIEWER;

    String FOLLOWERS_PAGE_AFTER_CURSOR = """
              AND (f.created_at, f.follower_id) < (:afterCreatedAt, :afterUserId)
            """;

    String FOLLOWERS_PAGE_ORDER = """
            ORDER BY f.created_at DESC, f.follower_id DESC
            LIMIT :fetchSize
            """;

    String FOLLOWING_PAGE_SELECT = """
            SELECT u.id AS "userId", u.username AS username, u.picture_url AS "profilePic",
                   f.created_at AS "followedAt"
            FROM follows f
            JOIN users u ON u.id = f.followed_id
            WHERE f.follower_id = :listOwnerId
              AND f.status = 'ACTIVE'
              AND u.account_status = 'ACCEPTED'
            """ + NOT_BLOCKED_WITH_VIEWER;

    String FOLLOWING_PAGE_AFTER_CURSOR = """
              AND (f.created_at, f.followed_id) < (:afterCreatedAt, :afterUserId)
            """;

    String FOLLOWING_PAGE_ORDER = """
            ORDER BY f.created_at DESC, f.followed_id DESC
            LIMIT :fetchSize
            """;

    interface FollowListRowProjection {
        UUID getUserId();

        String getUsername();

        String getProfilePic();

        Instant getFollowedAt();
    }

    @Query(value = FOLLOWERS_PAGE_SELECT + FOLLOWERS_PAGE_ORDER, nativeQuery = true)
    List<FollowListRowProjection> findFirstFollowersPage(
            @Param("listOwnerId") UUID listOwnerId,
            @Param("viewerId") UUID viewerId,
            @Param("fetchSize") int fetchSize
    );

    @Query(
            value = FOLLOWERS_PAGE_SELECT + FOLLOWERS_PAGE_AFTER_CURSOR + FOLLOWERS_PAGE_ORDER,
            nativeQuery = true
    )
    List<FollowListRowProjection> findFollowersPageAfter(
            @Param("listOwnerId") UUID listOwnerId,
            @Param("viewerId") UUID viewerId,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterUserId") UUID afterUserId,
            @Param("fetchSize") int fetchSize
    );

    @Query(value = FOLLOWING_PAGE_SELECT + FOLLOWING_PAGE_ORDER, nativeQuery = true)
    List<FollowListRowProjection> findFirstFollowingPage(
            @Param("listOwnerId") UUID listOwnerId,
            @Param("viewerId") UUID viewerId,
            @Param("fetchSize") int fetchSize
    );

    @Query(
            value = FOLLOWING_PAGE_SELECT + FOLLOWING_PAGE_AFTER_CURSOR + FOLLOWING_PAGE_ORDER,
            nativeQuery = true
    )
    List<FollowListRowProjection> findFollowingPageAfter(
            @Param("listOwnerId") UUID listOwnerId,
            @Param("viewerId") UUID viewerId,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterUserId") UUID afterUserId,
            @Param("fetchSize") int fetchSize
    );

    // Primary key (followed_id, follower_id) lookups, one query for the whole page
    @Query(
            value = """
                    SELECT f.followed_id
                    FROM follows f
                    WHERE f.follower_id = :followerId
                      AND f.followed_id IN (:candidateIds)
                      AND f.status = 'ACTIVE'
                    """,
            nativeQuery = true
    )
    List<UUID> findActivelyFollowedAmong(
            @Param("followerId") UUID followerId,
            @Param("candidateIds") Collection<UUID> candidateIds
    );

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
