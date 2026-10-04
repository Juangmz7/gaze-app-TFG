package com.app.postcommandservice.post.application.commands;

import java.util.List;
import java.util.UUID;

/**
 * Request to refresh (or reuse) the upload SAS urls of a {@code PENDING} post's media
 * (task 38). {@code clientMedia} carries, per media item, the SAS url(s) the client currently
 * holds — this is how the server can recognise a still-valid, previously issued SAS without
 * being able to reverse its persisted BCrypt hash.
 */
public record RefreshUploadUrlsCommand(
        UUID postId,
        UUID userId,
        List<ClientMediaUpload> clientMedia
) {

    public record ClientMediaUpload(
            UUID mediaId,
            String uploadUrl,
            String thumbnailUploadUrl
    ) {
    }
}
