package com.app.postcommandservice.report.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Evidence captured when a post is reported: the post can be edited or deleted later.
 * Media urls are stored bare (no query string, so no SAS token).
 */
public record PostReportSnapshot(String description, List<String> mediaUrls, UUID authorUserId) {

    public PostReportSnapshot {
        Objects.requireNonNull(authorUserId, "authorUserId must not be null");
        description = description == null ? "" : description;
        mediaUrls = mediaUrls == null
                ? List.of()
                : mediaUrls.stream().filter(Objects::nonNull).map(PostReportSnapshot::bareUrl).toList();
    }

    private static String bareUrl(String url) {
        var queryStart = url.indexOf('?');
        var fragmentStart = url.indexOf('#');
        var end = url.length();
        if (queryStart >= 0) {
            end = queryStart;
        }
        if (fragmentStart >= 0 && fragmentStart < end) {
            end = fragmentStart;
        }
        return url.substring(0, end);
    }
}
