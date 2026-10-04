package com.app.postcommandservice.post.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.app.postcommandservice.post.application.commands.RefreshUploadUrlsCommand.ClientMediaUpload;

/**
 * Per-media item of the refresh-upload-urls request body (task 38): the SAS url(s) the client
 * currently holds for this media item, so the server can recognise a still-valid, previously
 * issued SAS against its persisted BCrypt hash without being able to reverse that hash.
 * {@code uploadUrl}/{@code thumbnailUploadUrl} may be {@code null} (or omitted) when the client
 * never received or no longer holds a SAS for this media item; a fresh one is then signed.
 */
public record RefreshUploadUrlsMediaRequest(
        @NotNull(message = "media id is required")
        UUID id,
        String uploadUrl,
        String thumbnailUploadUrl
) {

    public ClientMediaUpload toClientMediaUpload() {
        return new ClientMediaUpload(id, uploadUrl, thumbnailUploadUrl);
    }
}
