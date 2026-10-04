package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
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

    @Query("""
            SELECT m.id AS id, m.url AS url, m.thumbnailUrl AS thumbnailUrl,
                   m.mediaType AS mediaType, m.mediaOrder AS mediaOrder,
                   m.uploadSasHash AS uploadSasHash, m.uploadSasExpiresAt AS uploadSasExpiresAt,
                   m.thumbnailSasHash AS thumbnailSasHash, m.thumbnailSasExpiresAt AS thumbnailSasExpiresAt
            FROM PostMediaEntity m
            WHERE m.post.id = :postId
            ORDER BY m.mediaOrder ASC
            """)
    List<PostMediaRefreshUploadUrlsView> findRefreshUploadUrlsViewsByPostId(@Param("postId") UUID postId);

    /**
     * Persists a freshly signed upload SAS hash + expiry for {@code mediaId}'s content blob
     * (task 38). The SAS itself is never persisted, only its BCrypt hash.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PostMediaEntity m
            SET m.uploadSasHash = :hash, m.uploadSasExpiresAt = :expiresAt
            WHERE m.id = :mediaId
            """)
    int updateUploadSas(@Param("mediaId") UUID mediaId, @Param("hash") String hash, @Param("expiresAt") Instant expiresAt);

    /**
     * Persists a freshly signed upload SAS hash + expiry for {@code mediaId}'s thumbnail blob
     * (task 38). Only ever invoked for {@code VIDEO} media.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PostMediaEntity m
            SET m.thumbnailSasHash = :hash, m.thumbnailSasExpiresAt = :expiresAt
            WHERE m.id = :mediaId
            """)
    int updateThumbnailSas(@Param("mediaId") UUID mediaId, @Param("hash") String hash, @Param("expiresAt") Instant expiresAt);
}
