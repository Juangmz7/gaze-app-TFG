package com.app.postcommandservice.report.application.usecase;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;
import com.app.postcommandservice.report.application.commands.ListReportsQuery;
import com.app.postcommandservice.report.application.dto.CommentReportItemResponse;
import com.app.postcommandservice.report.application.dto.ReportPage;
import com.app.postcommandservice.report.application.mapper.ReportResponseMapper;
import com.app.postcommandservice.report.application.repository.CommentReportRepository;

@Service
@RequiredArgsConstructor
public class ListCommentReportsUseCase {

    private final CommentReportRepository commentReportRepository;
    private final ReportResponseMapper reportResponseMapper;

    @Transactional(readOnly = true)
    public ReportPage<CommentReportItemResponse> list(ListReportsQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        var rows = commentReportRepository.findPage(query.status(), query.after(), query.limit().fetchSize());
        var pageSize = query.limit().value();
        var page = rows.size() > pageSize ? rows.subList(0, pageSize) : rows;

        KeysetPosition next = null;
        if (rows.size() > pageSize) {
            var last = page.get(page.size() - 1);
            next = new KeysetPosition(last.getCreatedAt(), last.getId());
        }
        return new ReportPage<>(page.stream().map(reportResponseMapper::toItem).toList(), next);
    }
}
