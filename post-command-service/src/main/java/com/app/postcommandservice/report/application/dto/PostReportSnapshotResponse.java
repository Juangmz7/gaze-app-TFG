package com.app.postcommandservice.report.application.dto;

import java.util.List;
import java.util.UUID;

public record PostReportSnapshotResponse(String description, List<String> mediaUrls, UUID authorUserId) {
}
