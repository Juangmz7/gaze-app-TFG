package com.app.socialservice.report.domain.model;

import java.util.Map;

/**
 * The reported profile as it was when the report was made (it can be edited or deleted later).
 * profilePic is the bare blob URL, never a signed one.
 */
public record UserReportSnapshot(
        String username,
        String description,
        Map<String, String> socialMedia,
        String profilePic
) {

    public UserReportSnapshot {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        socialMedia = socialMedia == null ? null : Map.copyOf(socialMedia);
    }
}
