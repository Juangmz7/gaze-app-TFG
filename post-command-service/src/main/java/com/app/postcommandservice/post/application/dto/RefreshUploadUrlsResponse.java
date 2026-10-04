package com.app.postcommandservice.post.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of the refresh-upload-urls endpoint (task 38): the overall expiry shared by all
 * freshly-issued SAS urls in this response (reused SAS urls keep their own earlier expiry, but
 * the client should treat {@code uploadExpiresAt} as the point by which it must call this
 * endpoint again), and each media item's current upload url(s).
 */
public record RefreshUploadUrlsResponse(
        UUID postId,
        Instant uploadExpiresAt,
        List<RefreshedMediaUpload> media
) {

    public record RefreshedMediaUpload(
            UUID id,
            String uploadUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) String thumbnailUploadUrl
    ) {
    }
}
