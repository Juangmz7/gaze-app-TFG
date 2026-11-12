package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;

import com.app.socialservice.user.infrastructure.entity.UserStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaUserStatsRepository extends JpaRepository<UserStatsEntity, UUID> {

    /**
     * One atomic statement: the row lock it takes serializes concurrent changes to the
     * same user, so no read-modify-write and no explicit lock are needed.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO user_stats (user_id, post_count)
                    VALUES (:userId, 1)
                    ON CONFLICT (user_id) DO UPDATE SET post_count = user_stats.post_count + 1
                    """,
            nativeQuery = true
    )
    int incrementPostCount(@Param("userId") UUID userId);

    /** Never below zero: a delete whose create was never counted must not go negative. */
    @Modifying
    @Query(
            value = """
                    INSERT INTO user_stats (user_id, post_count)
                    VALUES (:userId, 0)
                    ON CONFLICT (user_id) DO UPDATE SET post_count = GREATEST(user_stats.post_count - 1, 0)
                    """,
            nativeQuery = true
    )
    int decrementPostCount(@Param("userId") UUID userId);

    /** For the one-off copy of the counters Redis held: keeps a value already written here. */
    @Modifying
    @Query(
            value = """
                    INSERT INTO user_stats (user_id, post_count)
                    VALUES (:userId, :postCount)
                    ON CONFLICT (user_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertPostCountIfAbsent(@Param("userId") UUID userId, @Param("postCount") long postCount);

    @Query(
            value = """
                    SELECT (SELECT count(*) FROM follows f
                             WHERE f.followed_id = :userId AND f.status = 'ACTIVE') AS "followersCount",
                           (SELECT count(*) FROM follows f
                             WHERE f.follower_id = :userId AND f.status = 'ACTIVE') AS "followingCount",
                           COALESCE((SELECT s.post_count FROM user_stats s
                                      WHERE s.user_id = :userId), 0) AS "postCount"
                    """,
            nativeQuery = true
    )
    UserStatsProjection findStats(@Param("userId") UUID userId);

    interface UserStatsProjection {
        long getFollowersCount();

        long getFollowingCount();

        long getPostCount();
    }
}
