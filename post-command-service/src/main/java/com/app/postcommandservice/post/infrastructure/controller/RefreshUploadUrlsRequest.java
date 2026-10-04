package com.app.postcommandservice.post.infrastructure.controller;

import java.util.List;

import jakarta.validation.Valid;

/**
 * Request body of the refresh-upload-urls endpoint (task 38). {@code media} may be empty (or
 * omitted) when the client holds no SAS urls at all for this post yet, in which case fresh SAS
 * urls are signed for every media item.
 */
public record RefreshUploadUrlsRequest(
        @Valid
        List<RefreshUploadUrlsMediaRequest> media
) {
}
