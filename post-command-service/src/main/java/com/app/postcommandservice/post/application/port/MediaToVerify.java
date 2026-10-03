package com.app.postcommandservice.post.application.port;

import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * A single media item submitted to {@link MediaVerifier}. For {@code IMAGE} media,
 * {@code url} and {@code thumbnailUrl} are expected to be the same blob (as enforced by
 * {@code PostMedia}'s own invariant) and are verified only once.
 */
public record MediaToVerify(UUID id, String url, String thumbnailUrl, MediaType mediaType) {
}
