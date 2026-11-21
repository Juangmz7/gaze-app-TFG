package com.app.postcommandservice.report.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.CommentReportSnapshot;
import com.app.postcommandservice.report.domain.model.PostReport;
import com.app.postcommandservice.report.domain.model.PostReportSnapshot;
import com.app.postcommandservice.report.domain.model.ReportClaim;
import com.app.postcommandservice.report.domain.model.valueobj.ReportDetails;
import com.app.postcommandservice.report.infrastructure.entity.CommentReportEntity;
import com.app.postcommandservice.report.infrastructure.entity.CommentReportSnapshotPayload;
import com.app.postcommandservice.report.infrastructure.entity.PostReportEntity;
import com.app.postcommandservice.report.infrastructure.entity.PostReportSnapshotPayload;

@Component
public class ReportMapper {

    public PostReport toDomain(PostReportEntity entity) {
        var payload = entity.getSnapshot();
        return new PostReport(
                entity.getId(),
                entity.getPostId(),
                new ReportClaim(entity.getReporterUserId(), entity.getReason(), new ReportDetails(entity.getDetails())),
                new PostReportSnapshot(payload.description(), payload.mediaUrls(), payload.authorUserId()),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    public CommentReport toDomain(CommentReportEntity entity) {
        var payload = entity.getSnapshot();
        return new CommentReport(
                entity.getId(),
                entity.getPostId(),
                entity.getCommentId(),
                new ReportClaim(entity.getReporterUserId(), entity.getReason(), new ReportDetails(entity.getDetails())),
                new CommentReportSnapshot(payload.content(), payload.authorUserId(), payload.postId()),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    public PostReportSnapshotPayload toPayload(PostReportSnapshot snapshot) {
        return new PostReportSnapshotPayload(snapshot.description(), snapshot.mediaUrls(), snapshot.authorUserId());
    }

    public CommentReportSnapshotPayload toPayload(CommentReportSnapshot snapshot) {
        return new CommentReportSnapshotPayload(snapshot.content(), snapshot.authorUserId(), snapshot.postId());
    }
}
