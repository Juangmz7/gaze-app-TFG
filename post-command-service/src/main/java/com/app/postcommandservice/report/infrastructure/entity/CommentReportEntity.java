package com.app.postcommandservice.report.infrastructure.entity;

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

import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;

/**
 * Rows are inserted with a native {@code INSERT ... ON CONFLICT DO NOTHING} (see
 * {@code CommentReportJpaRepository}); the unique partial index on
 * {@code (reporter_user_id, comment_id) WHERE status = 'OPEN'} lives in the production schema.
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(
        name = "comment_reports",
        indexes = @Index(
                name = "idx_comment_reports_status_created_at_id",
                columnList = "status, created_at DESC, id DESC"
        )
)
public class CommentReportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "comment_id", nullable = false, updatable = false)
    private UUID commentId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReportReason reason;

    @Column(length = 1000, updatable = false)
    private String details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private CommentReportSnapshotPayload snapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
