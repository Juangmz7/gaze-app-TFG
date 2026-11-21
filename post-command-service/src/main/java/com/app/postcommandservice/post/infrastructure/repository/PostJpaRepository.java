package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;

public interface PostJpaRepository extends JpaRepository<PostEntity, UUID> {

    Optional<PostEntity> findFirstByCollabId(UUID collabId);

    @Query("""
            SELECT post.userId
            FROM PostEntity post
            WHERE post.id = :postId
              AND post.status = :status
            """)
    Optional<UUID> findOwnerIdByIdAndStatus(@Param("postId") UUID postId, @Param("status") PostStatus status);

    @Query("""
            SELECT post.id AS id, post.userId AS authorId, post.status AS status, post.createdAt AS createdAt
            FROM PostEntity post
            WHERE post.id = :postId
            """)
    Optional<PostConfirmMediaUploadView> findConfirmMediaUploadViewById(@Param("postId") UUID postId);

    @Query("""
            SELECT post.status
            FROM PostEntity post
            WHERE post.id = :postId
            """)
    Optional<PostStatus> findStatusById(@Param("postId") UUID postId);

    /**
     * Conditionally transitions a post's status, used by the media-upload verification flow
     * (task 37) so a duplicate/late message, or one that races with task 39's expiry cleanup,
     * affects zero rows instead of overwriting a status set elsewhere.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PostEntity post
            SET post.status = :newStatus
            WHERE post.id = :postId
              AND post.status = :expectedStatus
            """)
    int updateStatusIfCurrent(
            @Param("postId") UUID postId,
            @Param("expectedStatus") PostStatus expectedStatus,
            @Param("newStatus") PostStatus newStatus);

    /**
     * First keyset page of the media cleanup job (task 58). Split from
     * {@link #findMediaCleanupPageAfter} so no parameter is ever bound only inside an
     * {@code IS NULL} check, where Postgres cannot infer its type.
     */
    @Query("""
            SELECT post.id AS id, post.status AS status, post.createdAt AS createdAt
            FROM PostEntity post
            WHERE post.status IN :statuses
              AND post.createdAt < :createdBefore
              AND post.mediaPurgedAt IS NULL
            ORDER BY post.createdAt ASC, post.id ASC
            """)
    List<PostMediaCleanupView> findMediaCleanupPage(
            @Param("statuses") Collection<PostStatus> statuses,
            @Param("createdBefore") Instant createdBefore,
            Limit limit);

    /** Next keyset page of the media cleanup job, strictly after {@code (afterCreatedAt, afterId)}. */
    @Query("""
            SELECT post.id AS id, post.status AS status, post.createdAt AS createdAt
            FROM PostEntity post
            WHERE post.status IN :statuses
              AND post.createdAt < :createdBefore
              AND post.mediaPurgedAt IS NULL
              AND (post.createdAt > :afterCreatedAt
                   OR (post.createdAt = :afterCreatedAt AND post.id > :afterId))
            ORDER BY post.createdAt ASC, post.id ASC
            """)
    List<PostMediaCleanupView> findMediaCleanupPageAfter(
            @Param("statuses") Collection<PostStatus> statuses,
            @Param("createdBefore") Instant createdBefore,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterId") UUID afterId,
            Limit limit);

    /**
     * Records that the media cleanup job deleted the blobs of a {@code MEDIA_UPLOAD_FAILED} post.
     * The status guard makes sure an {@code ACCEPTED} post is never marked as purged.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PostEntity post
            SET post.mediaPurgedAt = :purgedAt
            WHERE post.id = :postId
              AND post.status = :status
              AND post.mediaPurgedAt IS NULL
            """)
    int markMediaPurged(
            @Param("postId") UUID postId,
            @Param("status") PostStatus status,
            @Param("purgedAt") Instant purgedAt);

    @Query("""
            SELECT post.id AS id, post.userId AS userId, post.collabId AS collabId,
                   post.status AS status, post.mediaPurgedAt AS mediaPurgedAt
            FROM PostEntity post
            WHERE post.id = :postId
            """)
    Optional<PostDeletionView> findDeletionViewById(@Param("postId") UUID postId);

    /**
     * Hard-deletes a post that never reached {@code ACCEPTED} (task 60). The status guard is the
     * race arbiter against media verification: 0 rows means the post was accepted (or removed)
     * meanwhile. Hibernate clears the {@code post_tags} collection table first; {@code post_media}
     * and its tagged users go by {@code ON DELETE CASCADE}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM PostEntity post
            WHERE post.id = :postId
              AND post.status IN :statuses
            """)
    int deleteByIdAndStatusIn(@Param("postId") UUID postId, @Param("statuses") Collection<PostStatus> statuses);

    boolean existsByCollabId(UUID collabId);

    /**
     * First keyset page of the failed-post purge job (task 60): {@code MEDIA_UPLOAD_FAILED} posts
     * whose blobs were purged before {@code purgedBefore}, ordered by {@code (mediaPurgedAt, id)}.
     */
    @Query("""
            SELECT post.id AS id, post.mediaPurgedAt AS mediaPurgedAt
            FROM PostEntity post
            WHERE post.status = :status
              AND post.mediaPurgedAt < :purgedBefore
            ORDER BY post.mediaPurgedAt ASC, post.id ASC
            """)
    List<PostFailedPurgeView> findFailedPurgePage(
            @Param("status") PostStatus status,
            @Param("purgedBefore") Instant purgedBefore,
            Limit limit);

    /** Next keyset page of the failed-post purge job, strictly after {@code (afterPurgedAt, afterId)}. */
    @Query("""
            SELECT post.id AS id, post.mediaPurgedAt AS mediaPurgedAt
            FROM PostEntity post
            WHERE post.status = :status
              AND post.mediaPurgedAt < :purgedBefore
              AND (post.mediaPurgedAt > :afterPurgedAt
                   OR (post.mediaPurgedAt = :afterPurgedAt AND post.id > :afterId))
            ORDER BY post.mediaPurgedAt ASC, post.id ASC
            """)
    List<PostFailedPurgeView> findFailedPurgePageAfter(
            @Param("status") PostStatus status,
            @Param("purgedBefore") Instant purgedBefore,
            @Param("afterPurgedAt") Instant afterPurgedAt,
            @Param("afterId") UUID afterId,
            Limit limit);
}
