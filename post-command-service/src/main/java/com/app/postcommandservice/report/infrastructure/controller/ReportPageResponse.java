package com.app.postcommandservice.report.infrastructure.controller;

import java.util.List;

/** {@code nextCursor} is null on the last page. */
public record ReportPageResponse<T>(List<T> items, String nextCursor) {
}
