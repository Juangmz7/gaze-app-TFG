package com.app.socialservice.report.application.dto;

import java.util.List;

/**
 * nextCursor is null on the last page.
 */
public record UserReportListResponse(
        List<UserReportItemResponse> items,
        String nextCursor
) {
}
