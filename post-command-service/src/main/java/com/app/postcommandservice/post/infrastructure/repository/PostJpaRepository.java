package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
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
     * First page of the keyset-paginated selection for the scheduled media-cleanup job
     * (task 39): posts whose upload was never confirmed in time (still {@code PENDING}) or
     * whose upload was already rejected ({@code MEDIA_UPLOAD_FAILED}), older than the upload
     * window, not yet purged. {@code pageable} must only ever be used for its page size
     * ({@code PageRequest.ofSize(batchSize)}), never for its page number. A separate method
     * from {@link #findExpiredCleanupNextPage} (rather than one query with a nullable cursor)
     * because binding a {@code NULL} parameter used only inside an {@code IS NULL} check with
     * no concrete column on either side leaves Postgres unable to infer that parameter's type
     * once the statement is server-side prepared.
     */
    @Query("""
            SELECT post.id AS id, post.status AS status, post.createdAt AS createdAt
            FROM PostEntity post
            WHERE post.status IN :statuses
              AND post.createdAt < :threshold
              AND post.mediaPurgedAt IS NULL
            ORDER BY post.createdAt ASC, post.id ASC
            """)
    List<PostMediaCleanupPostView> findExpiredCleanupFirstPage(
            @Param("statuses") List<PostStatus> statuses,
            @Param("threshold") Instant threshold,
            Pageable pageable);

    /**
     * Subsequent page of the same selection as {@link #findExpiredCleanupFirstPage}, starting
     * strictly after {@code (cursorCreatedAt, cursorId)} in {@code (createdAt, id)} order.
     * Passing the previous page's last row as the cursor (rather than an {@code OFFSET}) keeps
     * each page's cost independent of how many pages came before, and keeps advancing past a
     * post even if its own processing later fails, so one bad post cannot stall the batch on
     * the next page.
     */
    @Query("""
            SELECT post.id AS id, post.status AS status, post.createdAt AS createdAt
            FROM PostEntity post
            WHERE post.status IN :statuses
              AND post.createdAt < :threshold
              AND post.mediaPurgedAt IS NULL
              AND (post.createdAt > :cursorCreatedAt
                   OR (post.createdAt = :cursorCreatedAt AND post.id > :cursorId))
            ORDER BY post.createdAt ASC, post.id ASC
            """)
    List<PostMediaCleanupPostView> findExpiredCleanupNextPage(
            @Param("statuses") List<PostStatus> statuses,
            @Param("threshold") Instant threshold,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Marks {@code postId} as fully purged (its media blobs already deleted) only if it had
     * not already been marked, used by the media-cleanup job (task 39) after deleting a post's
     * blobs so a duplicate/overlapping run never double-counts or re-deletes them.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PostEntity post
            SET post.mediaPurgedAt = :purgedAt
            WHERE post.id = :postId
              AND post.mediaPurgedAt IS NULL
            """)
    int markMediaPurgedIfUnpurged(@Param("postId") UUID postId, @Param("purgedAt") Instant purgedAt);
}
