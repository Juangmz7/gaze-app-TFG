package com.app.socialservice.report.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.socialservice.report.infrastructure.entity.UserReportEntity;

@Repository
public interface JpaUserReportRepository extends JpaRepository<UserReportEntity, UUID> {

    /*
     * The only unique constraint a fresh random id can hit is the partial
     * uk_user_reports_open_reporter_reported index, so DO NOTHING means "this reporter already has
     * an OPEN report on this user". It waits for a concurrent insert of the same pair to commit.
     */
    String INSERT_IF_NO_OPEN_REPORT = """
            INSERT INTO user_reports
                (id, reported_user_id, reporter_user_id, reason, details, snapshot, status, created_at)
            VALUES
                (:id, :reportedUserId, :reporterUserId, :reason, CAST(:details AS VARCHAR(1000)),
                 CAST(:snapshot AS JSONB), :status, :createdAt)
            ON CONFLICT DO NOTHING
            """;

    // Served by the unique partial index, whose predicate is status = 'OPEN'
    String FIND_OPEN_RECEIPT = """
            SELECT r.id AS "reportId", r.status AS status, r.created_at AS "createdAt"
            FROM user_reports r
            WHERE r.reporter_user_id = :reporterUserId
              AND r.reported_user_id = :reportedUserId
              AND r.status = 'OPEN'
            """;

    /*
     * Keyset pages of the admin list: an index seek on (status, created_at DESC, id DESC), equality
     * on the status and then the row comparison against the cursor, in index order. The reported
     * user's current username and picture come from a primary key lookup on users per row; users
     * are only ever soft-deleted, the snapshot covers a missing row. The first page has its own
     * query: an "OR no cursor" branch would stop the index from serving the comparison.
     */
    String PAGE_SELECT = """
            SELECT r.id AS "reportId", r.reporter_user_id AS "reporterUserId",
                   r.reported_user_id AS "reportedUserId",
                   COALESCE(u.username, r.snapshot ->> 'username') AS "reportedUsername",
                   CASE WHEN u.id IS NULL THEN r.snapshot ->> 'profilePic' ELSE u.picture_url END
                       AS "reportedProfilePic",
                   r.reason AS reason, r.details AS details, CAST(r.snapshot AS TEXT) AS snapshot,
                   r.status AS status, r.created_at AS "createdAt"
            FROM user_reports r
            LEFT JOIN users u ON u.id = r.reported_user_id
            WHERE r.status = :status
            """;

    String PAGE_AFTER_CURSOR = """
              AND (r.created_at, r.id) < (:afterCreatedAt, :afterReportId)
            """;

    String PAGE_ORDER = """
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT :fetchSize
            """;

    interface UserReportReceiptProjection {
        UUID getReportId();

        String getStatus();

        Instant getCreatedAt();
    }

    interface UserReportRowProjection {
        UUID getReportId();

        UUID getReporterUserId();

        UUID getReportedUserId();

        String getReportedUsername();

        String getReportedProfilePic();

        String getReason();

        String getDetails();

        String getSnapshot();

        String getStatus();

        Instant getCreatedAt();
    }

    @Modifying
    @Query(value = INSERT_IF_NO_OPEN_REPORT, nativeQuery = true)
    int insertIfNoOpenReport(
            @Param("id") UUID id,
            @Param("reportedUserId") UUID reportedUserId,
            @Param("reporterUserId") UUID reporterUserId,
            @Param("reason") String reason,
            @Param("details") String details,
            @Param("snapshot") String snapshot,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt
    );

    @Query(value = FIND_OPEN_RECEIPT, nativeQuery = true)
    Optional<UserReportReceiptProjection> findOpenReceipt(
            @Param("reporterUserId") UUID reporterUserId,
            @Param("reportedUserId") UUID reportedUserId
    );

    @Query(value = PAGE_SELECT + PAGE_ORDER, nativeQuery = true)
    List<UserReportRowProjection> findFirstPage(
            @Param("status") String status,
            @Param("fetchSize") int fetchSize
    );

    @Query(value = PAGE_SELECT + PAGE_AFTER_CURSOR + PAGE_ORDER, nativeQuery = true)
    List<UserReportRowProjection> findPageAfter(
            @Param("status") String status,
            @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterReportId") UUID afterReportId,
            @Param("fetchSize") int fetchSize
    );
}
