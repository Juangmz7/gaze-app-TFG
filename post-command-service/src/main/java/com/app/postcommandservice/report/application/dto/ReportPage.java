package com.app.postcommandservice.report.application.dto;

import java.util.List;

import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;

/** {@code nextPosition} is null on the last page. */
public record ReportPage<T>(List<T> items, KeysetPosition nextPosition) {

    public ReportPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
