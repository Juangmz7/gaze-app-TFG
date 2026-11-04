package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

public interface PostMediaJpaRepository extends JpaRepository<PostMediaEntity, UUID> {

    @Query("""
            SELECT m.id AS id, m.url AS url, m.thumbnailUrl AS thumbnailUrl,
                   m.mediaType AS mediaType, m.mediaOrder AS mediaOrder
            FROM PostMediaEntity m
            WHERE m.post.id = :postId
            ORDER BY m.mediaOrder ASC
            """)
    List<PostMediaConfirmView> findConfirmMediaUploadViewsByPostId(@Param("postId") UUID postId);

    /**
     * Persists the duration (milliseconds) extracted for a {@code VIDEO} media item once its
     * post's media upload has been verified (task 37). {@code IMAGE} media is never targeted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PostMediaEntity m
            SET m.duration = :durationMillis
            WHERE m.id = :mediaId
            """)
    int updateDuration(@Param("mediaId") UUID mediaId, @Param("durationMillis") Integer durationMillis);
}
