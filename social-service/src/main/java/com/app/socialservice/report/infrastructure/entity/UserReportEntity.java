package com.app.socialservice.report.infrastructure.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;

/**
 * Rows are inserted with a native INSERT ... ON CONFLICT DO NOTHING (JpaUserReportRepository).
 * The unique partial index on (reporter_user_id, reported_user_id) WHERE status = 'OPEN' cannot be
 * declared here: it lives in db/schema/social-service-prod.sql.
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(
        name = "user_reports",
        indexes = @Index(
                name = "idx_user_reports_status_created_at_id",
                columnList = "status, created_at DESC, id DESC"
        )
)
public class UserReportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reported_user_id", nullable = false, updatable = false)
    private UUID reportedUserId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private UserReportReason reason;

    @Column(length = 1000, updatable = false)
    private String details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String snapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
