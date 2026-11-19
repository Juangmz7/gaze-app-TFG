package com.app.postcommandservice.view.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.view.infrastructure.entity.PostViewEntity;

public interface PostViewJpaRepository extends JpaRepository<PostViewEntity, UUID> {

    long countByPostIdAndUserId(UUID postId, UUID userId);

    /**
     * Plain insert on the primary key: an existing id is left untouched (no merge/upsert) and reported as
     * 0 affected rows. A concurrent uncommitted insert of the same id makes this statement wait for it,
     * so exactly one delivery of a viewId can ever get 1.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO post_views (id, post_id, user_id, source, exit_reason, feed_position, "
            + "duration_ms, time_watched_ms, completion_percent, replay_count, server_timestamp) "
            + "VALUES (:id, :postId, :userId, :source, :exitReason, :feedPosition, "
            + ":durationMs, :timeWatchedMs, :completionPercent, :replayCount, :serverTimestamp) "
            + "ON CONFLICT (id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("postId") UUID postId,
                       @Param("userId") UUID userId,
                       @Param("source") String source,
                       @Param("exitReason") String exitReason,
                       @Param("feedPosition") int feedPosition,
                       @Param("durationMs") int durationMs,
                       @Param("timeWatchedMs") int timeWatchedMs,
                       @Param("completionPercent") int completionPercent,
                       @Param("replayCount") int replayCount,
                       @Param("serverTimestamp") Instant serverTimestamp);
}
