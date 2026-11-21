package com.app.postcommandservice.report.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.report.infrastructure.entity.PostReportEntity;

public interface PostReportJpaRepository extends JpaRepository<PostReportEntity, UUID> {

    Optional<PostReportEntity> findFirstByReporterUserIdAndPostIdAndStatus(
            UUID reporterUserId, UUID postId, ReportStatus status);

    /*
     * The only unique constraint a fresh random id can hit is the partial
     * uk_post_reports_open_reporter_post index, so DO NOTHING means "an OPEN report of this
     * reporter on this post already exists". It waits for a concurrent insert to commit.
     */
    @Modifying
    @Query(value = """
            INSERT INTO post_reports
                (id, post_id, reporter_user_id, reason, details, snapshot, status, created_at)
            VALUES
                (:id, :postId, :reporterUserId, :reason, CAST(:details AS VARCHAR(1000)),
                 CAST(:snapshot AS JSONB), :status, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfNoOpenReport(
            @Param("id") UUID id,
            @Param("postId") UUID postId,
            @Param("reporterUserId") UUID reporterUserId,
            @Param("reason") String reason,
            @Param("details") String details,
            @Param("snapshot") String snapshot,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt);

    @Query(value = """
            SELECT * FROM post_reports
            WHERE status = :status
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PostReportEntity> findFirstPage(@Param("status") String status, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM post_reports
            WHERE status = :status
              AND (created_at, id) < (:createdAt, :id)
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PostReportEntity> findPageAfter(
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("id") UUID id,
            @Param("limit") int limit);
}
