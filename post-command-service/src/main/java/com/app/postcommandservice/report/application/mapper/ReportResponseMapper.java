package com.app.postcommandservice.report.application.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.report.application.dto.CommentReportItemResponse;
import com.app.postcommandservice.report.application.dto.CommentReportSnapshotResponse;
import com.app.postcommandservice.report.application.dto.CommentReportTargetResponse;
import com.app.postcommandservice.report.application.dto.PostReportItemResponse;
import com.app.postcommandservice.report.application.dto.PostReportSnapshotResponse;
import com.app.postcommandservice.report.application.dto.PostReportTargetResponse;
import com.app.postcommandservice.report.application.dto.ReportReceiptResponse;
import com.app.postcommandservice.report.domain.model.CommentReport;
import com.app.postcommandservice.report.domain.model.PostReport;

@Component
public class ReportResponseMapper {

    public ReportReceiptResponse toReceipt(PostReport report) {
        return new ReportReceiptResponse(report.getId(), report.getStatus(), report.getCreatedAt());
    }

    public ReportReceiptResponse toReceipt(CommentReport report) {
        return new ReportReceiptResponse(report.getId(), report.getStatus(), report.getCreatedAt());
    }

    public PostReportItemResponse toItem(PostReport report) {
        var snapshot = report.getSnapshot();
        return new PostReportItemResponse(
                report.getId(),
                new PostReportTargetResponse(report.getPostId()),
                report.getClaim().reporterUserId(),
                report.getClaim().reason(),
                report.getClaim().details().value(),
                new PostReportSnapshotResponse(snapshot.description(), snapshot.mediaUrls(), snapshot.authorUserId()),
                report.getStatus(),
                report.getCreatedAt()
        );
    }

    public CommentReportItemResponse toItem(CommentReport report) {
        var snapshot = report.getSnapshot();
        return new CommentReportItemResponse(
                report.getId(),
                new CommentReportTargetResponse(report.getPostId(), report.getCommentId()),
                report.getClaim().reporterUserId(),
                report.getClaim().reason(),
                report.getClaim().details().value(),
                new CommentReportSnapshotResponse(snapshot.content(), snapshot.authorUserId(), snapshot.postId()),
                report.getStatus(),
                report.getCreatedAt()
        );
    }
}
