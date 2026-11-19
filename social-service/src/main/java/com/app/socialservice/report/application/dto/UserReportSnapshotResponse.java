package com.app.socialservice.report.application.dto;

import java.util.Map;

/**
 * The reported profile at report time. profilePic is returned as stored: the bare URL.
 */
public record UserReportSnapshotResponse(
        String username,
        String description,
        Map<String, String> socialMedia,
        String profilePic
) {

    public UserReportSnapshotResponse {
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
